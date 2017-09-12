package hu.nevermind.rotester.test

import hu.nevermind.rotester.FromServer
import hu.nevermind.rotester.ToServer
import hu.nevermind.rotester.assertEquals

object WalkingTest : TestDefinition({
    given("A standing RoClient") {
        val roClient = testDirector.requestRoClient()

        on("Moving to left") {
            testDirector.warpMeTo(
                    "prontera", 100, 100,
                    roClient.clientState.charName,
                    roClient.mapSession,
                    roClient.packetArrivalVerifier)
            roClient.packetArrivalVerifier.cleanPacketHistory()
            roClient.mapSession.send(ToServer.WalkTo(101, 100))
            it("should get a ChangeMap packet with the expected coordinates") {
                val walkOkPacket = roClient.packetArrivalVerifier.waitForPacket(FromServer.WalkOk::class, 5000)
                assertEquals(100, walkOkPacket.posChange.srcX)
                assertEquals(100, walkOkPacket.posChange.srcY)
                assertEquals(101, walkOkPacket.posChange.dstX)
                assertEquals(100, walkOkPacket.posChange.dstY)
                assertEquals(8, walkOkPacket.posChange.sx)
                assertEquals(8, walkOkPacket.posChange.sy)
            }
        }
    }
})