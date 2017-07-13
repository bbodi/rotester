package hu.nevermind.rotester

import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder


fun main(args: Array<String>) = runBlocking {

    //    val socketChannel = SocketChannel.open()
    val jobs = List(1) { index ->
        launch(CommonPool) {
            val gmActor = GmActor("gmgm", "gmgm")
            val player = PlayerActor("bot1", "bot1", gmActor.actor.channel)
            gmActor.actor.join()
//            GmActor("bot1", "bot1").actor.join()
        }
    }
    jobs.forEach { it.join() }
}


fun toIpString(ip: Int): String {
    val ipBuf = ByteBuffer.allocate(4)
    ipBuf.order(ByteOrder.LITTLE_ENDIAN)
    ipBuf.putInt(ip)
    ipBuf.flip()
    val charServerIp = ipBuf.array().joinToString(".")
    return charServerIp
}