package hu.nevermind.rotester

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.channels.actor
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

sealed class IncomingPacketSubscriberMessage
data class IncomingPacketArrivedMessage(val incomingPacket: FromServer.Packet) : IncomingPacketSubscriberMessage()

data class IncaseOfPacketMessage(val expectedPacketClass: KClass<FromServer.Packet>,
                                 val timeout: Long,
                                 val timeoutAction: (() -> Unit)? = null,
                                 val responseChannel: Channel<FromServer.Packet>,
                                 val predicate: ((FromServer.Packet)->Boolean)? = null) : IncomingPacketSubscriberMessage()

class ClearHistory() : IncomingPacketSubscriberMessage()

class PacketArrivalVerifier() {

    private val logger: Logger = LoggerFactory.getLogger(this::class.simpleName)

    suspend fun <T> waitForPacket(expectedPacketClass: KClass<T>, timeout: Long, predicate: ((T)->Boolean)? = null): T
            where T : FromServer.Packet {
        val responseChannel = Channel<FromServer.Packet>()
        var ok = false
        return async(CommonPool) {
            logger.trace("waiting for $expectedPacketClass${if (predicate!= null) "[with predicate]" else ""}")
            actor.send(IncaseOfPacketMessage(expectedPacketClass as KClass<FromServer.Packet>, timeout,
                    timeoutAction = {
                        if (!ok) {
                            responseChannel.close(CancellationException("Waited for ${expectedPacketClass}${if (predicate!= null) "[with predicate]" else ""}, but didn't arrived."))
                        }
                    },
                    responseChannel = responseChannel,
                    predicate = predicate as ((FromServer.Packet) -> Boolean)?
            ))
            val packet = responseChannel.receive()
            ok = true
            packet
        }.await() as T
    }

    suspend fun cleanPacketHistory() {
        logger.debug("cleaning packet history")
        actor.send(ClearHistory())
    }

    suspend fun <T> inCaseOf(expectedPacketClass: KClass<T>, timeout: Long = 5000, action: (T) -> Unit)
            where T : FromServer.Packet {
        val responseChannel = Channel<FromServer.Packet>()
        val deferredArrivedPacked = async(CommonPool) {
            actor.send(IncaseOfPacketMessage(
                    expectedPacketClass as KClass<FromServer.Packet>,
                    timeout,
                    responseChannel = responseChannel)
            )
            responseChannel.receive() as T
        }
        deferredArrivedPacked.invokeOnCompletion { completionHandler ->
            val thereWasNoCancellationTimeout = completionHandler == null
            if (thereWasNoCancellationTimeout) {
                action(deferredArrivedPacked.getCompleted())
            }
        }
    }

    suspend fun <T> expectPacket(expectedPacketClass: KClass<T>, expectedPacketCount: Int, timeout: Long)
            where T : FromServer.Packet {
        var arrivedCount = 0
        val responseChannel = Channel<FromServer.Packet>()
        (0..expectedPacketCount).forEach {
            launch(CommonPool) {
                actor.send(IncaseOfPacketMessage(expectedPacketClass as KClass<FromServer.Packet>, timeout,
                        responseChannel = responseChannel,
                        timeoutAction = {
                            if (arrivedCount == expectedPacketCount) {
                                // ok
                            } else if (arrivedCount > expectedPacketCount) {
                                responseChannel.close(IllegalStateException("Expected for ${expectedPacketClass} ${expectedPacketCount} times, but arrived ${arrivedCount}"))
                            } else {
                                responseChannel.close(IllegalStateException("Expected for ${expectedPacketClass}, but didn't arrived"))
                            }
                        }))
                responseChannel.receive()
                logger.debug("Expected packet arrived: ${expectedPacketClass}")
                arrivedCount++
            }
        }
    }

    val actor = actor<IncomingPacketSubscriberMessage>(CommonPool) {
        val tasks = arrayListOf<IncaseOfPacketMessage>()
        val packetHistory = arrayListOf<FromServer.Packet>()
        for (msg in channel) {
            when (msg) {
                is IncomingPacketArrivedMessage -> {
                    val found = tasks.firstOrNull { task ->
                        msg.incomingPacket::class == task.expectedPacketClass && task.predicate?.invoke(msg.incomingPacket)?:true
                    }
                    if (found != null) {
                        found.responseChannel.send(msg.incomingPacket)
                        tasks.remove(found)
                    } else {
                        packetHistory.add(msg.incomingPacket)
                    }
                }
                is IncaseOfPacketMessage -> {
                    val expectedPacketsInHistory = packetHistory.filter { it::class == msg.expectedPacketClass && msg.predicate?.invoke(it)?:true}
                    if (expectedPacketsInHistory.isNotEmpty()) {
                        expectedPacketsInHistory.forEach { p -> msg.responseChannel.send(p) }
                        packetHistory.removeAll(expectedPacketsInHistory)
                    } else {
                        tasks.add(msg)
                        launch(CommonPool) {
                            try {
                                withTimeout(msg.timeout) {
                                    while (true) {
                                        delay(1000)
                                    }
                                }
                            } catch (e: CancellationException) {
                                msg.timeoutAction?.invoke()
                            }
                        }
                    }
                }
                is ClearHistory -> {
                    packetHistory.clear()
                }
            }
        }
    }
}

class Session(val name: String, val connection: Connection) : AutoCloseable {

    private val logger: Logger = LoggerFactory.getLogger(this::class.simpleName)

    override fun close() {
        connection.close()
        logger.info("$name: Close session. Buffer: \n" + connection.getHexDump())
    }

    private var packetArrivalSubscribers: MutableList<SendChannel<IncomingPacketArrivedMessage>> = arrayListOf()

    fun asyncStartProcessingIncomingPackets() {
        launch(CommonPool) {
            while (true) {
                readIncomingPocketsFromSocket()
                delay(10)
            }
        }
    }

    suspend fun readIncomingPocketsFromSocket() {
        val incomingPackets = connection.readPackets()
        incomingPackets.forEach { incomingPacket ->
            packetArrivalSubscribers.forEach {
                it.send(IncomingPacketArrivedMessage(incomingPacket))
            }
        }
    }

    fun subscribeForPackerArrival(actor: SendChannel<IncomingPacketArrivedMessage>) {
        packetArrivalSubscribers.add(actor)
    }

    fun unSubscribeForPackerArrival(subscriber: SendChannel<IncomingPacketArrivedMessage>) {
        require(packetArrivalSubscribers.remove(subscriber))
    }

    suspend fun send(packet: ToServer.Packet) {
        logger.trace("[Send] $packet")
        connection.fill(packet)
        connection.send()
    }
}
