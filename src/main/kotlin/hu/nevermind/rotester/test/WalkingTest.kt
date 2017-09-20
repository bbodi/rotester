package hu.nevermind.rotester.test

import hu.nevermind.rotester.FromServer
import hu.nevermind.rotester.TestDirectorCommunicator
import hu.nevermind.rotester.ToServer
import hu.nevermind.rotester.assertEquals

/*
* prt_fild08 162 353
prt_fild08 128 302
   148 253
   283 238 le nem tud menni
   297 231 balra nem
   269 232 jobbra nem
   258 206 fel nem
   236 164

prt_fild05 84 93
prt_fild09 138 115
prt_fild10 217 97
* */

class TestOnMovingRightWalkOkPacketShouldArrive : TestDefinition({ testDirector ->
    testMovingTo(testDirector, +1, 0)
})

class TestOnMovingLeftWalkOkPacketShouldArrive : TestDefinition({ testDirector ->
    testMovingTo(testDirector, -1, 0)
})

class TestOnMovingUpWalkOkPacketShouldArrive : TestDefinition({ testDirector ->
    testMovingTo(testDirector, 0, -1)
})

class TestOnMovingDownWalkOkPacketShouldArrive : TestDefinition({ testDirector ->
    testMovingTo(testDirector, 0, +1)
})

private suspend fun testMovingTo(testDirector: TestDirectorCommunicator, deltaX: Int, deltaY: Int) {
    val roClient = testDirector.requestRoClient()
    testDirector.warpMeTo(
            "prt_fild10", 217, 97,
            roClient.clientState.charName,
            roClient.mapSession,
            roClient.packetArrivalVerifier)
    roClient.mapSession.send(ToServer.WalkTo(217 + deltaX, 98 + deltaY))
    val walkOkPacket = roClient.packetArrivalVerifier.waitForPacket(FromServer.WalkOk::class, 5000)
    assertEquals(217, walkOkPacket.posChange.srcX)
    assertEquals(97, walkOkPacket.posChange.srcY)
    assertEquals(217 + deltaX, walkOkPacket.posChange.dstX)
    assertEquals(98 + deltaY, walkOkPacket.posChange.dstY)
    assertEquals(8, walkOkPacket.posChange.sx)
    assertEquals(8, walkOkPacket.posChange.sy)
}