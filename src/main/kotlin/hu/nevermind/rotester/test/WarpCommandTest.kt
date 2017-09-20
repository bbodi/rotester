package hu.nevermind.rotester.test

import hu.nevermind.rotester.FromServer
import hu.nevermind.rotester.assertEquals


class WarpingCharFromPronteraToGeffenShouldSendChangeMapPacketWithTheExpectedCoordinates : TestDefinition({ testDirector ->
    val roClient = testDirector.requestRoClient()
    testDirector.warpMeTo(
            "prontera", 100, 101,
            roClient.clientState.charName,
            roClient.mapSession,
            roClient.packetArrivalVerifier
    )
    roClient.packetArrivalVerifier.cleanPacketHistory()
    testDirector.warpMeTo(
            "geffen", 154, 54,
            roClient.clientState.charName,
            roClient.mapSession,
            roClient.packetArrivalVerifier
    )
    roClient.packetArrivalVerifier.waitForPacket(FromServer.ChangeMap::class, 5000).apply {
        require(mapName == "geffen.gat") { "$mapName != geffen.gat" }
        require(x == 154)
        require(y == 54)
    }
})

class WarpingCharFromPronteraToGeffenThenToBackToPronteraShouldSendChangeMapPacketWithTheExpectedCoordinates : TestDefinition({ testDirector ->
    val roClient = testDirector.requestRoClient()
    testDirector.warpMeTo(
            "prontera", 100, 101,
            roClient.clientState.charName,
            roClient.mapSession,
            roClient.packetArrivalVerifier)
    testDirector.warpMeTo(
            "geffen", 154, 54,
            roClient.clientState.charName,
            roClient.mapSession,
            roClient.packetArrivalVerifier)
    roClient.packetArrivalVerifier.cleanPacketHistory()
    testDirector.warpMeTo(
            "prontera", 102, 103,
            roClient.clientState.charName,
            roClient.mapSession,
            roClient.packetArrivalVerifier)
    roClient.packetArrivalVerifier.waitForPacket(FromServer.ChangeMap::class, 5000).apply {
        assertEquals("prontera.gat", mapName)
        assertEquals(102, x)
        assertEquals(103, y)
    }
})
