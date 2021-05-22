package ru.splite.replicator.raft.protocol.leader

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import ru.splite.replicator.log.ReplicatedLogStore
import ru.splite.replicator.raft.message.RaftMessage
import ru.splite.replicator.raft.state.ExternalNodeState
import ru.splite.replicator.raft.state.NodeStateStore
import ru.splite.replicator.raft.state.NodeType
import ru.splite.replicator.transport.NodeIdentifier
import ru.splite.replicator.transport.sender.MessageSender

internal class AppendEntriesSender(
    private val nodeIdentifier: NodeIdentifier,
    private val localNodeStateStore: NodeStateStore,
    private val logStore: ReplicatedLogStore
) {

    private data class AppendEntriesResult(
        val dstNodeIdentifier: NodeIdentifier,
        val matchIndex: Long,
        val isSuccess: Boolean
    )

    suspend fun sendAppendEntriesIfLeader(
        nodeIdentifiers: Collection<NodeIdentifier>,
        messageSender: MessageSender<RaftMessage>
    ) =
        coroutineScope {
            LOGGER.info("Sending AppendEntries (term ${localNodeStateStore.getState().currentTerm})")

            nodeIdentifiers.map { dstNodeIdentifier ->
                val nextIndexPerNode: Long = localNodeStateStore.getExternalNodeState(dstNodeIdentifier).nextIndex
                val appendEntriesRequest: RaftMessage.AppendEntries =
                    buildAppendEntries(fromIndex = nextIndexPerNode)
                val matchIndexIfSuccess = nextIndexPerNode + appendEntriesRequest.entries.size - 1
                val deferredAppendEntriesResult: Deferred<AppendEntriesResult> = async {
                    kotlin.runCatching {
                        val appendEntriesResponse = messageSender.sendOrThrow(dstNodeIdentifier, appendEntriesRequest)
                                as RaftMessage.AppendEntriesResponse
                        AppendEntriesResult(
                            dstNodeIdentifier,
                            matchIndexIfSuccess,
                            appendEntriesResponse.entriesAppended
                        )
                    }.getOrElse {
                        LOGGER.trace("Exception while sending AppendEntries to $dstNodeIdentifier", it)
                        AppendEntriesResult(dstNodeIdentifier, matchIndexIfSuccess, false)
                    }
                }
                deferredAppendEntriesResult
            }.map { deferredAppendEntriesResult ->
                val appendEntriesResult = deferredAppendEntriesResult.await()
                if (appendEntriesResult.isSuccess) {
                    val matchIndex = appendEntriesResult.matchIndex
                    localNodeStateStore.setExternalNodeState(
                        appendEntriesResult.dstNodeIdentifier,
                        ExternalNodeState(nextIndex = matchIndex + 1, matchIndex = matchIndex)
                    )
                } else {
                    val currentState = localNodeStateStore.getExternalNodeState(appendEntriesResult.dstNodeIdentifier)
                    localNodeStateStore.setExternalNodeState(
                        appendEntriesResult.dstNodeIdentifier,
                        ExternalNodeState(
                            nextIndex = maxOf(0, currentState.nextIndex - 1),
                            matchIndex = currentState.matchIndex
                        )
                    )
                }
            }
            Unit
        }

    private suspend fun buildAppendEntries(fromIndex: Long): RaftMessage.AppendEntries =
        localNodeStateStore.getState().let { localNodeState ->
            if (fromIndex < 0) {
                error("fromIndex cannot be negative")
            }

            if (localNodeState.currentNodeType != NodeType.LEADER) {
                LOGGER.warn("Cannot send appendEntries because node is not leader. currentNodeType = ${localNodeState.currentNodeType}")
                error("Cannot send appendEntries because node is not leader")
            }

            val lastLogIndex: Long? = logStore.lastLogIndex()

            val lastCommitIndex: Long? = logStore.lastCommitIndex()

            if (lastLogIndex != null && fromIndex <= lastLogIndex) {

                val prevLogIndex: Long? = if (fromIndex > 0) fromIndex - 1 else null

                val prevLogTerm: Long? = prevLogIndex?.let { logStore.getLogEntryByIndex(it)!!.term }

                val entries = (fromIndex..lastLogIndex).map { logStore.getLogEntryByIndex(it)!! }.toList()

                return RaftMessage.AppendEntries(
                    term = localNodeState.currentTerm,
                    leaderIdentifier = nodeIdentifier,
                    prevLogIndex = prevLogIndex ?: -1,
                    prevLogTerm = prevLogTerm ?: -1,
                    lastCommitIndex = lastCommitIndex ?: -1,
                    entries = entries
                )
            }

            val lastLogTerm = lastLogIndex?.let { logStore.getLogEntryByIndex(it)!!.term }

            return RaftMessage.AppendEntries(
                term = localNodeState.currentTerm,
                leaderIdentifier = nodeIdentifier,
                prevLogIndex = lastLogIndex ?: -1,
                prevLogTerm = lastLogTerm ?: -1,
                lastCommitIndex = lastCommitIndex ?: -1,
                entries = emptyList()
            )
        }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(javaClass.enclosingClass)
    }
}