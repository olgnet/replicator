package ru.splite.replicator.paxos.state.follower

import org.slf4j.LoggerFactory
import ru.splite.replicator.log.ReplicatedLogStore
import ru.splite.replicator.paxos.message.PaxosMessage
import ru.splite.replicator.paxos.state.PaxosLocalNodeState

class VoteRequestHandler<C>(
    private val localNodeState: PaxosLocalNodeState<C>,
    private val logStore: ReplicatedLogStore<C>
) {

    fun handleVoteRequest(request: PaxosMessage.VoteRequest): PaxosMessage.VoteResponse<C> {

        //текущий терм больше полученного -> получили устаревший запрос -> отклоняем
        if (localNodeState.currentTerm > request.term) {
            LOGGER.debug("${localNodeState.nodeIdentifier} :: VoteRequest rejected: currentTerm ${localNodeState.currentTerm} > requestTerm ${request.term}. request = $request")
            return PaxosMessage.VoteResponse(
                term = localNodeState.currentTerm,
                voteGranted = false,
                entries = emptyList()
            )
        }

        val fromLogIndex: Long = request.leaderCommit?.plus(1) ?: 0

        val entries = generateSequence(fromLogIndex) {
            it + 1
        }.map {
            logStore.getLogEntryByIndex(it)
        }.takeWhile {
            it != null
        }.filterNotNull().toList()


        return PaxosMessage.VoteResponse(
            term = localNodeState.currentTerm,
            voteGranted = true,
            entries = entries
        )
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(javaClass.enclosingClass)
    }
}