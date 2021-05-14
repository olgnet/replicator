package ru.splite.replicator.atlas.executor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import ru.splite.replicator.atlas.graph.Dependency
import ru.splite.replicator.atlas.graph.DependencyGraph
import ru.splite.replicator.atlas.id.Id
import ru.splite.replicator.atlas.state.Command
import ru.splite.replicator.metrics.Metrics
import ru.splite.replicator.metrics.Metrics.measureAndRecord
import ru.splite.replicator.statemachine.StateMachine
import ru.splite.replicator.transport.NodeIdentifier
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

class CommandExecutor(
    private val dependencyGraph: DependencyGraph<Dependency>,
    private val stateMachine: StateMachine<ByteArray, ByteArray>
) {

    private val commandBlockersChannel = Channel<Id<NodeIdentifier>>(capacity = Channel.UNLIMITED)

    val commandBlockersFlow: Flow<Id<NodeIdentifier>> = commandBlockersChannel.receiveAsFlow()

    private val commandBuffer = ConcurrentHashMap<Id<NodeIdentifier>, Command>()

    private val completableDeferredResponses = ConcurrentHashMap<Id<NodeIdentifier>, CompletableDeferred<ByteArray>>()

    private val committedChannel = MutableStateFlow<Id<NodeIdentifier>?>(null)

    private val graphMutex = Mutex()

    suspend fun awaitCommandResponse(commandId: Id<NodeIdentifier>, action: suspend () -> Unit): ByteArray {
        try {
            val deferredResponse = completableDeferredResponses.getOrPut(commandId) {
                CompletableDeferred()
            }
            action()
            return deferredResponse.await()
        } finally {
            completableDeferredResponses.remove(commandId)
        }
    }

    fun commit(commandId: Id<NodeIdentifier>, command: Command, dependencies: Set<Dependency>) {
        commandBuffer[commandId] = command
        dependencyGraph.commit(Dependency(commandId), dependencies)
        committedChannel.value = commandId
        LOGGER.debug("Added to graph commandId=$commandId, dependencies=${dependencies.map { it.dot }}")
    }

    fun launchCommandExecutor(coroutineContext: CoroutineContext, coroutineScope: CoroutineScope): Job {
        return coroutineScope.launch(coroutineContext) {

            val executableCommandChannel = produce(capacity = Channel.UNLIMITED) {
                committedChannel.collect {
                    Metrics.registry.atlasCommandExecutorLatency.measureAndRecord {
                        fetchAvailableCommands().forEach {
                            send(it.dot)
                        }
                    }
                }
            }

            for (command in executableCommandChannel) {
                executeCommand(command)
            }
        }
    }

    private suspend fun fetchAvailableCommands(): Collection<Dependency> {
        val keysToExecute = graphMutex.withLock {
            dependencyGraph.evaluateKeyToExecute()
        }

        LOGGER.debug(
            "Executing available commands. " +
                    "executable=${keysToExecute.executable.map { it.dot }} " +
                    "blockers=${keysToExecute.blockers.map { it.dot }}"
        )

        keysToExecute.blockers.forEach {
            commandBlockersChannel.send(it.dot)
        }

        return keysToExecute.executable
    }

    private fun executeCommand(commandId: Id<NodeIdentifier>) {
        LOGGER.debug("Executing on state machine commandId=$commandId")
        val response = kotlin.runCatching {
            when (val commandToExecute = commandBuffer.remove(commandId)) {
                is Command.WithPayload -> {
                    stateMachine.apply(commandToExecute.payload)
                }
                is Command.WithNoop -> null
                else -> error(
                    "Cannot extract command from buffer. " +
                            "value=$commandToExecute, commandId=$commandId"
                )
            }
        }
        LOGGER.debug("Executed on state machine commandId=$commandId")
        completableDeferredResponses[commandId]?.let { deferredResponse ->
            response.onSuccess { payload ->
                if (payload == null) {
                    LOGGER.warn("Command cannot be completed because value is NOOP. commandId=$commandId")
                } else {
                    deferredResponse.complete(payload)
                }
            }.onFailure { exception ->
                LOGGER.error(
                    "Cannot execute command because of nested exception. commandId=$commandId",
                    exception
                )
                deferredResponse.completeExceptionally(exception)
                throw exception
            }
        }
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(javaClass.enclosingClass)
    }
}