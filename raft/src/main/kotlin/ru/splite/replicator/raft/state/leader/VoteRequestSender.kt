package ru.splite.replicator.raft.state.leader

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import ru.splite.replicator.log.ReplicatedLogStore
import ru.splite.replicator.raft.message.RaftMessage
import ru.splite.replicator.raft.state.ExternalNodeState
import ru.splite.replicator.raft.state.NodeType
import ru.splite.replicator.raft.state.RaftLocalNodeState
import ru.splite.replicator.transport.NodeIdentifier
import ru.splite.replicator.transport.sender.MessageSender

class VoteRequestSender(
    private val nodeIdentifier: NodeIdentifier,
    private val localNodeState: RaftLocalNodeState,
    private val logStore: ReplicatedLogStore
) {

    suspend fun sendVoteRequestsAsCandidate(
        messageSender: MessageSender<RaftMessage>,
        quorumSize: Int
    ): Boolean = coroutineScope {
        val clusterNodeIdentifiers = messageSender.getAllNodes().minus(nodeIdentifier)

        check(clusterNodeIdentifiers.isNotEmpty()) {
            "Cluster cannot be empty"
        }

        val voteRequest: RaftMessage.VoteRequest = becomeCandidate()

        val voteGrantedCount = clusterNodeIdentifiers
            .map {
                async {
                    val result = kotlin.runCatching {
                        messageSender.sendOrThrow(it, voteRequest) as RaftMessage.VoteResponse
                    }
                    if (result.isFailure) {
                        LOGGER.error("Exception while sending VoteRequest to $it", result.exceptionOrNull())
                    }
                    result.getOrNull()
                }
            }.mapNotNull {
                it.await()
            }.filter {
                it.voteGranted
            }.count() + 1

        LOGGER.info("VoteResult for term ${localNodeState.currentTerm}: ${voteGrantedCount}/${messageSender.getAllNodes().size} (quorum = ${quorumSize})")
        if (voteGrantedCount >= quorumSize) {
            becomeLeader()
            reinitializeExternalNodeStates(messageSender.getAllNodes())
            true
        } else {
            false
        }
    }

    private fun becomeCandidate(): RaftMessage.VoteRequest {
        LOGGER.debug("State transition ${localNodeState.currentNodeType} (term ${localNodeState.currentTerm}) -> CANDIDATE")
        localNodeState.currentTerm = localNodeState.currentTerm + 1
        localNodeState.lastVotedLeaderIdentifier = nodeIdentifier
        localNodeState.currentNodeType = NodeType.CANDIDATE

        val lastLogIndex: Long? = logStore.lastLogIndex()
        val lastLogTerm: Long? = lastLogIndex?.let { logStore.getLogEntryByIndex(it)!!.term }

        return RaftMessage.VoteRequest(
            term = localNodeState.currentTerm, candidateIdentifier = nodeIdentifier,
            lastLogIndex = lastLogIndex ?: -1, lastLogTerm = lastLogTerm ?: -1
        )
    }

    private fun becomeLeader() {
        LOGGER.debug("State transition ${localNodeState.currentNodeType} -> LEADER (term ${localNodeState.currentTerm})")
        localNodeState.lastVotedLeaderIdentifier = null
        localNodeState.leaderIdentifier = nodeIdentifier
        localNodeState.currentNodeType = NodeType.LEADER
    }

    private fun reinitializeExternalNodeStates(clusterNodeIdentifiers: Collection<NodeIdentifier>) {
        val lastLogIndex = logStore.lastLogIndex()?.plus(1) ?: 0
        clusterNodeIdentifiers.forEach { dstNodeIdentifier ->
            localNodeState.externalNodeStates[dstNodeIdentifier] =
                ExternalNodeState(nextIndex = lastLogIndex, matchIndex = -1)
        }
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(javaClass.enclosingClass)
    }
}