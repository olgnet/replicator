package ru.splite.replicator.executor

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import ru.splite.replicator.bus.NodeIdentifier
import ru.splite.replicator.graph.Dependency
import ru.splite.replicator.graph.DependencyGraph
import ru.splite.replicator.id.Id
import ru.splite.replicator.statemachine.StateMachine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

class CommandExecutor(
    private val dependencyGraph: DependencyGraph<Dependency>,
    private val stateMachine: StateMachine<ByteArray, ByteArray>
) {

    private object NewCommitEvent

    private val commandBuffer = ConcurrentHashMap<Id<NodeIdentifier>, ByteArray>()

    private val completableDeferredResponses = ConcurrentHashMap<Id<NodeIdentifier>, CompletableDeferred<ByteArray>>()

    private val committedChannel = Channel<NewCommitEvent>(capacity = Channel.UNLIMITED)

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

    suspend fun commit(commandId: Id<NodeIdentifier>, command: ByteArray, dependencies: Set<Dependency>) {
        //TODO noop
        commandBuffer[commandId] = command
        graphMutex.withLock {
            dependencyGraph.commit(Dependency(commandId), dependencies)
        }
        committedChannel.offer(NewCommitEvent)
        LOGGER.debug("Queued commandId=${commandId}")
    }

    fun launchCommandExecutor(coroutineContext: CoroutineContext, coroutineScope: CoroutineScope): Job {
        return coroutineScope.launch(coroutineContext) {
            for (newCommitEvent in committedChannel) {
                executeAvailableCommands()
            }
        }
    }

    private suspend fun executeAvailableCommands() {
        val keysToExecute = graphMutex.withLock {
            dependencyGraph.evaluateKeyToExecute()
        }

        LOGGER.debug(
            "Executing available commands. " +
                    "executable=${keysToExecute.executable.map { it.dot }} " +
                    "blockers=${keysToExecute.blockers.map { it.dot }}"
        )

        keysToExecute.executable.forEach {
            executeCommand(it.dot)
        }
    }

    private fun executeCommand(commandId: Id<NodeIdentifier>) {
        LOGGER.debug("Committing commandId=$commandId")
        val response = kotlin.runCatching {
            stateMachine.commit(
                commandBuffer.remove(commandId)
                    ?: error("Cannot extract command from buffer. commandId = $commandId")
            )
        }
        LOGGER.debug("Committed commandId=$commandId")

        completableDeferredResponses[commandId]?.let { deferredResponse ->
            deferredResponse.completeWith(response)
            if (response.isFailure) {
                LOGGER.error(
                    "Cannot commit commandId=$commandId because of nested exception",
                    response.exceptionOrNull()
                )
                response.getOrThrow()
            } else {
                LOGGER.debug("Completed deferred for awaiting client request. commandId=$commandId")
            }
        }
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(javaClass.enclosingClass)
    }
}