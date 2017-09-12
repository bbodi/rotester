import hu.nevermind.rotester.FromServer
import hu.nevermind.rotester.FromServer.WalkOk.Companion.reader
import hu.nevermind.rotester.ToServer
import hu.nevermind.rotester.assertEquals
import java.nio.ByteBuffer

fun main(args: Array<String>) {
    PacketReaderTest().main()
}

class PacketReaderTest {


    fun main() {
        val pos = readPacket("194640") { // 6303 WalkTo
            mapPosition()
        }
        assertEquals(101, pos.x)
        assertEquals(100, pos.y)
        // 8700
        val walkOk = readPacket("f1200400190641946488", FromServer.WalkOk.reader)!!
        println(walkOk)
        assertEquals(100, walkOk.posChange.srcX)
        assertEquals(100, walkOk.posChange.srcY)
        assertEquals(101, walkOk.posChange.dstX)
        assertEquals(100, walkOk.posChange.dstY)
        assertEquals(8, walkOk.posChange.sx)
        assertEquals(8, walkOk.posChange.sy)
    }

    fun toByteArray(str: String): ByteArray {
        val cleanedStr = str.replace(" ", "")
        return (0..cleanedStr.length - 1 step 2).map { i ->
            cleanedStr.substring(i, i + 2)
        }.map { it.toInt(16).toByte() }.toByteArray()

    }

    fun <T> readPacket(str: String, packetReader: FromServer.PacketFieldReader.() -> T): T {
        val byteArray = toByteArray(str)
        val pb = FromServer.PacketFieldReader(ByteBuffer.wrap(byteArray))
        return pb.packetReader()
    }

}