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
data class RequestRoCLient(val testCase: TestCase, val answerChannel: Channel<ROClient?>) : TestDirectorMessage()
data class LockMap(val sourceTestCase: TestCase, val mapName: String) : TestDirectorMessage()
data class TestFailed(val sourceTestCase: TestCase, val failureReason: FailureReason) : TestDirectorMessage()
data class LoginToMap(val username: String, val password: String) : TestDirectorMessage()
class SendHeartbeat2 : TestDirectorMessage()

sealed class FailureReason()
data class TestCaseError(val e: Exception) : FailureReason() {
    override fun toString(): String {
        return "TestCaseError(${e.message})"
    }
}
class NoFreeMap : FailureReason()
class NoFreeGM : FailureReason()
class NoFreeRoClient : FailureReason()
data class ExpectationFails(val reason: String) : FailureReason()

data class TestMetadata(val testCase: TestCase, val tryCount: Int, val failureReasons: List<FailureReason>, val startTime: Long, val endTime: Long)

data class ROClient(val clientState: ClientState, val mapSession: Session, val packetArrivalVerifier: PacketArrivalVerifier)

class TestDirector(val gmActors: List<GmActor>, val emptyMaps: List<MapPos>) {

    data class MapPos(val mapName: String, val x: Int, val y: Int)

    private val logger: Logger = LoggerFactory.getLogger(this::class.simpleName)


    val actor = actor<TestDirectorMessage>(CommonPool) {
        launch(CommonPool) {
            try {
                while (true) { // to itself
                    delay(5000)
                    this@actor.channel.send(SendHeartbeat2())
                }
            } catch (e: Exception) {
                logger.error("TestDirector.sendHeartbeat", e)
            }
        }
        val freeGMList = gmActors.toMutableList()
        val gmsOccupiedByTestCases = hashMapOf<TestCase, GmActor>()
        val roClientsOccupiedByTestCases = hashMapOf<TestCase, MutableList<ROClient>>()
        val freeEmptyMapList = emptyMaps.toMutableList()
        val mapsOccupiedByTests = mutableMapOf<TestCase, String>()
        val runningTests: MutableList<TestMetadata> = arrayListOf()
        val tryItLaterTests: MutableList<TestMetadata> = arrayListOf()
        val finishedTests: MutableList<TestMetadata> = arrayListOf()
        var maxRoClient = 0
        val freeROClients = arrayListOf<ROClient>()

        actorChannelLoop@ for (msg in channel) {
            try {
                when (msg) {
                    is SendHeartbeat2 -> {
                        freeROClients.forEach { roClient ->
                            val start = Date().time
                            roClient.mapSession.send(ToServer.TickSend())
                        }
                        tryItLaterTests.forEach {
                            this@actor.channel.send(StartTest(it.testCase))
                        }
                    }
                    is LoginToMap -> {
                        var mapName: String = ""
                        var pos: Pos = Pos(0, 0, 0)
                        try {
                            val loginResponse = login(msg.username, msg.password)

                            val charServerIp = toIpString(loginResponse.charServerDatas[0].ip)

                            val (mapData, charName) = connectToCharServerAndSelectChar(msg.username, charServerIp, loginResponse, 0)
                            mapName = mapData.mapName

                            val mapSession = Session("roClient - mapSession", connect(charName, toIpString(mapData.ip), mapData.port))
                            logger.debug("${msg.username} connected to map server: ${toIpString(mapData.ip)}, ${mapData.port}")
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
                        if (mapsOccupiedByTests.containsValue(dstMapname)) {
                            this@actor.channel.send(TestFailed(msg.sourceTestCase, NoFreeMap()))
                            continue@actorChannelLoop
                        }
                        mapsOccupiedByTests.put(msg.sourceTestCase, dstMapname)
                        gm.actor.send(TakeMeTo(msg.charName, dstMapname, dstX, dstY))
                    }
                    is StartTest -> {
                        val alreadyStartedTestMetadata = tryItLaterTests.find { it.testCase == msg.testCase }
                        val testMetadata = if (alreadyStartedTestMetadata != null) {
                            measureTimeAndMoveMetadata(tryItLaterTests, runningTests, alreadyStartedTestMetadata.testCase)
                            logger.debug("Try again ${msg.testCase.scenarioDescription}")
                            alreadyStartedTestMetadata.copy(
                                    tryCount = alreadyStartedTestMetadata.tryCount + 1,
                                    startTime = System.currentTimeMillis()
                            )
                        } else {
                            TestMetadata(msg.testCase, 1, emptyList(), System.currentTimeMillis(), 0)
                        }
                        logger.debug("Starting test [${msg.testCase.scenarioDescription}], RoClients[${freeROClients.size}\\${maxRoClient}].")
                        runningTests.add(testMetadata)
                        launch(CommonPool) {
                            try {
                                msg.testCase.testLogic(
                                        TestDirectorCommunicator(msg.testCase, this@actor.channel)
                                )
                            } catch (e: Exception) {
                                logger.error(msg.testCase.scenarioDescription, e)
                                this@actor.channel.send(TestFailed(msg.testCase, TestCaseError(e)))
                            }
                        }
                    }
                    is RequestRoCLient -> {
                        if (freeROClients.isEmpty()) {
                            logger.warn("There is no free ROClient for ${msg.testCase.scenarioDescription}!")
                            measureTimeAndMoveMetadata(runningTests, tryItLaterTests, msg.testCase, NoFreeRoClient())
                            msg.answerChannel.send(null)
                        } else {
                            logger.debug("RoClients[${freeROClients.size - 1}\\${maxRoClient}] was requested by [${msg.testCase.scenarioDescription}].")
                            val roClient = freeROClients.removeAt(0)
                            roClientsOccupiedByTestCases.getOrPut(msg.testCase, { arrayListOf<ROClient>() }).add(roClient)
                            roClient.packetArrivalVerifier.cleanPacketHistory()
                            msg.answerChannel.send(roClient)
                        }
                    }
                    is TestSucceeded -> {
                        measureTimeAndMoveMetadata(runningTests, finishedTests, msg.testCase)
                        releaseGmsAndChars(gmsOccupiedByTestCases, freeGMList, roClientsOccupiedByTestCases, freeROClients, mapsOccupiedByTests, msg.testCase)
                        logger.info("Test SUCCESSFUL: ${msg.testCase.scenarioDescription}")

                        logState(freeROClients, maxRoClient, freeGMList, freeEmptyMapList, finishedTests, tryItLaterTests, runningTests)

                        if (runningTests.isEmpty() && tryItLaterTests.isEmpty()) {
                            return@actor
                        }
                    }
                    is TestFailed -> {
                        logger.error("${msg.sourceTestCase.scenarioDescription} FAILED: ${msg.failureReason}")
                        releaseGmsAndChars(gmsOccupiedByTestCases, freeGMList, roClientsOccupiedByTestCases, freeROClients, mapsOccupiedByTests, msg.sourceTestCase)
                        if (runningTests.any { msg.sourceTestCase == it.testCase }) {
                            measureTimeAndMoveMetadata(runningTests, finishedTests, msg.sourceTestCase, msg.failureReason)
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
                        }// else the test is already waiting for retrying
                        logState(freeROClients, maxRoClient, freeGMList, freeEmptyMapList, finishedTests, tryItLaterTests, runningTests)
                        if (runningTests.isEmpty() && tryItLaterTests.isEmpty()) {
                            return@actor
                        }
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
                return@actor
            }
        }
    }

    private fun logState(freeROClients: ArrayList<ROClient>, maxRoClient: Int, freeGMList: MutableList<GmActor>, freeEmptyMapList: MutableList<MapPos>, finishedTests: MutableList<TestMetadata>, tryItLaterTests: MutableList<TestMetadata>, runningTests: MutableList<TestMetadata>) {
        val sb = StringBuilder()
        val nameColumnLength = 20
        val durationColumnLength = 20
        val stateLength = 30
        val tryCountLen = 20
        val tableLen = nameColumnLength + durationColumnLength + stateLength + tryCountLen + 6
        sb.append("=\n".padStart(tableLen, '='))
        sb.append("| RoClients[${freeROClients.size}\\${maxRoClient}]\n")
        sb.append("| GMs[${freeGMList.size}\\${gmActors.size}]\n")
        sb.append("| Maps[${freeEmptyMapList.size}\\${emptyMaps.size}]\n")
        sb.append("=\n".padStart(tableLen, '='))
        sb.append("|").append(alignCenter("Name", nameColumnLength)).append("|")
        sb.append(alignCenter("Duration (ms)", durationColumnLength)).append("|")
        sb.append(alignCenter("TryCount", tryCountLen)).append("|")
        sb.append(alignCenter("State", stateLength)).append("|")
        sb.append("\n=".padEnd(tableLen, '='))

        val allTestsAndTheirState = finishedTests.map {
            it to if (it.failureReasons.isNotEmpty()) "Fail(${it.failureReasons.last()})" else "Success"
        } + tryItLaterTests.map {
            it to if (it.failureReasons.isNotEmpty()) "Waiting(${it.failureReasons.last()})" else "Waiting"
        } + runningTests.map { it to "Running" }

        allTestsAndTheirState.forEach { (test, state) ->
            sb.append("\n|")
            sb.append(test.testCase.scenarioDescription.take(nameColumnLength).padEnd(nameColumnLength))
            sb.append("|")
            sb.append(alignCenter("${test.endTime - test.startTime}", durationColumnLength))
            sb.append("|")
            sb.append(alignCenter("${test.tryCount}", tryCountLen))
            sb.append("|")
            sb.append(" " + state)
            sb.append("|")
        }
        sb.append("\n=".padEnd(tableLen, '='))
        logger.info("\n${sb.toString()}")
    }

    private fun alignCenter(text: String, columnLength: Int): String {
        val txtLen = text.length
        val paddingSpaceCount = (columnLength - txtLen) / 2
        return text.take(columnLength).padStart(paddingSpaceCount + txtLen).padEnd(maxOf(columnLength, paddingSpaceCount + txtLen + paddingSpaceCount))
    }

    private fun measureTimeAndMoveMetadata(from: MutableList<TestMetadata>,
                                           to: MutableList<TestMetadata>,
                                           testCase: TestCase,
                                           failureReason: FailureReason? = null) {
        val testMetadata = from.find { it.testCase == testCase }!!
        from.remove(testMetadata)
        to.add(testMetadata.copy(
                endTime = System.currentTimeMillis(),
                failureReasons = if (failureReason != null) testMetadata.failureReasons + failureReason else testMetadata.failureReasons
        ))
    }

    private fun releaseGmsAndChars(gmsOccupiedByTestCases: MutableMap<TestCase, GmActor>,
                                   freeGMList: MutableList<GmActor>,
                                   roCLientsOccupiedByTestCases: MutableMap<TestCase, MutableList<ROClient>>,
                                   freeROClients: MutableList<ROClient>,
                                   mapsOccupiedByTests: MutableMap<TestCase, String>,
                                   testCase: TestCase) {
        val gm = gmsOccupiedByTestCases.remove(testCase)
        if (gm != null) {
            freeGMList.add(gm)
        }
        mapsOccupiedByTests.remove(testCase)
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
//            throw e
        }
    }

    suspend fun requestRoClient(): ROClient {
        val answerChannel = Channel<ROClient?>()
        testDirectorChannel.send(RequestRoCLient(testCase, answerChannel))
        val roClient = answerChannel.receive()
        if (roClient != null) {
            return roClient
        } else {
            testDirectorChannel.send(TestFailed(testCase, NoFreeRoClient()))
            throw CancellationException("NoFreeRoClient")
        }
    }

    suspend fun succeed() {
        testDirectorChannel.send(TestSucceeded(testCase))
    }
}