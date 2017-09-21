package hu.nevermind.rotester

enum class TestLockGranularity {
    Global,
    Map
}

data class TestCase(val scenarioDescription: String,
                    val lockGranularity: TestLockGranularity = TestLockGranularity.Map,
                    val testLogic: suspend (TestDirectorCommunicator) -> Unit) {

}


data class ClientState(val mapName: String, val pos: Pos, val charName: String)
