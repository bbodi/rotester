package hu.nevermind.rotester

import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.delay
import java.util.*

class GmActor(private val username: String, private val password: String) {

    val actor = actor<IncomingPacketSubscriberMessage>(CommonPool) {
        var mapName: String = ""
        var pos: Pos = Pos(0, 0, 0)
        try {
            val loginResponse = login(username, password)

            val charServerIp = toIpString(loginResponse.charServerDatas[0].ip)

            val (mapData, charName) = loginToMapServer(charServerIp, loginResponse)
            mapName = mapData.mapName
            Session(connect(toIpString(mapData.ip), mapData.port)).use { mapSession ->
                println("connected to map server: ${toIpString(mapData.ip)}, ${mapData.port}")
                val packetArrivalVerifier = PacketArrivalVerifier()
                mapSession.subscribeForPackerArrival(packetArrivalVerifier.actor.channel)
                mapSession.asyncStartProcessingIncomingPackets()
                mapSession.send(ToServer.ConnectToMapServer(
                        accountId = loginResponse.accountId,
                        charId = mapData.charId,
                        loginId = loginResponse.loginId,
                        clientTick = Date().time.toInt(),
                        sex = loginResponse.sex
                ))
                var blId = 0
                packetArrivalVerifier.inCaseOf(FromServer.ConnectToMapServerResponse::class, 5000) { p ->
                    blId = p.blockListId
                    println("blId: $blId")
                }
                packetArrivalVerifier.inCaseOf(FromServer.MapAuthOk::class, 5000) { p ->
                    pos = p.pos
                }
                val changeMapPacket = packetArrivalVerifier.waitForPacket(FromServer.ChangeMap::class, 5000)
                mapSession.send(ToServer.LoadEndAck())
                packetArrivalVerifier.waitForPacket(FromServer.EquipCheckbox::class, 5000)
                // changeMapPacket.pos.x, changeMapPacket.pos.y-1
                // 84 lefele van a 85hoy kepest$
                var (x, y) = changeMapPacket.x to changeMapPacket.y
                var yDir = 1
                (0 until 4).forEach { walkCount ->
                    mapSession.send(ToServer.WalkTo(x, y + yDir))
                    packetArrivalVerifier.waitForPacket(FromServer.WalkOk::class, 5000)
                    y += yDir
                    yDir *= -1
                    println("$walkCount ok ;)")
                    delay(1000)
                }
                packetArrivalVerifier.cleanPacketHistory()
                packetArrivalVerifier.waitForPacket(FromServer.NotifyPlayerChat::class, 5000)
                mapSession.send(ToServer.Chat("$charName : Hello World!"))
                mapSession.send(ToServer.Chat("$charName : ($mapName): ${pos.x}, ${pos.y}"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            throw e;
        }
    }

    private suspend fun loginToMapServer(charServerIp: String, loginResponse: FromServer.LoginSucceedResponsePacket): Pair<FromServer.MapServerData, String> {
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
//                    delay(10000000000)
            packetArrivalVerifier.waitForPacket(FromServer.CharWindow::class, 5000)
            val characterList = packetArrivalVerifier.waitForPacket(FromServer.CharacterList::class, 5000)
            val pincodeState = packetArrivalVerifier.waitForPacket(FromServer.PincodeState::class, 5000)
            if (pincodeState.state != 0) {
                error("pincode is enabled! Please disable it in conf/char_athena.conf")
            }
            charSession.send(ToServer.SelectChar(0))
            val mapData = packetArrivalVerifier.waitForPacket(FromServer.MapServerData::class, 5000)
            println("$mapData")
            return mapData to characterList.charInfos[0].name
        }
    }

    private suspend fun login(username: String, password: String): FromServer.LoginSucceedResponsePacket {
        val loginSession = Session(connect("localhost", 6900))
        loginSession.asyncStartProcessingIncomingPackets()
        val packetArrivalVerifier = PacketArrivalVerifier()
        loginSession.subscribeForPackerArrival(packetArrivalVerifier.actor.channel)
        loginSession.send(ToServer.LoginPacket(username, password))
        packetArrivalVerifier.inCaseOf(FromServer.CharSelectErrorResponse::class) { p ->
            println("Login error: ${p.reason}")
        }
        val loginResponse = packetArrivalVerifier.waitForPacket(FromServer.LoginSucceedResponsePacket::class, 5000)
        println("response: " + loginResponse)
        return loginResponse
    }

}