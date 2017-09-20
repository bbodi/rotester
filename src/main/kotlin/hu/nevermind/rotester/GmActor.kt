package hu.nevermind.rotester

import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*

sealed class GmActorMessage()
class SendHeartbeat : GmActorMessage()
data class SpawnMonster(val monsterName: String, val dstMapName: String, val dstX: Int, val dstY: Int, val monsterCount: Int = 1) : GmActorMessage()
data class TakeMeTo(val charName: String,
                    val dstMapName: String,
                    val dstX: Int,
                    val dstY: Int) : GmActorMessage()

class GmActor(private val username: String, private val password: String) {

    private val logger: Logger = LoggerFactory.getLogger(this::class.simpleName)

// 2017-09-20 07:29:00 [ForkJoinPool.commonPool-worker-2 @coroutine#126] TRACE Connection - [gmgm] Incoming Packet [NotifyPlayerChat(msg=Invisible: On)] - [18 bytes]
    val actor = actor<GmActorMessage>(CommonPool) {
        var mapName: String = ""
        var pos: Pos = Pos(0, 0, 0)
        try {
            val loginResponse = login(username, password)

            val charServerIp = toIpString(loginResponse.charServerDatas[0].ip)

            val (mapData, charName) = connectToCharServerAndSelectChar(username, charServerIp, loginResponse, 0)
            mapName = mapData.mapName
            Session("GM - mapSession", connect(charName, toIpString(mapData.ip), mapData.port)).use { mapSession ->
                logger.info("$username connected to map server: ${toIpString(mapData.ip)}, ${mapData.port}")
                val packetArrivalVerifier = PacketArrivalVerifier(charName, mapSession)
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
                    logger.debug("$username: blId: $blId")
                }
                pos = packetArrivalVerifier.waitForPacket(FromServer.MapAuthOk::class, 5000).pos
                mapSession.send(ToServer.LoadEndAck())
                val changeMapPacket = packetArrivalVerifier.waitForPacket(FromServer.ChangeMap::class, 5000)
                packetArrivalVerifier.waitForPacket(FromServer.EquipCheckbox::class, 5000)
                launch(CommonPool) {
                    while (true) {
                        this@actor.channel.send(SendHeartbeat())
                        delay(5000)
                    }
                }

//                mapSession.send(ToServer.Chat("$charName : ($mapName): ${pos.x}, ${pos.y}"))
                packetArrivalVerifier.cleanPacketHistory()
                for (msg in channel) {
                    logger.debug("{}: incoming command: {}", username, msg)
                    when (msg) {
                        is SpawnMonster -> {
                            mapSession.send(ToServer.Chat("$charName : @warp ${msg.dstMapName} ${msg.dstX} ${msg.dstY}"))
                            packetArrivalVerifier.waitForPacket(FromServer.NotifyPlayerChat::class, 5000)
                            mapSession.send(ToServer.Chat("$charName : @spawn ${msg.monsterName} ${msg.monsterCount}"))
                            packetArrivalVerifier.waitForPacket(FromServer.NotifyPlayerChat::class, 5000)
                            mapSession.send(ToServer.Chat("$charName : asd"))
                            packetArrivalVerifier.waitForPacket(FromServer.NotifyPlayerChat::class, 5000)
                        }
                        is SendHeartbeat -> {
                            val start = Date().time
                            mapSession.send(ToServer.TickSend())
                            packetArrivalVerifier.waitForPacket(FromServer.NotifyTime::class, 5000)
                            val end = Date().time
                            logger.debug("$username ping: ${end - start} ms")
                        }
                        is TakeMeTo -> {
                            mapSession.send(ToServer.Chat("$charName : @where ${msg.charName}"))
                            val pos = packetArrivalVerifier.waitForPacket(FromServer.NotifyPlayerChat::class, 5000) { packet ->
                                !packet.msg.contains(':')
                            }.msg.split(" ")
                            if (pos[1] == msg.dstMapName && pos[2].toInt() == msg.dstX && pos[3].toInt() == msg.dstY) {
                                mapSession.send(ToServer.Whisper(msg.charName, "Done"))
                                val whisperResult = packetArrivalVerifier.waitForPacket(FromServer.WhisperResultPacket::class, 5000)
                                require(whisperResult.result == FromServer.WhisperResult.Success)
                            } else {
                                mapSession.send(ToServer.Chat("$charName : @warp ${msg.dstMapName} ${msg.dstX} ${msg.dstY}"))
                                packetArrivalVerifier.waitForPacket(FromServer.NotifyPlayerChat::class, 5000) { packet ->
                                    packet.msg == "Warped."
                                }
                                mapSession.send(ToServer.LoadEndAck())
                                mapSession.send(ToServer.Chat("$charName : @recall ${msg.charName}"))
                                packetArrivalVerifier.waitForPacket(FromServer.NotifyPlayerChat::class, 5000) { packet ->
                                    packet.msg == "${msg.charName} recalled!"
                                }
                                mapSession.send(ToServer.Whisper(msg.charName, "Done"))
                                val whisperResult = packetArrivalVerifier.waitForPacket(FromServer.WhisperResultPacket::class, 5000)
                                require(whisperResult.result == FromServer.WhisperResult.Success)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("error", e)
            throw e;
        }
        logger.info("$username END")
    }

}