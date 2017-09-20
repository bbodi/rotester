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

data class NoFreeResource(val resourceType: String) : FailureReason()

data class TestMetadata(val testCase: TestCase, val tryCount: Int, val failureReasons: List<FailureReason?>, val startTime: Long, val endTime: Long)

data class ROClient(val clientState: ClientState, val mapSession: Session, val packetArrivalVerifier: PacketArrivalVerifier)

class TestDirector(val gmActors: List<GmActor>, val emptyMaps: List<MapPos>) {

    data class MapPos(val mapName: String, val x: Int, val y: Int)

    private val logger: Logger = LoggerFactory.getLogger(this::class.simpleName)


    val actor = actor<TestDirectorMessage>(CommonPool, 64) {
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
        val gmsOccupiedByTestCases = hashMapOf<String, GmActor>()
        val roClientsOccupiedByTestCases = hashMapOf<String, MutableList<ROClient>>()
        val freeEmptyMapList = emptyMaps.toMutableList()
        val mapsOccupiedByTests = mutableMapOf<String, String>()
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
                            if (tryItLaterTests.size == 1 || Math.random() * 10 < 7) {
                                this@actor.channel.send(StartTest(it.testCase))
                            }
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
                            this@actor.channel.send(TestFailed(msg.sourceTestCase, NoFreeResource("GM")))
                            continue@actorChannelLoop
                        }
                        val gm = freeGMList.removeAt(0)
                        gmsOccupiedByTestCases.put(msg.sourceTestCase.scenarioDescription, gm)
                        if (msg.dstMapName == null && freeEmptyMapList.isEmpty()) {
                            this@actor.channel.send(TestFailed(msg.sourceTestCase, NoFreeResource("Map")))
                            continue@actorChannelLoop
                        }
                        val (dstMapname, dstX, dstY) = if (msg.dstMapName == null) {
                            val dstMap = freeEmptyMapList.removeAt(0)
                            Triple(dstMap.mapName, dstMap.x, dstMap.y)
                        } else {
                            Triple(msg.dstMapName, msg.dstX, msg.dstY)
                        }
                        if (mapsOccupiedByTests.containsValue(dstMapname)) {
                            this@actor.channel.send(TestFailed(msg.sourceTestCase, NoFreeResource("Map")))
                            continue@actorChannelLoop
                        }
                        mapsOccupiedByTests.put(msg.sourceTestCase.scenarioDescription, dstMapname)
                        gm.actor.send(TakeMeTo(msg.charName, dstMapname, dstX, dstY))
                    }
                    is StartTest -> {
                        val alreadyStartedTestMetadata = tryItLaterTests.find { it.testCase.scenarioDescription == msg.testCase.scenarioDescription }
                        if (alreadyStartedTestMetadata != null) {
                            measureTimeAndMoveMetadata(tryItLaterTests, runningTests, alreadyStartedTestMetadata.testCase) {
                                copy(
                                        tryCount = this.tryCount + 1,
                                        startTime = System.currentTimeMillis()
                                )
                            }
                            logger.debug("Try again ${msg.testCase.scenarioDescription}")

                        } else {
                            val testMetadata = TestMetadata(msg.testCase, 1, emptyList(), System.currentTimeMillis(), 0)
                            runningTests.add(testMetadata)
                            logger.debug("Starting test [${msg.testCase.scenarioDescription}], RoClients[${freeROClients.size}\\${maxRoClient}].")
                        }

                        launch(CommonPool) {
                            try {
                                msg.testCase.testLogic(
                                        TestDirectorCommunicator(msg.testCase, this@actor.channel)
                                )
                            } catch (e: Exception) {
                                logger.error(msg.testCase.scenarioDescription, e)
                                if (e !is WaitingForPacketTimeout) {
                                    this@actor.channel.send(TestFailed(msg.testCase, TestCaseError(e)))
                                }
                            }
                        }
                    }
                    is RequestRoCLient -> {
                        if (freeROClients.isEmpty()) {
                            logger.warn("There is no free ROClient for ${msg.testCase.scenarioDescription}!")
                            this@actor.channel.send(TestFailed(msg.testCase, NoFreeResource("RoClient")))
                            msg.answerChannel.send(null)
                        } else {
                            logger.debug("RoClients[${freeROClients.size - 1}\\${maxRoClient}] was requested by [${msg.testCase.scenarioDescription}].")
                            val roClient = freeROClients.removeAt(0)
                            roClientsOccupiedByTestCases.getOrPut(msg.testCase.scenarioDescription, { arrayListOf<ROClient>() }).add(roClient)
                            roClient.packetArrivalVerifier.cleanPacketHistory()
                            msg.answerChannel.send(roClient)
                        }
                    }
                    is TestSucceeded -> {
                        measureTimeAndMoveMetadata(runningTests, finishedTests, msg.testCase) {
                            copy(
                                    failureReasons = this.failureReasons + listOf(null)
                            )
                        }
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
                        if (runningTests.any { msg.sourceTestCase.scenarioDescription == it.testCase.scenarioDescription }) {
                            val targetList = if (msg.failureReason is TestCaseError) {
                                finishedTests
                            } else {
                                tryItLaterTests
                            }
                            measureTimeAndMoveMetadata(runningTests, targetList, msg.sourceTestCase, msg.failureReason)
                        }// else the test is already waiting for retrying
                        logState(freeROClients, maxRoClient, freeGMList, freeEmptyMapList, finishedTests, tryItLaterTests, runningTests)
                        if (runningTests.isEmpty() && tryItLaterTests.isEmpty()) {
                            return@actor
                        }
                    }
                    is MyGmHasFinishedHerJob -> {
                        val gm = gmsOccupiedByTestCases.remove(msg.testCase.scenarioDescription)
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
// TODO: if a client disconnects, its test should be restarted
// TODO: unused bots should be warped to a neutral position
// TODO: long running tests should be restarted
    private fun logState(freeROClients: ArrayList<ROClient>, maxRoClient: Int, freeGMList: MutableList<GmActor>, freeEmptyMapList: MutableList<MapPos>, finishedTests: MutableList<TestMetadata>, tryItLaterTests: MutableList<TestMetadata>, runningTests: MutableList<TestMetadata>) {
        val sb = StringBuilder()
        val nameColumnLength = 60
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
        sb.append(alignCenter("State <------", stateLength)).append("|")
        sb.append("\n=".padEnd(tableLen, '='))


        val allTestsAndTheirState = finishedTests.map {
            val statusList = it.failureReasons.map { if (it != null) it.toString() else "Success" }.reversed().joinToString(" <-- ")
            it to statusList
        } + tryItLaterTests.map {
            val statusList = it.failureReasons.map { if (it != null) it.toString() else "Waiting" }.reversed().joinToString(" <-- ")
            it to statusList
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
        logger.debug("\n${sb.toString()}")
    }

    private fun alignCenter(text: String, columnLength: Int): String {
        val txtLen = text.length
        val paddingSpaceCount = (columnLength - txtLen) / 2
        return text.take(columnLength).padStart(paddingSpaceCount + txtLen).padEnd(maxOf(columnLength, paddingSpaceCount + txtLen + paddingSpaceCount))
    }

    private fun measureTimeAndMoveMetadata(from: MutableList<TestMetadata>,
                                           to: MutableList<TestMetadata>,
                                           testCase: TestCase,
                                           failureReason: FailureReason? = null,
                                           metadataModifier: TestMetadata.() -> TestMetadata = { this }) {
        val testMetadata = from.find { it.testCase.scenarioDescription == testCase.scenarioDescription }
        if (testMetadata != null) {
            from.remove(testMetadata)
            to.add(testMetadata.copy(
                    endTime = System.currentTimeMillis(),
                    failureReasons = if (failureReason != null) testMetadata.failureReasons + failureReason else testMetadata.failureReasons
            ).metadataModifier())
        }
    }

    private fun releaseGmsAndChars(gmsOccupiedByTestCases: MutableMap<String, GmActor>,
                                   freeGMList: MutableList<GmActor>,
                                   roCLientsOccupiedByTestCases: MutableMap<String, MutableList<ROClient>>,
                                   freeROClients: MutableList<ROClient>,
                                   mapsOccupiedByTests: MutableMap<String, String>,
                                   testCase: TestCase) {
        val gm = gmsOccupiedByTestCases.remove(testCase.scenarioDescription)
        if (gm != null) {
            freeGMList.add(gm)
        }
        mapsOccupiedByTests.remove(testCase.scenarioDescription)
        roCLientsOccupiedByTestCases.remove(testCase.scenarioDescription)?.forEach {
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

        packetArrivalVerifier.waitForPacket(FromServer.NotifyPlayerWhisperChat::class, 5000) { packet ->
            packet.msg == "Done"
        }
        testDirectorChannel.send(MyGmHasFinishedHerJob(testCase))
        mapSession.send(ToServer.LoadEndAck())
    }

    suspend fun requestRoClient(): ROClient {
        val answerChannel = Channel<ROClient?>()
        testDirectorChannel.send(RequestRoCLient(testCase, answerChannel))
        val roClient = answerChannel.receive()
        if (roClient != null) {
            return roClient
        } else {
            testDirectorChannel.send(TestFailed(testCase, NoFreeResource("RoClient")))
            throw CancellationException("NoFreeRoClient")
        }
    }

    suspend fun succeed() {
        testDirectorChannel.send(TestSucceeded(testCase))
    }
}