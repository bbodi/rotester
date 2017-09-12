package hu.nevermind.rotester

import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.delay
import java.util.*

enum class TestLockGranularity {
    Global,
    Map
}

data class TestCase(val scenarioDescription: String,
                    val lockGranularity: TestLockGranularity = TestLockGranularity.Map,
                    val testLogic: suspend (TestDirectorCommunicator) -> Unit) {

}


data class ClientState(val mapName: String, val pos: Pos, val charName: String)

class PlayerActor(private val username: String,
                  private val password: String,
                  gmActorChannel: SendChannel<GmActorMessage>?,
                  logic: suspend (ClientState, PacketArrivalVerifier, Session) -> Unit
) {

    val actor = actor<GmActorMessage>(CommonPool) {
        var clientState: ClientState = ClientState("", Pos(0, 0, 0), "")

        try {
            val loginResponse = login(username, password)

            val charServerIp = toIpString(loginResponse.charServerDatas[0].ip)

            val (mapData, charName) = connectToCharServerAndSelectChar(username, charServerIp, loginResponse, 0)
            clientState = clientState.copy(mapName = mapData.mapName)
            Session("PlayerActor - mapSession", connect(charName, toIpString(mapData.ip), mapData.port)).use { mapSession ->
                println("connected to map server: ${toIpString(mapData.ip)}, ${mapData.port}")
                val packetArrivalVerifier = PacketArrivalVerifier(charName)
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
                    clientState = clientState.copy(pos = p.pos)
                }
                val changeMapPacket = packetArrivalVerifier.waitForPacket(FromServer.ChangeMap::class, 5000)
                mapSession.send(ToServer.LoadEndAck())
                packetArrivalVerifier.waitForPacket(FromServer.EquipCheckbox::class, 5000)
//                gmActorChannel.send(SpawnMonster("pupa", mapName.dropLast(4), pos.x, pos.y, 1))
//                gmActorChannel?.send(TakeMeTo(charName, "prontera", 100, 100))
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
                mapSession.send(ToServer.Chat("$charName : (${clientState.mapName}): ${clientState.pos.x}, ${clientState.pos.y}"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            throw e;
        }
    }
}