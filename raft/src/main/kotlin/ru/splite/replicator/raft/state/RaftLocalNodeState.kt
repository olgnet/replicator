package ru.splite.replicator.raft.state

import ru.splite.replicator.transport.NodeIdentifier
import java.util.concurrent.ConcurrentHashMap

open class RaftLocalNodeState(
    var currentTerm: Long = 0L
) {

    var lastVotedLeaderIdentifier: NodeIdentifier? = null

    var currentNodeType: NodeType = NodeType.FOLLOWER

    var leaderIdentifier: NodeIdentifier? = null

    val externalNodeStates: MutableMap<NodeIdentifier, ExternalNodeState> = ConcurrentHashMap()

    override fun toString(): String {
        return "RaftLocalNodeState(currentTerm=$currentTerm, lastVotedLeaderIdentifier=$lastVotedLeaderIdentifier, currentNodeType=$currentNodeType, leaderIdentifier=$leaderIdentifier)"
    }
}