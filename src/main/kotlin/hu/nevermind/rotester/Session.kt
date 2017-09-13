package hu.nevermind.rotester

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.ReceiveChannel
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

class WaitingForPacketTimeout(msg: String) : RuntimeException(msg)

class ClearHistory(val responseChannel: Channel<Any>) : IncomingPacketSubscriberMessage()

class PacketArrivalVerifier(val name: String, session: Session) {

    private val logger: Logger = LoggerFactory.getLogger(this::class.simpleName)

    suspend fun <T> collectIncomingPackets(expectedPacketClass: KClass<T>, timeout: Long, predicate: ((T) -> Boolean)? = null): List<T>
            where T : FromServer.Packet {
        val responseChannel = Channel<FromServer.Packet>()
        val incomingPackets = mutableListOf<T>()
        logger.trace("[$name] Collecting ${expectedPacketClass.simpleName}-s ${if (predicate != null) "[with predicate]" else ""}")
        actor.send(IncaseOfPacketMessage(expectedPacketClass as KClass<FromServer.Packet>, timeout,
                timeoutAction = {
                    logger.trace("[$name] Collection for ${expectedPacketClass.simpleName}-s ${if (predicate != null) "[with predicate]" else ""} ended")
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

    suspend fun <T> waitForPacket(expectedPacketClass: KClass<T>, timeout: Long,
                                  predicate: ((T) -> Boolean)? = null): T
            where T : FromServer.Packet {
        val timeoutChannel = Channel<Int>()
        val responseChannel = Channel<FromServer.Packet>()
        val mixedChannel = multiplex(responseChannel, timeoutChannel)
        logger.trace("[$name] waiting for ${expectedPacketClass.simpleName}${if (predicate != null) "[with predicate]" else ""}")
        actor.send(IncaseOfPacketMessage(expectedPacketClass as KClass<FromServer.Packet>, timeout,
                timeoutAction = {
                    async(CommonPool) {
                        timeoutChannel.send(0)
                    }
                },
                responseChannel = responseChannel,
                predicate = predicate as ((FromServer.Packet) -> Boolean)?
        ))
        val packet = mixedChannel.receive()
        if (packet is Int) {
            throw WaitingForPacketTimeout("[$name] Waited for ${expectedPacketClass.simpleName}${if (predicate != null) "[with predicate]" else ""}, but didn't arrived.")
        } else {
            return packet as T
        }
    }

    suspend fun multiplex(input1: ReceiveChannel<Any>, input2: ReceiveChannel<Any>): ReceiveChannel<Any> {
        val c = Channel<Any>()
        async(CommonPool) {
            for (v in input1)
                c.send(v)
        }
        async(CommonPool) {
            for (v in input2)
                c.send(v)
        }
        return c
    }

    suspend fun cleanPacketHistory() {
        logger.debug("[$name] cleaning packet history")
        val responseChannel = Channel<Any>()
        actor.send(ClearHistory(responseChannel))
        responseChannel.receive()
    }

    suspend fun <T> inCaseOf(expectedPacketClass: KClass<T>, timeout: Long = 5000, action: suspend (T) -> Unit)
            where T : FromServer.Packet {
        val responseChannel = Channel<FromServer.Packet>()
        val deferredArrivedPacket = async(CommonPool) {
            actor.send(IncaseOfPacketMessage(
                    expectedPacketClass as KClass<FromServer.Packet>,
                    timeout,
                    responseChannel = responseChannel)
            )
            responseChannel.receive() as T
        }
        deferredArrivedPacket.invokeOnCompletion { completionHandler ->
            val thereWasNoCancellationTimeout = completionHandler == null
            if (thereWasNoCancellationTimeout) {
                async(CommonPool) {
                    action(deferredArrivedPacket.getCompleted())
                }
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
                        msg.incomingPacket::class.isSubclassOf(task.expectedPacketClass) && task.predicate?.invoke(msg.incomingPacket) ?: true
                    }
                    if (found != null) {
                        found.responseChannel.send(msg.incomingPacket)
                        expectedPacketArrivedList.add(found)
                        if (!found.waitUntilTimeout) {
                            tasks.remove(found)
                        } else {
                            packetHistory.add(msg.incomingPacket)
                        }
                    } else {
                        packetHistory.add(msg.incomingPacket)
                    }
                }
                is IncaseOfPacketMessage -> {
                    val expectedPacketsInHistory = packetHistory.filter { it::class.isSubclassOf(msg.expectedPacketClass) && msg.predicate?.invoke(it) ?: true }
                    if (expectedPacketsInHistory.isNotEmpty()) {
                        expectedPacketsInHistory.forEach { p -> msg.responseChannel.send(p) }
                        if (!msg.waitUntilTimeout) {
                            packetHistory.removeAll(expectedPacketsInHistory)
                        }
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

    init {
        session.subscribeForPackerArrival(this.actor.channel)
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
                logger.error("[$name] asyncStartProcessingIncomingPackets", e)
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
        logger.trace("[$name] [Send] $packet")
        connection.fill(packet)
        connection.send()
    }
}
