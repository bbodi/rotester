package hu.nevermind.rotester.test

import hu.nevermind.rotester.FromServer
import hu.nevermind.rotester.ToServer
import hu.nevermind.rotester.assertEquals

object WalkingTest : TestDefinition({
    given("A standing RoClient") {
        val roClient = testDirector.requestRoClient()

        on("Moving to left") {
            testDirector.warpMeTo(
                    "prontera", 100, 101,
                    roClient.clientState.charName,
                    roClient.mapSession,
                    roClient.packetArrivalVerifier)
            roClient.packetArrivalVerifier.cleanPacketHistory()
            roClient.mapSession.send(ToServer.WalkTo(100, 90))
            it("should get a ChangeMap packet with the expected coordinates") {
//                val asd = roClient.packetArrivalVerifier.waitForPacket(FromServer.WalkOk::class, 5000)
                val asd = roClient.packetArrivalVerifier.collectIncomingPackets(FromServer.Packet::class, 5000)
                println(asd)
            }
        }
    }
})