package hu.nevermind.rotester.test

import hu.nevermind.rotester.FromServer
import hu.nevermind.rotester.ROClient
import hu.nevermind.rotester.ToServer

class TestWhisperingToEachOther : TestDefinition({ testDirector ->
    val a = testDirector.requestRoClient()
    val b = testDirector.requestRoClient()

    sendWhisper(a to b, "Do you get it? o-O")
    val whisperResult = a.packetArrivalVerifier.waitForPacket(FromServer.WhisperResultPacket::class, 1000)
    require(whisperResult.result == FromServer.WhisperResult.Success)
    b.packetArrivalVerifier.waitForPacket(FromServer.NotifyPlayerWhisperChat::class, 1000) { packet ->
        packet.msg == "Do you get it? o-O" &&
                packet.from == a.clientState.charName
    }

    sendWhisper(b to a, "Sure!")
    val whisperResult2 = b.packetArrivalVerifier.waitForPacket(FromServer.WhisperResultPacket::class, 1000)
    require(whisperResult2.result == FromServer.WhisperResult.Success)
    a.packetArrivalVerifier.waitForPacket(FromServer.NotifyPlayerWhisperChat::class, 1000) { packet ->
        packet.msg == "Sure!" &&
                packet.from == b.clientState.charName
    }
})

private suspend fun sendWhisper(chatters: Pair<ROClient, ROClient>, message: String) {
    chatters.first.mapSession.send(ToServer.Whisper(chatters.second.clientState.charName, message))
}
