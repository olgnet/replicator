package ru.splite.replicator.raft.state.leader

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import ru.splite.replicator.bus.ClusterTopology
import ru.splite.replicator.bus.NodeIdentifier
import ru.splite.replicator.log.ReplicatedLogStore
import ru.splite.replicator.raft.message.AppendEntriesMessageReceiver
import ru.splite.replicator.raft.message.RaftMessage
import ru.splite.replicator.raft.state.NodeType
import ru.splite.replicator.raft.state.RaftLocalNodeState

class AppendEntriesSender(
    private val localNodeState: RaftLocalNodeState,
    private val logStore: ReplicatedLogStore
) {

    private data class AppendEntriesResult(
        val dstNodeIdentifier: NodeIdentifier,
        val matchIndex: Long,
        val isSuccess: Boolean
    )

    suspend fun sendAppendEntriesIfLeader(clusterTopology: ClusterTopology<AppendEntriesMessageReceiver>) =
        coroutineScope {
            val clusterNodeIdentifiers = clusterTopology.nodes.minus(localNodeState.nodeIdentifier)

            clusterNodeIdentifiers.map { dstNodeIdentifier ->
                val nextIndexPerNode: Long = localNodeState.externalNodeStates[dstNodeIdentifier]!!.nextIndex
                val appendEntriesRequest: RaftMessage.AppendEntries =
                    buildAppendEntries(fromIndex = nextIndexPerNode)
                val matchIndexIfSuccess = nextIndexPerNode + appendEntriesRequest.entries.size - 1
                val deferredAppendEntriesResult: Deferred<AppendEntriesResult> = async {
                    kotlin.runCatching {
                        val appendEntriesResponse = withTimeout(1000) {
                            clusterTopology[dstNodeIdentifier].handleAppendEntries(appendEntriesRequest)
                    }
                    AppendEntriesResult(dstNodeIdentifier, matchIndexIfSuccess, appendEntriesResponse.entriesAppended)
                }.getOrElse {
                    AppendEntriesResult(dstNodeIdentifier, matchIndexIfSuccess, false)
                }
            }
            deferredAppendEntriesResult
        }.map { deferredAppendEntriesResult ->
            val appendEntriesResult = deferredAppendEntriesResult.await()
            if (appendEntriesResult.isSuccess) {
                localNodeState.externalNodeStates[appendEntriesResult.dstNodeIdentifier]!!.matchIndex =
                    appendEntriesResult.matchIndex
                localNodeState.externalNodeStates[appendEntriesResult.dstNodeIdentifier]!!.nextIndex =
                    appendEntriesResult.matchIndex + 1
            } else {
                localNodeState.externalNodeStates[appendEntriesResult.dstNodeIdentifier]!!.nextIndex =
                    maxOf(0, localNodeState.externalNodeStates[appendEntriesResult.dstNodeIdentifier]!!.nextIndex - 1)
            }
        }
        Unit
    }

    private fun buildAppendEntries(fromIndex: Long): RaftMessage.AppendEntries {
        if (fromIndex < 0) {
            error("fromIndex cannot be negative")
        }

        if (localNodeState.currentNodeType != NodeType.LEADER) {
            LOGGER.warn("${localNodeState.nodeIdentifier} :: cannot send appendEntries because node is not leader. currentNodeType = ${localNodeState.currentNodeType}")
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
                leaderIdentifier = localNodeState.nodeIdentifier,
                prevLogIndex = prevLogIndex,
                prevLogTerm = prevLogTerm,
                lastCommitIndex = lastCommitIndex,
                entries = entries
            )
        }

        val lastLogTerm = lastLogIndex?.let { logStore.getLogEntryByIndex(it)!!.term }

        return RaftMessage.AppendEntries(
            term = localNodeState.currentTerm,
            leaderIdentifier = localNodeState.nodeIdentifier,
            prevLogIndex = lastLogIndex,
            prevLogTerm = lastLogTerm,
            lastCommitIndex = lastCommitIndex,
            entries = emptyList()
        )
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(javaClass.enclosingClass)
    }
}