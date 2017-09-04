package hu.nevermind.rotester

import kotlinx.coroutines.experimental.CancellationException
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*

sealed class TestDirectorMessage()
data class StartTest(val testCase: TestCase) : TestDirectorMessage()
data class MyGmHasFinishedHerJob(val testCase: TestCase) : TestDirectorMessage()
data class WarpChar(val sourceTestCase: TestCase,
                    val charName: String,
                    val dstMapName: String?,
                    val dstX: Int = 0,
                    val dstY: Int = 0) : TestDirectorMessage()

data class TestSucceeded(val testCase: TestCase) : TestDirectorMessage()
data class RequestRoCLient(val testCase: TestCase, val answerChannel: Channel<ROClient>) : TestDirectorMessage()
data class LockMap(val sourceTestCase: TestCase, val mapName: String) : TestDirectorMessage()
data class TestFailed(val sourceTestCase: TestCase, val failureReason: FailureReason) : TestDirectorMessage()
data class LoginToMap(val username: String, val password: String) : TestDirectorMessage()
class SendHeartbeat2 : TestDirectorMessage()

sealed class FailureReason()
data class TestCaseError(val e: Exception) : FailureReason()
class NoFreeMap : FailureReason()
class NoFreeGM : FailureReason()
data class ExpectationFails(val reason: String) : FailureReason()

data class TestMetadata(val testCase: TestCase, val tryCount: Int, val failureReasons: List<FailureReason>)

data class ROClient(val clientState: ClientState, val mapSession: Session, val packetArrivalVerifier: PacketArrivalVerifier)

class TestDirector(val gmActors: List<GmActor>, val emptyMaps: List<MapPos>) {

    data class MapPos(val mapName: String, val x: Int, val y: Int)

    private val logger: Logger = LoggerFactory.getLogger(this::class.simpleName)
    private val tests: List<TestMetadata> = emptyList()


    val actor = actor<TestDirectorMessage>(CommonPool) {
        launch(CommonPool) {
            while (true) { // saját magának
                this@actor.channel.send(SendHeartbeat2())
                delay(5000)
            }
        }
        val freeGMList = gmActors.toMutableList()
        val gmsOccupiedByTestCases = hashMapOf<TestCase, GmActor>()
        val roCLientsOccupiedByTestCases = hashMapOf<TestCase, MutableList<ROClient>>()
        var freeEmptyMapList = emptyMaps.toMutableList()
        var maxRoClient = 0
        val freeROClients = arrayListOf<ROClient>()
        val runningTests = arrayListOf<String>()
        actorChannelLoop@ for (msg in channel) {
            try {
                when (msg) {
                    is SendHeartbeat2 -> {
                        freeROClients.forEach { roClient ->
                            val start = Date().time
                            roClient.mapSession.send(ToServer.TickSend())
                        }
                    }
                    is LoginToMap -> {
                        var mapName: String = ""
                        var pos: Pos = Pos(0, 0, 0)
                        try {
                            val loginResponse = login(msg.username, msg.password)

                            val charServerIp = toIpString(loginResponse.charServerDatas[0].ip)

                            val (mapData, charName) = connectToCharServerAndSelectChar(charServerIp, loginResponse, 0)
                            mapName = mapData.mapName

                            val mapSession = Session("roClient - mapSession", connect(toIpString(mapData.ip), mapData.port))
                            logger.debug("${msg.username} connected to map server: ${toIpString(mapData.ip)}, ${mapData.port}")
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
                                logger.debug("${msg.username}: blId: $blId")
                            }
                            pos = packetArrivalVerifier.waitForPacket(FromServer.MapAuthOk::class, 5000).pos
                            mapSession.send(ToServer.LoadEndAck())
                            val changeMapPacket = packetArrivalVerifier.waitForPacket(FromServer.ChangeMap::class, 5000)
                            packetArrivalVerifier.waitForPacket(FromServer.EquipCheckbox::class, 5000)
                            val roClient = ROClient(ClientState(mapName, pos, charName), mapSession, packetArrivalVerifier)
                            freeROClients.add(roClient)
                            maxRoClient++
                        } catch (e: Exception) {
                            logger.error("error", e)
                            throw e;
                        }
                    }
                    is WarpChar -> {
                        if (freeGMList.isEmpty()) {
                            this@actor.channel.send(TestFailed(msg.sourceTestCase, NoFreeGM()))
                            continue@actorChannelLoop
                        }
                        val gm = freeGMList.removeAt(0)
                        gmsOccupiedByTestCases.put(msg.sourceTestCase, gm)
                        if (msg.dstMapName == null && freeEmptyMapList.isEmpty()) {
                            this@actor.channel.send(TestFailed(msg.sourceTestCase, NoFreeMap()))
                            continue@actorChannelLoop
                        }
                        val (dstMapname, dstX, dstY) = if (msg.dstMapName == null) {
                            val dstMap = freeEmptyMapList.removeAt(0)
                            Triple(dstMap.mapName, dstMap.x, dstMap.y)
                        } else {
                            Triple(msg.dstMapName, msg.dstX, msg.dstY)
                        }
                        gm.actor.send(TakeMeTo(msg.charName, dstMapname, dstX, dstY))
                    }
                    is TestFailed -> {
                        logger.error("${msg.sourceTestCase.scenarioDescription} FAILED: ${msg.failureReason}")
                        releaseResources(gmsOccupiedByTestCases, freeGMList, roCLientsOccupiedByTestCases, freeROClients, msg.sourceTestCase)
                        when (msg.failureReason) {
                            is NoFreeMap -> {
                                launch(CommonPool) {
                                    delay(3000)
                                    // try again this task
                                }
                            }
                            is NoFreeGM -> {
                                launch(CommonPool) {
                                    delay(3000)
                                    // try again this task
                                }
                            }
                        }
                    }
                    is StartTest -> {
                        if (freeROClients.isEmpty()) {
                            continue@actorChannelLoop
                        }
                        val roClient = freeROClients.removeAt(0)
                        roClient.packetArrivalVerifier.cleanPacketHistory()
                        roCLientsOccupiedByTestCases.put(msg.testCase, arrayListOf(roClient))
                        logger.debug("Starting test [${msg.testCase.scenarioDescription}], RoClients[${freeROClients.size}\\${maxRoClient}].")
                        launch(CommonPool) {
                            try {
                                msg.testCase.testLogic(
                                        roClient,
                                        TestDirectorCommunicator(msg.testCase, this@actor.channel)
                                )
                            } catch (e: Exception) {
                                logger.error(msg.testCase.scenarioDescription, e)
                                this@actor.channel.send(TestFailed(msg.testCase, TestCaseError(e)))
                            }
                        }
                    }
                    is RequestRoCLient -> {
                        logger.debug("RoClients[${freeROClients.size-1}\\${maxRoClient}] was requested by [${msg.testCase.scenarioDescription}].")
                        val roClient = freeROClients.removeAt(0)
                        roCLientsOccupiedByTestCases[msg.testCase]!!.add(roClient)
                        roClient.packetArrivalVerifier.cleanPacketHistory()
                        msg.answerChannel.send(roClient)
                    }
                    is TestSucceeded -> {
                        releaseResources(gmsOccupiedByTestCases, freeGMList, roCLientsOccupiedByTestCases, freeROClients, msg.testCase)
                        logger.info("Test SUCCESSFUL: ${msg.testCase.scenarioDescription}")
                    }
                    is MyGmHasFinishedHerJob -> {
                        val gm = gmsOccupiedByTestCases.remove(msg.testCase)
                        if (gm == null) {
                            logger.error("MyGmHasFinishedHerJob message arrived for ${msg.testCase.scenarioDescription} but no GM occupied by this test!")
                            continue@actorChannelLoop
                        }
                        freeGMList.add(gm)
                    }
                }
            } catch (e: Exception) {
                logger.error("Error while processing ${msg}", e)
            }
        }
    }

    private fun releaseResources(gmsOccupiedByTestCases: MutableMap<TestCase, GmActor>, freeGMList: MutableList<GmActor>, roCLientsOccupiedByTestCases: MutableMap<TestCase, MutableList<ROClient>>, freeROClients: MutableList<ROClient>, testCase: TestCase) {
        val gm = gmsOccupiedByTestCases.remove(testCase)
        if (gm != null) {
            freeGMList.add(gm)
        }
        roCLientsOccupiedByTestCases.remove(testCase)?.forEach {
            freeROClients.add(it)
        }
    }
}

class TestDirectorCommunicator(val testCase: TestCase,
                               val testDirectorChannel: Channel<TestDirectorMessage>) {
    suspend fun warpMeToEmptyMap(charName: String, mapSession: Session, packetArrivalVerifier: PacketArrivalVerifier) {
        warpMeTo(null, 0, 0, charName, mapSession, packetArrivalVerifier)
    }

    suspend fun warpMeTo(dstMapName: String?, dstX: Int, dstY: Int, charName: String, mapSession: Session, packetArrivalVerifier: PacketArrivalVerifier) {
        testDirectorChannel.send(WarpChar(testCase, charName, dstMapName, dstX, dstY))
        try {
            packetArrivalVerifier.waitForPacket(FromServer.NotifyPlayerWhisperChat::class, 5000) { packet ->
                packet.msg == "Done"
            }
            testDirectorChannel.send(MyGmHasFinishedHerJob(testCase))
            mapSession.send(ToServer.LoadEndAck())
        } catch (e: CancellationException) {
            logger.error("warpMeToEmptyMap", e)
            testDirectorChannel.send(TestFailed(testCase, NoFreeMap()))
        }
    }

    suspend fun requestRoClient(): ROClient {
        val answerChannel = Channel<ROClient>()
        testDirectorChannel.send(RequestRoCLient(testCase, answerChannel))
        return answerChannel.receive()
    }

    suspend fun succeed() {
        testDirectorChannel.send(TestSucceeded(testCase))
    }
}