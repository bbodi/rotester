package hu.nevermind.rotester

import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.delay
import java.util.*


class PlayerActor(private val username: String, private val password: String, gmActorChannel: SendChannel<GmActorMessage>) {


    val actor = actor<GmActorMessage>(CommonPool) {
        var mapName: String = ""
        var pos: Pos = Pos(0, 0, 0)

        try {
            val loginResponse = login(username, password)

            val charServerIp = toIpString(loginResponse.charServerDatas[0].ip)

            val (mapData, charName) = connectToCharServerAndSelectChar(charServerIp, loginResponse, 0)
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
                gmActorChannel.send(SpawnMonster("pupa", mapName.dropLast(4), pos.x, pos.y, 1))
                // changeMapPacket.pos.x, changeMapPacket.pos.y-1
                // 84 lefele van a 85hoy kepest$
                var (x, y) = changeMapPacket.x to changeMapPacket.y
                var yDir = -1
                (0 until 1).forEach { walkCount ->
                    mapSession.send(ToServer.WalkTo(x, y + yDir))
                    packetArrivalVerifier.waitForPacket(FromServer.WalkOk::class, 5000)
                    y += yDir
                    yDir *= -1
                    println("$walkCount ok ;)")
                    delay(1000)
                }
                packetArrivalVerifier.cleanPacketHistory()
                mapSession.send(ToServer.Chat("$charName : Hello World!"))
                mapSession.send(ToServer.Chat("$charName : ($mapName): ${pos.x}, ${pos.y}"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            throw e;
        }
    }
}