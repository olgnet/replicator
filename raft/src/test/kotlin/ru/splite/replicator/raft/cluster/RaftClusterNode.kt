package ru.splite.replicator.raft.cluster

import ru.splite.replicator.keyvalue.KeyValueStateMachine
import ru.splite.replicator.log.ReplicatedLogStore
import ru.splite.replicator.raft.RaftCommandSubmitter
import ru.splite.replicator.raft.RaftProtocol
import ru.splite.replicator.transport.NodeIdentifier

class RaftClusterNode(
    val address: NodeIdentifier,
    val protocol: RaftProtocol,
    val commandSubmitter: RaftCommandSubmitter,
    val logStore: ReplicatedLogStore,
    val stateMachine: KeyValueStateMachine
)