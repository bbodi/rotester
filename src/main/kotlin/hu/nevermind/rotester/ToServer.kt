package hu.nevermind.rotester

import java.nio.ByteBuffer


object ToServer {
    abstract class Packet(val id: Int) {

        abstract fun PacketFieldWriter.buildPacket()

        fun write(bb: ByteBuffer) {
            val pb = PacketFieldWriter(bb)
            pb.byte2(id)
            pb.buildPacket()
        }
    }


    class LoginPacket(val username: String, val password: String) : Packet(0x64) {

        override fun PacketFieldWriter.buildPacket() {
            byte4(55)
            string(username, 24)
            string(password, 33)
            byte1(1)
        }

    }

    class SelectChar(val slot: Int) : Packet(0x66) {

        override fun PacketFieldWriter.buildPacket() {
            byte1(slot)
        }

    }

    class PacketFieldWriter(val bb: ByteBuffer) {
        fun byte1(value: Int) {
            bb.put(value.toByte())
        }

        fun byte2(value: Int) {
            bb.putShort(value.toShort())
        }

        fun byte4(value: Int) {
            bb.putInt(value)
        }

        fun string(value: String, maxLen: Int = value.length) {
            bb.put(value.take(maxLen).padEnd(maxLen, 0.toChar()).toByteArray(Charsets.US_ASCII))
        }

        fun mapPosition(x: Int, y: Int) {
            // 00xx_xxxx
            // xx00_yyyy
            // yyyy_dddd
            bb.put(x.ushr(2).and(0B11_1111).toByte())
            bb.put(x.shl(6).and(0B1100_0000).or(y.ushr(4).and(0B0000_1111)).toByte())
            bb.put(y.shl(4).and(0B1111_0000).toByte())
        }
    }

    class CharServerInit(val accountId: Int, val loginId: Int, val userLevel: Int, val sex: Int) : Packet(0x65) {
        override fun PacketFieldWriter.buildPacket() {
            byte4(accountId)
            byte4(loginId)
            byte4(userLevel)
            byte2(0)
            byte1(sex)
        }
    }

    class ConnectToMapServer(val accountId: Int, val charId: Int, val loginId: Int, val clientTick: Int, val sex: Int) : Packet(0x72) {
        override fun PacketFieldWriter.buildPacket() {
            byte4(accountId)
            byte4(charId)
            byte4(loginId)
            byte4(clientTick)
            byte1(sex)
        }
    }

    class LoadEndAck() : Packet(0x7d) {
        override fun PacketFieldWriter.buildPacket() {

        }
    }

    class WalkTo(val x: Int, val y: Int) : Packet(0x85) {
        override fun PacketFieldWriter.buildPacket() {
            mapPosition(x, y)
        }
    }

    class Chat(val text: String) : Packet(0x8c) {
        override fun PacketFieldWriter.buildPacket() {
            byte2(2 + 2 + text.length + 1)
            string(text + 0.toChar())
        }
    }
}