package hu.nevermind.rotester

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.channels.actor
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

sealed class IncomingPacketSubscriberMessage
data class IncomingPacketArrivedMessage(val incomingPacket: FromServer.Packet) : IncomingPacketSubscriberMessage()

data class IncaseOfPacketMessage(val expectedPacketClass: KClass<FromServer.Packet>,
                                 val timeout: Long,
                                 val timeoutAction: (() -> Unit)? = null,
                                 val responseChannel: Channel<FromServer.Packet>,
                                 val waitUntilTimeout: Boolean = false,
                                 val predicate: ((FromServer.Packet) -> Boolean)? = null) : IncomingPacketSubscriberMessage()

class ClearHistory(val responseChannel: Channel<Any>) : IncomingPacketSubscriberMessage()

class PacketArrivalVerifier() {

    private val logger: Logger = LoggerFactory.getLogger(this::class.simpleName)

    suspend fun <T> collectIncomingPackets(expectedPacketClass: KClass<T>, timeout: Long, predicate: ((T) -> Boolean)? = null): List<T>
            where T : FromServer.Packet {
        val responseChannel = Channel<FromServer.Packet>()
        val incomingPackets = mutableListOf<T>()
        logger.trace("waiting for ${expectedPacketClass.simpleName}-s ${if (predicate != null) "[with predicate]" else ""}")
        actor.send(IncaseOfPacketMessage(expectedPacketClass as KClass<FromServer.Packet>, timeout,
                timeoutAction = {
                    responseChannel.close(CancellationException())
                },
                responseChannel = responseChannel,
                waitUntilTimeout = true,
                predicate = predicate as ((FromServer.Packet) -> Boolean)?
        ))
        while (true) {
            try {
                val packet = responseChannel.receive() as T
                incomingPackets.add(packet)
            } catch (e: CancellationException) {
                return incomingPackets
            }
        }
    }

    suspend fun <T> waitForPacket(expectedPacketClass: KClass<T>, timeout: Long, predicate: ((T) -> Boolean)? = null): T
            where T : FromServer.Packet {
        val responseChannel = Channel<FromServer.Packet>()
        logger.trace("waiting for ${expectedPacketClass.simpleName}${if (predicate != null) "[with predicate]" else ""}")
        actor.send(IncaseOfPacketMessage(expectedPacketClass as KClass<FromServer.Packet>, timeout,
                timeoutAction = {
                    responseChannel.close(CancellationException("Waited for ${expectedPacketClass.simpleName}${if (predicate != null) "[with predicate]" else ""}, but didn't arrived."))
                },
                responseChannel = responseChannel,
                predicate = predicate as ((FromServer.Packet) -> Boolean)?
        ))
        val packet = responseChannel.receive()
        return packet as T
    }

    suspend fun cleanPacketHistory() {
        logger.debug("cleaning packet history")
        val responseChannel = Channel<Any>()
        actor.send(ClearHistory(responseChannel))
        responseChannel.receive()
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

    val actor = actor<IncomingPacketSubscriberMessage>(CommonPool) {
        val tasks = mutableSetOf<IncaseOfPacketMessage>()
        val packetHistory = arrayListOf<FromServer.Packet>()
        val expectedPacketArrivedList = arrayListOf<IncaseOfPacketMessage>()
        for (msg in channel) {
            when (msg) {
                is IncomingPacketArrivedMessage -> {
                    val found = tasks.firstOrNull { task ->
                        msg.incomingPacket::class == task.expectedPacketClass && task.predicate?.invoke(msg.incomingPacket) ?: true
                    }
                    if (found != null) {
                        found.responseChannel.send(msg.incomingPacket)
                        expectedPacketArrivedList.add(found)
                        if (!found.waitUntilTimeout) {
                            tasks.remove(found)
                        }
                    } else {
                        packetHistory.add(msg.incomingPacket)
                    }
                }
                is IncaseOfPacketMessage -> {
                    val expectedPacketsInHistory = packetHistory.filter { it::class.isSubclassOf(msg.expectedPacketClass) && msg.predicate?.invoke(it) ?: true }
                    if (expectedPacketsInHistory.isNotEmpty()) {
                        expectedPacketsInHistory.forEach { p -> msg.responseChannel.send(p) }
                        packetHistory.removeAll(expectedPacketsInHistory)
                    }
                    if (expectedPacketsInHistory.isEmpty() || msg.waitUntilTimeout) {
                        tasks.add(msg)
                        launch(CommonPool) {
                            try {
                                withTimeout(msg.timeout) {
                                    while (true) {
                                        delay(1000)
                                    }
                                }
                            } catch (e: CancellationException) {
                                tasks.remove(msg)
                                if (!expectedPacketArrivedList.contains(msg) || msg.waitUntilTimeout) {
                                    msg.timeoutAction?.invoke()
                                    expectedPacketArrivedList.remove(msg)
                                } else {
                                    expectedPacketArrivedList.remove(msg)
                                }
                            }
                        }
                    }
                }
                is ClearHistory -> {
                    packetHistory.clear()
                    msg.responseChannel.send("done")
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
            try {
                while (true) {
                    readIncomingPocketsFromSocket()
                    delay(10)
                }
            } catch (e: Exception) {
                logger.error("asyncStartProcessingIncomingPackets", e)
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
