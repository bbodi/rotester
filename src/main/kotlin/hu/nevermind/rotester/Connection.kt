package hu.nevermind.rotester

import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.channels.produce
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import org.apache.commons.io.FileUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetSocketAddress
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.util.*
import kotlin.coroutines.experimental.suspendCoroutine

suspend fun connect(host: String, port: Int): Connection {
    return suspendCoroutine<Connection> { continuation ->
        val asynchronousSocketChannel = AsynchronousSocketChannel.open()
        asynchronousSocketChannel.connect(InetSocketAddress(host, port), 1, object : CompletionHandler<Void, Int> {
            override fun failed(throwable: Throwable, attachment: Int?) {
                continuation.resumeWithException(throwable)
            }

            override fun completed(result: Void?, attachment: Int?) {
                val incomingDataProducer = produce<ByteArray>(CommonPool) {
                    while (true) {
                        val out = ByteArray(256)
                        val buffer = ByteBuffer.wrap(out)

                        val incomingData = suspendCoroutine<ByteArray> { continuation ->
                            asynchronousSocketChannel.read(buffer, Unit, object : CompletionHandler<Int, Unit> {
                                override fun completed(readaBytes: Int, attachment: Unit): Unit = run {
                                    if (readaBytes < 0) {
                                        continuation.resumeWithException(RuntimeException("EOF"))
                                    } else {
                                        continuation.resume(Arrays.copyOf(out, readaBytes))
                                    }
                                }

                                override fun failed(exc: Throwable, attachment: Unit): Unit = run {
                                    continuation.resumeWithException(exc)
                                }
                            })
                        }
                        send(incomingData)
                    }
                }

                val outgoingDataChannel = Channel<ByteArray>()
                launch(CommonPool) {
                    for (data in outgoingDataChannel) {
                        asynchronousSocketChannel.write(ByteBuffer.wrap(data))
                    }
                }
                continuation.resume(Connection(incomingDataProducer, outgoingDataChannel, asynchronousSocketChannel))
            }
        })
    }
}

data class Connection(val incomingDataProducer: ReceiveChannel<ByteArray>, val outgoingDataChannel: SendChannel<ByteArray>,
                      private val asynchronousSocketChannel: AsynchronousSocketChannel) {

    private val logger: Logger = LoggerFactory.getLogger(this::class.simpleName)

    private val outgoingBuffer = ByteBuffer.allocate(1024)
    private val incomingBuffer = ByteBuffer.allocate(1024)
    private var startOfFirstUnprocessedByte = 0
    private var indexOfLastIncomingByte = 0

    init {
        outgoingBuffer.order(ByteOrder.LITTLE_ENDIAN)
        incomingBuffer.order(ByteOrder.LITTLE_ENDIAN)
    }

    fun close() {
        asynchronousSocketChannel.close()
    }

    fun fill(packet: ToServer.Packet) {
        packet.write(outgoingBuffer)
        logger.trace("[SendBuffer] ${outgoingBuffer.position()}/${outgoingBuffer.capacity()}")
    }

    suspend fun send() {
        outgoingBuffer.flip()
        val sendingBytes = ByteArray(outgoingBuffer.remaining())
        if (logger.isTraceEnabled) {
            logger.trace("Sending bytes: \n" + toHexDump(outgoingBuffer.duplicate(), outgoingBuffer.position(), outgoingBuffer.limit()))
        }
        outgoingBuffer.get(sendingBytes, outgoingBuffer.position(), outgoingBuffer.limit())
        outgoingDataChannel.send(sendingBytes)
        outgoingBuffer.clear()
    }

    private fun readIncomingBytes(dstBuffer: ByteBuffer, receiveChannel: ReceiveChannel<ByteArray>) {
        val receivedBytes = receiveChannel.poll()
        if (receivedBytes != null) {
            val buf = ByteBuffer.wrap(receivedBytes)
            require(dstBuffer.remaining() >= receivedBytes.size)
            buf.order(ByteOrder.LITTLE_ENDIAN)
            if (logger.isTraceEnabled) {
                logger.trace("Incoming data: {}", toHexDump(buf.duplicate(), 0, receivedBytes.size))
            }
            dstBuffer.put(buf)
        }
    }

    suspend fun readInt(): Int {
        while (!readBytesAndUpdateBufferPos()) {
            delay(10)
        }
        val int = incomingBuffer.getInt()
        startOfFirstUnprocessedByte += 4
        return int
    }

    suspend fun readPackets(): List<FromServer.Packet> {
        return suspendCoroutine<List<FromServer.Packet>> { continuation ->
            launch(CommonPool) {
                val incomingPackets = arrayListOf<FromServer.Packet>()
                try {
                    var run = true
                    while (run) {
                        readBytesAndUpdateBufferPos()
                        if (startOfFirstUnprocessedByte == indexOfLastIncomingByte) {
                            delay(10)
                            continue
                        }
                        val beginOfPacketPos = incomingBuffer.position()
                        val header = incomingBuffer.short.toInt()
                        val packetDescr = FromServer.PACKETS.firstOrNull {
                            header == it.first
                        }
                        if (packetDescr != null) {
                            val incomingPacket = tryReadPacket(packetDescr, beginOfPacketPos)
                            if (incomingPacket != null) {
                                incomingPackets.add(incomingPacket)
                            } else {
                                run = false
                                continuation.resume(incomingPackets)
                            }
                        } else {
                            incomingBuffer.position(startOfFirstUnprocessedByte)
                            val remainingBytesAsString = toHexDump(incomingBuffer.duplicate(), beginOfPacketPos, indexOfLastIncomingByte)
                            logger.error("Unknown packet\n$remainingBytesAsString")
                            logger.error("Packets in buffer: ${incomingPackets.joinToString("\n    ")}")
                            run = false
                            continuation.resumeWithException(IllegalStateException("Unknown packet: " + remainingBytesAsString))
                        }
                        if (startOfFirstUnprocessedByte > indexOfLastIncomingByte) {
                            run = false
                            continuation.resumeWithException(IllegalStateException("More bytes were processed than received: $startOfFirstUnprocessedByte > $indexOfLastIncomingByte"))
                        } else if (startOfFirstUnprocessedByte == indexOfLastIncomingByte) {
                            run = false
                            incomingBuffer.clear()
                            startOfFirstUnprocessedByte = 0
                            indexOfLastIncomingByte = 0
                            continuation.resume(incomingPackets)
                        }
                        delay(10)
                    }
                } catch (e: BufferUnderflowException) {
                    continuation.resume(incomingPackets)
                } catch (e: Exception) {
                    e.printStackTrace()
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    public fun getHexDump(): String = toHexDump(incomingBuffer.duplicate(), startOfFirstUnprocessedByte, indexOfLastIncomingByte)

    private fun toHexDump(byteBuffer: ByteBuffer, startPos: Int, endPos: Int): String {
        byteBuffer.position(startPos)
        byteBuffer.limit(endPos)
        val remaining = byteBuffer.remaining()
        val byteArray = ByteArray(remaining)
        byteBuffer.get(byteArray, 0, endPos - startPos)
        FileUtils.writeByteArrayToFile(File("dump.hex"), byteArray)
        val sb = StringBuilder()
        byteArray.forEachIndexed { index, byte ->
            if (index % 8 == 0 && index > 0) {
                sb.append(" ")
            }
            if (index % 16 == 0 && index > 0) {
                appendAsciiDumpRow(sb, byteArray.drop((index / 16 - 1) * 16).take(16))
                sb.append("\n")
            }
            sb.append((byte.toInt().and(0xFF)).toString(16).padStart(2, '0')).append(' ')
        }
        appendAsciiDumpRow(sb, byteArray.drop(((byteArray.size - 1) / 16) * 16).take(16))
        return sb.toString()
    }

    private fun appendAsciiDumpRow(sb: StringBuilder, bytes: List<Byte>) {
        sb.append(" ".repeat(2 + (16 - bytes.size) * 2))
        bytes.map {
            if (it in 32..126) it.toChar() else '.'
        }.joinTo(sb, " ")
    }

    private suspend fun readBytesAndUpdateBufferPos(): Boolean {
        val posBeforeReceiving = incomingBuffer.position()
        incomingBuffer.position(indexOfLastIncomingByte)
        readIncomingBytes(incomingBuffer, incomingDataProducer)
        indexOfLastIncomingByte = incomingBuffer.position()
        incomingBuffer.position(startOfFirstUnprocessedByte)
        return indexOfLastIncomingByte > posBeforeReceiving
    }

    private fun tryReadPacket(packerDescr: Triple<Int, Int, (FromServer.PacketFieldReader) -> FromServer.Packet?>, beginOfPacketPos: Int): FromServer.Packet? {
        val hasStaticSize = packerDescr.second != 0
        val hasEnoughBytesForThisPacket = hasStaticSize && indexOfLastIncomingByte >= packerDescr.second
        return if (!hasStaticSize || hasEnoughBytesForThisPacket) {
            val incomingPacket = FromServer.read(incomingBuffer, packerDescr.third)
            if (incomingPacket != null) {
                if (hasStaticSize) {
                    val currentPosition = incomingBuffer.position()
                    val newPosition = beginOfPacketPos + packerDescr.second
                    if (currentPosition > newPosition) {
                        error("$incomingPacket: more bytes were read ($currentPosition) than the static size would suggest ($newPosition)")
                    }
                    incomingBuffer.position(newPosition)
                } else {
                    logger.trace("Incoming Packet [$incomingPacket] - [${incomingBuffer.position() - beginOfPacketPos} bytes]")
                }
                startOfFirstUnprocessedByte = incomingBuffer.position()
                incomingPacket
            } else {
                null
            }
        } else {
            null
        }
    }
}


