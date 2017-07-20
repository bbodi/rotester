package hu.nevermind.rotester

import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

val logger = LoggerFactory.getLogger("global")

fun main(args: Array<String>) = runBlocking {

    //    val socketChannel = SocketChannel.open()
    val jobs = List(1) { index ->
        launch(CommonPool) {
            val gmActor = GmActor("gmgm", "gmgm")
//            val player = PlayerActor("bot1", "bot1", gmActor.actor.channel)
            gmActor.actor.join()
        }
    }
    jobs.forEach { it.join() }
}


fun toIpString(ip: Int): String {
    val ipBuf = ByteBuffer.allocate(4)
    ipBuf.order(ByteOrder.LITTLE_ENDIAN)
    ipBuf.putInt(ip)
    ipBuf.flip()
    val charServerIp = ipBuf.array().joinToString(".")
    return charServerIp
}

suspend fun connectToCharServerAndSelectChar(charServerIp: String, loginResponse: FromServer.LoginSucceedResponsePacket, charIndex: Int): Pair<FromServer.MapServerData, String> {
    Session(connect(charServerIp, loginResponse.charServerDatas[0].port)).use { charSession ->
        val packetArrivalVerifier = PacketArrivalVerifier()
        charSession.subscribeForPackerArrival(packetArrivalVerifier.actor.channel)
        charSession.send(ToServer.CharServerInit(
                accountId = loginResponse.accountId,
                loginId = loginResponse.loginId,
                userLevel = loginResponse.userLevel,
                sex = loginResponse.sex
        ))
        val newAuthId = charSession.connection.readInt()
        charSession.asyncStartProcessingIncomingPackets()
        packetArrivalVerifier.waitForPacket(FromServer.CharWindow::class, 5000)
        val characterList = packetArrivalVerifier.waitForPacket(FromServer.CharacterList::class, 5000)
        val pincodeState = packetArrivalVerifier.waitForPacket(FromServer.PincodeState::class, 5000)
        if (pincodeState.state != 0) {
            error("pincode is enabled! Please disable it in conf/char_athena.conf")
        }
        charSession.send(ToServer.SelectChar(charIndex))
        val mapData = packetArrivalVerifier.waitForPacket(FromServer.MapServerData::class, 5000)
        charSession.close()
        logger.debug("$mapData")
        return mapData to characterList.charInfos[0].name
    }
}

suspend fun login(username: String, password: String): FromServer.LoginSucceedResponsePacket {
    val loginSession = Session(connect("localhost", 6900))
    loginSession.asyncStartProcessingIncomingPackets()
    val packetArrivalVerifier = PacketArrivalVerifier()
    loginSession.subscribeForPackerArrival(packetArrivalVerifier.actor.channel)
    loginSession.send(ToServer.LoginPacket(username, password))
    packetArrivalVerifier.inCaseOf(FromServer.CharSelectErrorResponse::class) { p ->
        logger.debug("Login error: ${p.reason}")
    }
    val loginResponse = packetArrivalVerifier.waitForPacket(FromServer.LoginSucceedResponsePacket::class, 5000)
    loginSession.close()
    return loginResponse
}