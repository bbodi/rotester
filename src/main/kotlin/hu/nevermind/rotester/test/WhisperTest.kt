package hu.nevermind.rotester.test

import hu.nevermind.rotester.FromServer
import hu.nevermind.rotester.ToServer

object WhisperTest : TestDefinition({
    given("Two RO clients") {
        val a = testDirector.requestRoClient()
        val b = testDirector.requestRoClient()

        on("sending a Whisper message from A to B") {
            a.mapSession.send(ToServer.Whisper(b.clientState.charName, "Do you get it? o-O"))
            it("client A should get a WhisperResultPacket(success)") {
                val whisperResult = a.packetArrivalVerifier.waitForPacket(FromServer.WhisperResultPacket::class, 1000)
                require(whisperResult.result == FromServer.WhisperResult.Success)
            }
            it("client B should get a WhisperNotification from client A with the sent message") {
                b.packetArrivalVerifier.waitForPacket(FromServer.NotifyPlayerWhisperChat::class, 1000) { packet ->
                    packet.msg == "Do you get it? o-O" &&
                            packet.from == a.clientState.charName
                }
            }
        }

        on("answering to this message") { // TODO: this second 'on' "depends" on the first one
            b.mapSession.send(ToServer.Whisper(a.clientState.charName, "Sure!"))
            it("client B should get a WhisperResultPacket(success)") {
                val whisperResult = b.packetArrivalVerifier.waitForPacket(FromServer.WhisperResultPacket::class, 1000)
                require(whisperResult.result == FromServer.WhisperResult.Success)
            }
            it("client A should get a WhisperNotification from client B with the answer") {
                a.packetArrivalVerifier.waitForPacket(FromServer.NotifyPlayerWhisperChat::class, 1000) { packet ->
                    packet.msg == "Sure!" &&
                            packet.from == b.clientState.charName
                }
            }
        }
    }
})

//
//testDirector.actor.send(StartTest(TestCase("Whisper must work") { whisperSenderClient, testDirector ->
//    val whisperReceiverClient = testDirector.requestRoClient()
//
//    whisperSenderClient.mapSession.send(ToServer.Whisper(whisperReceiverClient.clientState.charName, "Do you get it? o-O"))
//    val whisperResult = whisperSenderClient.packetArrivalVerifier.waitForPacket(FromServer.WhisperResultPacket::class, 1000)
//    require(whisperResult.result == FromServer.WhisperResult.Success)
//    whisperReceiverClient.packetArrivalVerifier.waitForPacket(FromServer.NotifyPlayerWhisperChat::class, 1000) { packet ->
//        packet.msg == "Do you get it? o-O"
//    }
//
//    whisperReceiverClient.mapSession.send(ToServer.Whisper(whisperSenderClient.clientState.charName, "Sure!"))
//    val whisperResponseResult = whisperReceiverClient.packetArrivalVerifier.waitForPacket(FromServer.WhisperResultPacket::class, 1000)
//    require(whisperResponseResult.result == FromServer.WhisperResult.Success)
//    whisperSenderClient.packetArrivalVerifier.waitForPacket(FromServer.NotifyPlayerWhisperChat::class, 1000) { packet ->
//        packet.msg == "Sure!"
//    }
//    testDirector.succeed()
//}))