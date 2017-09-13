package hu.nevermind.rotester

import hu.nevermind.rotester.test.WalkingTest
import hu.nevermind.rotester.test.WarpCommandTest
import hu.nevermind.rotester.test.WhisperTest
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

val logger = LoggerFactory.getLogger("global")

fun <T> assertEquals(expected: T, actual: T) {
    require(expected == actual) { "expected: $expected, but was $actual" }
}

fun main(args: Array<String>) = runBlocking {

    val jobs = List(1) { index ->
        launch(CommonPool) {
            val testDirector = TestDirector(
                    listOf(GmActor("gmgm", "gmgm")),
//                    emptyList(),
                    listOf(TestDirector.MapPos("prontera", 100, 100))
            )
            (1..6).forEach {
                testDirector.actor.send(LoginToMap("bot$it", "bot$it"))
            }

            WhisperTest.run(testDirector)
            WarpCommandTest.run(testDirector)
            WalkingTest.run(testDirector)

            testDirector.actor.join()
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

suspend fun connectToCharServerAndSelectChar(username: String, charServerIp: String, loginResponse: FromServer.LoginSucceedResponsePacket, charIndex: Int): Pair<FromServer.MapServerData, String> {
    Session("char[$username]", connect("char[$username]", charServerIp, loginResponse.charServerDatas[0].port)).use { charSession ->
        val packetArrivalVerifier = PacketArrivalVerifier("char[$username]", charSession)
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
        packetArrivalVerifier.cleanPacketHistory()
        val selectedCharName = if (characterList.charInfos.isEmpty()) {
            logger.info("There is no character for this account, creating one...")
            charSession.send(ToServer.CreateChar(username.capitalize(),
                    //                    str = 1,
//                    agi = 1,
//                    vit = 1,
//                    int = 1,
//                    dex = 1,
//                    luk = 1,
                    slot = 0,
                    hairColor = 0,
                    hairStyle = 0,
                    startingJobId = 0,
                    sex = 0)
            )
            charSession.send(ToServer.SelectChar(charIndex))
            // TODO: these tasks should be removed after timeout from packetArrivalVerifier
            packetArrivalVerifier.inCaseOf(FromServer.CharCreationRejected::class, 5000) { p ->
                logger.info("Char creation error(${p.reason})")
            }
            val charCreationResponse = packetArrivalVerifier.waitForPacket(FromServer.CharCreationSuccessful::class, 5000)
            logger.info("Char created ${charCreationResponse.charInfo.name}")
            charCreationResponse.charInfo.name
        } else {
            characterList.charInfos[0].name
        }
        charSession.send(ToServer.SelectChar(charIndex))
        val mapData = packetArrivalVerifier.waitForPacket(FromServer.MapServerData::class, 5000)
        charSession.close()
        logger.debug("$mapData")
        return mapData to selectedCharName
    }
}

suspend fun login(username: String, password: String): FromServer.LoginSucceedResponsePacket {
    val loginSession = Session("login[$username]", connect("login[$username]", "localhost", 6900))
    loginSession.asyncStartProcessingIncomingPackets()
    val packetArrivalVerifier = PacketArrivalVerifier("login[$username]", loginSession)
    loginSession.send(ToServer.LoginPacket(username, password))
    val packet = packetArrivalVerifier.waitForPacket(FromServer.Packet::class, 5000)
    return when (packet) {
        is FromServer.LoginFailResponsePacket -> {
            logger.info("Login error: ${packet.reason}. Account $username does not exist")
            logger.info("creating one...")
            loginSession.send(ToServer.LoginPacket("${username}_M", password))
            val loginResponse = packetArrivalVerifier.waitForPacket(FromServer.LoginSucceedResponsePacket::class, 5000)
            loginSession.close()
            loginResponse
        }
        is FromServer.LoginSucceedResponsePacket -> {
            loginSession.close()
            packet
        }
        else -> error(packet)
    }
}