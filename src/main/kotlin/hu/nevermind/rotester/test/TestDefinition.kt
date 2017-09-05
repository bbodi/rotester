package hu.nevermind.rotester.test

import hu.nevermind.rotester.*

internal class GivenDefinition(val description: String,
                               val body: suspend GivenBuilder.() -> Unit,
                               val onDefinitions: MutableList<OnDefinition>
)

internal class OnDefinition(val description: String,
                            val body: suspend OnBuilder.() -> Unit,
                            val itDefinitions: MutableList<ItDefinition>
)

internal class ItDefinition(val description: String,
                            val body: suspend ItBuilder.() -> Unit
)

open class TestDefinition(val body: suspend TestDefinitionBuilder.() -> Unit) {

    internal lateinit var given: GivenDefinition

    suspend fun run(testDirector: TestDirector) {
        testDirector.actor.send(StartTest(TestCase(this.javaClass.simpleName) { testDirectorCommunicator ->
            val testDefinitionBuilder = TestDefinitionBuilder(this, testDirectorCommunicator)
            body(testDefinitionBuilder) // given is filled
            val sb = StringBuilder()
            sb.append("\ngiven(${given.description})")
            try {
                given.body.invoke(GivenBuilder(this)) // given.onDefinitions is filled
                given.onDefinitions.forEach { onDefinition ->
                    sb.append("\n\ton(${onDefinition.description})\n")
                    onDefinition.body.invoke(OnBuilder(onDefinition)) // given.onDefinition.itDefinitions is filled
                    onDefinition.itDefinitions.forEach { itDefinition ->
                        val itStr = "\n\t\tit(${itDefinition.description})"
                        sb.append(itStr)
                        itDefinition.body.invoke(ItBuilder(this))
                        sb.append("SUCCESS".padStart(140-itStr.length))
                    }
                    sb.append("\n\tSUCCESS")
                }
                testDirectorCommunicator.succeed()
            } catch (e: Exception) {
                logger.error(sb.toString(), e)
                throw e
            }
        }))
    }
}

class TestDefinitionBuilder(internal val testDefinition: TestDefinition,
                            val testDirector: TestDirectorCommunicator) {
}

class GivenBuilder(internal val testDefinition: TestDefinition) {

}

fun TestDefinitionBuilder.given(description: String, body: suspend GivenBuilder.() -> Unit) {
    testDefinition.given = GivenDefinition(description, body, arrayListOf())
}

internal fun GivenBuilder.on(description: String, body: suspend OnBuilder.() -> Unit) {
    testDefinition.given.onDefinitions.add(OnDefinition(description, body, arrayListOf()))
}

internal class OnBuilder(internal val onDefinition: OnDefinition) {

}

class ItBuilder(internal val testDefinition: TestDefinition) {

}

internal fun OnBuilder.it(description: String, body: suspend ItBuilder.() -> Unit) {
    onDefinition.itDefinitions.add(ItDefinition(description, body))
}
