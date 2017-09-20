package hu.nevermind.rotester.test

import hu.nevermind.rotester.StartTest
import hu.nevermind.rotester.TestCase
import hu.nevermind.rotester.TestDirector
import hu.nevermind.rotester.TestDirectorCommunicator


open class TestDefinition(val testLogic: suspend (TestDirectorCommunicator) -> Unit) {

    suspend fun run(testDirector: TestDirector) {
        testDirector.actor.send(StartTest(TestCase(this.javaClass.simpleName) { testDirectorCommunicator ->
            testLogic(testDirectorCommunicator)
            testDirectorCommunicator.succeed()
        }))
    }
}