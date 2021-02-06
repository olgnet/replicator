package ru.splite.replicator.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import ru.splite.replicator.bus.NodeIdentifier
import java.util.concurrent.ConcurrentHashMap

class CoroutineChannelTransport(private val coroutineScope: CoroutineScope) : Transport {

    private class ChannelMessage(
        val src: NodeIdentifier,
        val payload: ByteArray,
        val response: CompletableDeferred<ByteArray> = CompletableDeferred()
    )

    private val actors: MutableMap<NodeIdentifier, SendChannel<ChannelMessage>> = ConcurrentHashMap()

    private val isolatedActors: MutableSet<NodeIdentifier> = mutableSetOf()

    override val nodes: Collection<NodeIdentifier>
        get() = actors.keys

    fun isolate(nodeIdentifier: NodeIdentifier) {
        isolatedActors.add(nodeIdentifier)
    }

    fun open(nodeIdentifier: NodeIdentifier) {
        isolatedActors.remove(nodeIdentifier)
    }

    override fun subscribe(address: NodeIdentifier, actor: Actor) {
        val name = address.toString()
        val channel = coroutineScope.actor<ChannelMessage>(CoroutineName(name) + SupervisorJob(), Int.MAX_VALUE) {
            for (message in channel) {
                try {
                    if (isolatedActors.contains(address)) {
                        throw NodeUnavailableException("Node $address isolated")
                    }
                    val response = actor.receive(message.src, message.payload)
                    message.response.complete(response)
                } catch (e: Throwable) {
                    message.response.completeExceptionally(e)
                }
            }
        }
        actors[address] = channel
    }

    override suspend fun send(actor: Actor, dst: NodeIdentifier, payload: ByteArray): ByteArray {
        val dstChannel = actors[dst] ?: error("Address ${dst} is not registered")
        val message = ChannelMessage(actor.address, payload)
        dstChannel.send(message)
        return message.response.await()
    }
}