package coroutines

import coroutines.ChallengeStatement.ChallengeBlock
import functional.collections.map.filter
import io.ktor.utils.io.CancellationException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertEquals

private fun generateChallenge(expectedStatements: Int, vg: ValueGenerator = ValueGenerator()): ChallengeStatement {
    var state: ChallengeStatement =
        ChallengeStatement.CoroutineScope(generateInitialBodyStatements(expectedStatements, vg = vg))
    do {
        while (true) {
            val statementsToAdd = expectedStatements - state.countStatements()
            if (statementsToAdd <= 0) break
            state = state.addRandomStatementToRandomBlock(statementLeft = statementsToAdd, vg = vg)
        }
        state = state
            .purgePrintsThatHappenAtTheSameTime()
            .purgeStatementsThatNotAffectResult()
    } while (state.countStatements() < expectedStatements)
    return state
}

private fun generateInitialBodyStatements(statementLeft: Int, vg: ValueGenerator): List<ChallengeStatement> =
    buildList {
        add(
            generateChallengeStatement(
                randomChallengeStatementType(statementsLeft = statementLeft, isFirstInBlock = true, vg = vg),
                statementLeft = statementLeft,
                vg = vg
            )
        )
    }

private fun generateChallengeStatement(
    type: ChallengeStatementType,
    statementLeft: Int,
    vg: ValueGenerator
): ChallengeStatement {
    fun generateStatementBody() =
        generateInitialBodyStatements(statementLeft = statementLeft - type.statementsNeeded, vg = vg)
    return when (type) {
        ChallengeStatementType.Delay -> ChallengeStatement.Delay(vg.random.nextInt(1, 3) * 1000)
        ChallengeStatementType.Print -> ChallengeStatement.Print(vg.nextString())
        ChallengeStatementType.Launch -> ChallengeStatement.Launch(generateStatementBody())
        ChallengeStatementType.CoroutineScope -> ChallengeStatement.CoroutineScope(generateStatementBody())

        ChallengeStatementType.AsyncAwait -> ChallengeStatement.Async(
            variableName = vg.nextVariableName(),
            resultString = vg.nextString(),
            statements = generateStatementBody()
        )

        ChallengeStatementType.LaunchJoin, ChallengeStatementType.LaunchCancel -> ChallengeStatement.LaunchJob(
            variableName = vg.nextJobName(),
            statements = generateStatementBody()
        )

        ChallengeStatementType.ThrowException -> ChallengeStatement.ThrowException(
            cancellation = false
        )

        ChallengeStatementType.ThrowCancellationException -> ChallengeStatement.ThrowException(
            cancellation = true
        )

        ChallengeStatementType.TryCatch -> ChallengeStatement.TryCatch(
            statements = generateStatementBody()
        )

        ChallengeStatementType.SupervisorScope -> ChallengeStatement.SupervisorScope(
            statements = generateStatementBody()
        )
//        ChallengeStatementType.RunBlocking -> ChallengeStatement.RunBlocking(
//            statements = generateStatementBody()
//        )
    }
}

private fun generateStatementUsageStatement(
    statementType: ChallengeStatementType,
    statement: ChallengeStatement
): ChallengeStatement = when (statementType) {
    ChallengeStatementType.AsyncAwait -> ChallengeStatement.PrintAwait((statement as ChallengeStatement.Async).variableName)
    ChallengeStatementType.LaunchJoin -> ChallengeStatement.Join((statement as ChallengeStatement.LaunchJob).variableName)
    ChallengeStatementType.LaunchCancel -> ChallengeStatement.Cancel((statement as ChallengeStatement.LaunchJob).variableName)
    else -> error("Define generateStatementUsageStatement value for $statementType")
}

private fun randomChallengeStatementType(
    vg: ValueGenerator,
    isFirstInBlock: Boolean = false,
    statementsLeft: Int = Int.MAX_VALUE,
) =
    (ChallengeStatementType.entries + ChallengeStatementType.Delay + ChallengeStatementType.Delay + ChallengeStatementType.Print)
        .filter { statementsLeft >= it.statementsNeeded }
        .let { if (isFirstInBlock) it.filterNot { it.hasUsage } - listOf(ChallengeStatementType.Print) else it }
        .randomOrNull(vg.random) ?: ChallengeStatementType.Print

enum class ChallengeStatementType(
    val isBlock: Boolean = false,
    val hasUsage: Boolean = false,
) {
    Delay,
    Print,
    Launch(isBlock = true),
    CoroutineScope(isBlock = true),

    // 2
    AsyncAwait(isBlock = true, hasUsage = true),
    LaunchJoin(isBlock = true, hasUsage = true),
    LaunchCancel(isBlock = true, hasUsage = true),

    // 3
//    RunBlocking,
    ThrowException,
    ThrowCancellationException,
    TryCatch(isBlock = true),
    SupervisorScope(isBlock = true)
    ;

    val statementsNeeded = 1 + (if (isBlock) 1 else 0) + (if (hasUsage) 1 else 0)
}

private fun ChallengeStatement.countStatements(): Int =
    if (this is ChallengeStatement.ChallengeBlock) statements.sumOf { it.countStatements() } + 1
    else 1

private fun ChallengeStatement.countBlocks(): Int =
    if (this is ChallengeStatement.ChallengeBlock) statements.sumOf { it.countBlocks() } + 1
    else 0

private fun ChallengeStatement.countPossibleInsertionPoints(): Int =
    if (this is ChallengeStatement.ChallengeBlock) statements.size + 1 + statements.sumOf { it.countPossibleInsertionPoints() }
    else 0

private fun ChallengeStatement.addRandomStatementToRandomBlock(
    statementLeft: Int,
    vg: ValueGenerator
): ChallengeStatement {
    val statementType = randomChallengeStatementType(statementsLeft = statementLeft, vg = vg)
    val statement = generateChallengeStatement(
        statementType,
        statementLeft = statementLeft - statementType.statementsNeeded,
        vg = vg
    )

    println(this.toCode())
    return addStatementAtRandomPosition(statementType, statement, vg = vg)
}

private fun ChallengeStatement.addStatementAtRandomPosition(
    statementType: ChallengeStatementType,
    statement: ChallengeStatement,
    vg: ValueGenerator
): ChallengeStatement {
    val possibleInsertionPoints = countPossibleInsertionPoints()
    var chosenBlock = if (possibleInsertionPoints <= 1) 0 else vg.random.nextInt(0, possibleInsertionPoints - 1)
    var current = 0
    var valueToInsertAtChosenBlock = statement
    fun ChallengeStatement.addStatementToChosenInsertionPoint(): ChallengeStatement =
        if (this is ChallengeBlock) {
            val innerInsertionPoints = countPossibleInsertionPoints()
            if (chosenBlock in current..(current + statements.size)) {
                // Adding element to the block
                val indexWhereToAddStatement = chosenBlock - current
                val newStatements = statements.plusAt(indexWhereToAddStatement, valueToInsertAtChosenBlock)
                val newBlock = this.withStatements(newStatements)
                current += newStatements.size
                if (statementType.hasUsage) {
                    // Adding usage, must add it to later position in the same block, including blocks
                    val restOfStatementsInBlock = newStatements.drop(indexWhereToAddStatement + 1)
                    val laterPositionInsertionPoints =
                        restOfStatementsInBlock.sumOf { it.countPossibleInsertionPoints() + 1 } + 1
                    val usage = generateStatementUsageStatement(statementType, statement)
                    println("The rest is:\n${restOfStatementsInBlock.joinToString { it.toCode() }}")
                    println("Can insert rest in $laterPositionInsertionPoints insertion points")
                    val chosenUsageInsertionPoint = vg.random.nextInt(laterPositionInsertionPoints)
                    if (chosenUsageInsertionPoint <= restOfStatementsInBlock.size) {
                        // Adding to the same block
                        print("Adding to the same block")
                        newBlock.withStatements(
                            newBlock.statements.plusAt(
                                indexWhereToAddStatement + 1 + chosenUsageInsertionPoint,
                                usage
                            )
                        )
                    } else {
                        // Adding to later child block
                        print("Adding to later child block")
                        chosenBlock += newBlock.countPossibleInsertionPoints() + chosenUsageInsertionPoint
                        valueToInsertAtChosenBlock = usage
                        newBlock.withStatements(newBlock.statements.map { it.addStatementToChosenInsertionPoint() })
                    }
                } else {
                    newBlock
                }
            } else if (chosenBlock in current..(current + innerInsertionPoints)) {
                current += statements.size
                this.withStatements(statements.map { it.addStatementToChosenInsertionPoint() })
            } else {
                current += innerInsertionPoints
                this
            }
        } else {
            current++
            this
        }
    return addStatementToChosenInsertionPoint()
}

fun ChallengeStatement.purgeStatementsThatNotAffectResult(): ChallengeStatement {
    println("Purging $this")
    if (this !is ChallengeBlock) return this
    var newStatements = statements

    // We must compare like statements, otherwise comparing launch or async is useless
    fun getResult(statements: List<ChallengeStatement>) =
        ChallengeStatement.CoroutineScope(statements).getResult()

    val currentResult = getResult(statements)
    fun theSameResult(statements: List<ChallengeStatement>) = getResult(statements) == currentResult

    fun List<ChallengeStatement>.removeUsages(statement: ChallengeStatement): List<ChallengeStatement> {
        if (statement is ChallengeStatement.WithUsage) {
            return ChallengeStatement.CoroutineScope(this)
                .minusAll(
                    ChallengeStatement.Cancel(statement.variableName),
                    ChallengeStatement.Join(statement.variableName),
                    ChallengeStatement.PrintAwait(statement.variableName),
                )
                .statements
        }
        return this
    }

    println("The result is: $currentResult")

    // Try inlining different statements
    statements.forEach { statement ->
        if (statement !is ChallengeBlock) return@forEach
        val afterInlining = statements.flatMap { if (it == statement) statement.statements else listOf(it) }
            .removeUsages(statement)
        println(
            "After inlining $statement, the result is: ${
                ChallengeStatement.CoroutineScope(statements).getResult()
            }"
        )
        if (theSameResult(afterInlining)) {
            println("It is the same, so inlining $statement")
            newStatements = afterInlining
            println("the result it $afterInlining")
        }
    }

    // Try removing different statements
    newStatements.forEach { statement ->
        val afterRemoving = (newStatements - statement).removeUsages(statement)
        println(
            "After removing $statement, the result is: ${
                ChallengeStatement.CoroutineScope(statements).getResult()
            }"
        )
        if (theSameResult(afterRemoving)) {
            println("It is the same, so removing $statement")
            newStatements = afterRemoving
            println("the result it $afterRemoving")
        }
    }

    // Remove empty blocks
    val emptyBlocks = newStatements.filter { it is ChallengeBlock && it.statements.isEmpty() }
    newStatements = newStatements.minus(emptyBlocks)
    for (emptyBlock in emptyBlocks) {
        newStatements = newStatements.removeUsages(emptyBlock)
    }

    // Remove repeating print or delay
    for ((curr, next) in newStatements.zipWithNext()) {
        if (curr is ChallengeStatement.Print && next is ChallengeStatement.Print) {
            newStatements -= curr
        }
        if (curr is ChallengeStatement.Delay && next is ChallengeStatement.Delay) {
            newStatements -= curr
        }
    }

    // Try the same for all statements
    newStatements = newStatements.map { it.purgeStatementsThatNotAffectResult() }

    if (newStatements != statements) {
        return withStatements(newStatements)
            .purgeStatementsThatNotAffectResult()
    } else {
        return this
    }
}

fun ChallengeStatement.purgePrintsThatHappenAtTheSameTime(): ChallengeStatement {
    val results = this.getResult()
    var newStatement = this
    for ((elem, next) in results.zipWithNext()) {
        if (elem.time == next.time) {
            if (ChallengeStatement.Print(elem.value) in this) {
                newStatement = this - ChallengeStatement.Print(elem.value)
            } else {
                newStatement = this - ChallengeStatement.Print(next.value)
            }
        }
    }
    return newStatement
}

private operator fun ChallengeStatement.contains(statement: ChallengeStatement): Boolean = when (this) {
    is ChallengeBlock -> this == statement || statements.any { it.contains(statement) }
    else -> this == statement
}

private operator fun ChallengeStatement.minus(elem: ChallengeStatement): ChallengeStatement = when (this) {
    is ChallengeBlock -> withStatements((statements - elem).map { it - elem })
    else -> this
}


private fun ChallengeBlock.minusAll(vararg elem: ChallengeStatement): ChallengeBlock =
    withStatements((statements - elem).map { if (it is ChallengeBlock) it.minusAll(*elem) else it })

private fun <T> List<T>.plusAt(index: Int, element: T): List<T> {
    require(index in 0..size)
    val mutable = toMutableList()
    mutable.add(index, element)
    return mutable
}

sealed class ChallengeStatement {
    sealed class ChallengeBlock : ChallengeStatement() {
        abstract val statements: List<ChallengeStatement>
        abstract fun withStatements(statements: List<ChallengeStatement>): ChallengeBlock
    }

    interface WithUsage {
        val variableName: String
    }

    // 1
    data class Delay(val time: Int) : ChallengeStatement()
    data class Print(val text: String) : ChallengeStatement()
    data class Launch(override val statements: List<ChallengeStatement>) : ChallengeBlock() {
        override fun withStatements(statements: List<ChallengeStatement>): ChallengeBlock =
            copy(statements = statements)
    }

    data class CoroutineScope(
        override val statements: List<ChallengeStatement>
    ) : ChallengeBlock() {
        override fun withStatements(statements: List<ChallengeStatement>): ChallengeBlock =
            copy(statements = statements)
    }

    // 2
    data class LaunchJob(
        override val variableName: String,
        override val statements: List<ChallengeStatement>
    ) : ChallengeBlock(), WithUsage {
        override fun withStatements(statements: List<ChallengeStatement>): ChallengeBlock =
            copy(statements = statements)
    }

    data class Async(
        override val variableName: String,
        val resultString: String,
        override val statements: List<ChallengeStatement>
    ) : ChallengeBlock(), WithUsage {
        override fun withStatements(statements: List<ChallengeStatement>): ChallengeBlock =
            copy(statements = statements)
    }

    data class Join(val variableName: String) : ChallengeStatement()
    data class Cancel(val variableName: String) : ChallengeStatement()
    data class PrintAwait(val variableName: String) : ChallengeStatement()

    data class ThrowException(val cancellation: Boolean) : ChallengeStatement()
    data class RunBlocking(override val statements: List<ChallengeStatement>) : ChallengeBlock() {
        override fun withStatements(statements: List<ChallengeStatement>): ChallengeBlock =
            copy(statements = statements)
    }

    data class TryCatch(override val statements: List<ChallengeStatement>) : ChallengeBlock() {
        override fun withStatements(statements: List<ChallengeStatement>): ChallengeBlock =
            copy(statements = statements)
    }

    data class SupervisorScope(override val statements: List<ChallengeStatement>) : ChallengeBlock() {
        override fun withStatements(statements: List<ChallengeStatement>): ChallengeBlock =
            copy(statements = statements)
    }
}

private fun ChallengeStatement.toCode(): String = when (this) {
    is ChallengeStatement.CoroutineScope -> "coroutineScope {\n${statements.toCodeWithIndent()}\n}"
    is ChallengeStatement.Launch -> "launch {\n${statements.toCodeWithIndent()}\n}"
    is ChallengeStatement.LaunchJob -> "val $variableName = launch {\n${statements.toCodeWithIndent()}\n}"
    is ChallengeStatement.Async -> "val $variableName = async {\n${statements.toCodeWithIndent()}\n    \"$resultString\"\n}"
    is ChallengeStatement.Delay -> "delay($time)"
    is ChallengeStatement.Print -> "println(\"${text}\")"
    is ChallengeStatement.PrintAwait -> "println($variableName.await())"
    is ChallengeStatement.Join -> "$variableName.join()"
    is ChallengeStatement.Cancel -> "$variableName.cancel()"
    is ChallengeStatement.RunBlocking -> "runBlocking {\n${statements.toCodeWithIndent()}\n}"
    is ChallengeStatement.SupervisorScope -> "supervisorScope {\n${statements.toCodeWithIndent()}\n}"
    is ChallengeStatement.TryCatch -> "try {\n${statements.toCodeWithIndent()}\n} catch (e: Exception) {\n    println(\"Got exception\")\n}"
    is ChallengeStatement.ThrowException -> if (cancellation) "throw CancellationException()" else "throw Exception()"
}

private fun List<ChallengeStatement>.toCodeWithIndent() = joinToString(separator = "\n") {
    it.toCode().prependIndent("    ")
}

data class PrintWithTime(val value: String, val time: Long)

private fun ChallengeStatement.getResult(): List<PrintWithTime> = buildList<PrintWithTime> {
    val jobs = mutableMapOf<String, Job>()
    val deferred = mutableMapOf<String, Deferred<String>>()
    println("Evaluating:\n${this@getResult}\n${toCode()}")
    try {
        runTest {
            suspend fun evaluate(statement: ChallengeStatement, scope: CoroutineScope) {
                when (statement) {
                    is ChallengeStatement.CoroutineScope -> coroutineScope {
                        statement.statements.forEach { evaluate(it, this@coroutineScope) }
                    }

                    is ChallengeStatement.Launch -> scope.launch {
                        statement.statements.forEach { evaluate(it, this@launch) }
                    }

                    is ChallengeStatement.LaunchJob -> jobs[statement.variableName] = scope.launch {
                        statement.statements.forEach { evaluate(it, this@launch) }
                    }

                    is ChallengeStatement.Async -> deferred[statement.variableName] = scope.async {
                        statement.statements.forEach { evaluate(it, this@async) }
                        statement.resultString
                    }

                    is ChallengeStatement.Delay -> delay(statement.time.toLong())
                    is ChallengeStatement.Print -> add(PrintWithTime(statement.text, currentTime))
                    is ChallengeStatement.Cancel -> requireNotNull(jobs[statement.variableName]) { "No ${statement.variableName} in jobs $jobs" }.cancel()
                    is ChallengeStatement.Join -> requireNotNull(jobs[statement.variableName]) { "No ${statement.variableName} in jobs $jobs" }.join()
                    is ChallengeStatement.PrintAwait -> add(
                        PrintWithTime(
                            requireNotNull(deferred[statement.variableName]) { "No ${statement.variableName} in deferred $deferred" }.await(),
                            currentTime
                        )
                    )

                    // TODO
                    is ChallengeStatement.RunBlocking -> runBlocking {
                        statement.statements.forEach {
                            evaluate(it, this)
                        }
                    }

                    is ChallengeStatement.SupervisorScope -> supervisorScope {
                        statement.statements.forEach { evaluate(it, this) }
                    }

                    is ChallengeStatement.TryCatch -> try {
                        statement.statements.forEach { evaluate(it, this) }
                    } catch (e: Exception) {
                        add(PrintWithTime("Got exception", currentTime))
                    }

                    is ChallengeStatement.ThrowException -> if (statement.cancellation) throw GameCancellationException() else throw GameException()
                }
            }
            try {
                evaluate(this@getResult, this)
                add(PrintWithTime("(done)", currentTime))
            } catch (npe: GameException) {
                add(PrintWithTime("(exception)", currentTime))
            } catch (npe: GameCancellationException) {
                add(PrintWithTime("(cancellation exception)", currentTime))
            }
        }
    } catch (npe: GameException) {
        // no-op
    } catch (npe: GameCancellationException) {
        // no-op
    }
}

private class GameException : Exception()
private class GameCancellationException : CancellationException()


private fun ChallengeStatement.getStringResult() = getResult()
    .joinToString(separator = "\n") { "[${it.time}] ${it.value}" }


private class ValueGenerator(seed: Long = Random.nextLong()) {
    val random = Random(seed)
    val varCounter = AtomicInteger(1)
    fun nextVariableName() = "value${varCounter.getAndIncrement()}"
    val jobCounter = AtomicInteger(1)
    fun nextJobName() = "job${jobCounter.getAndIncrement()}"
    val char = AtomicInteger(0)
    fun nextString() = "${'A' + char.getAndIncrement()}"
}

fun main() {
    val challenge = generateChallenge(20)
    println(challenge)
    println("******")
    println("Before purge")
    println("******")
    println(challenge.toCode())
    println("******")
    println("After purge")
    println("******")
    println(challenge.purgeStatementsThatNotAffectResult().toCode())
    println("******")
    println(challenge.getStringResult())
}

class AsyncGameTest {

    @Test
    fun `should not create bigger challenge than expected`() = repeat(20) {
        val size = it * 2 + 5
        val challenge = generateChallenge(size, ValueGenerator(it * 1234L))
        assertEquals(size, challenge.countStatements())
    }

    @Test
    fun `should not generate two prints or delays one after another`() {
    }

    @Test
    fun `should purge statements that do not change result`() {
    }

    @Test
    fun `should produce correct code`() {
    }

    @Test
    fun `should predict correct result`() {
    }
}
