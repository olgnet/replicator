package ru.splite.replicator.paxos.message

import ru.splite.replicator.raft.message.AppendEntriesMessageReceiver

interface PaxosMessageReceiver : PaxosVoteRequestMessageReceiver, AppendEntriesMessageReceiver {
}