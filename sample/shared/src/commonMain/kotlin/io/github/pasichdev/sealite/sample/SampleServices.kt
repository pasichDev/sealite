package io.github.pasichdev.sealite.sample

/** One line of the self-check screen. */
data class CheckItem(
    val name: String,
    val passed: Boolean,
    val details: List<String>,
)

/** What the UI needs from the encrypted side of the app. */
interface SampleServices : AutoCloseable {
    val db: SampleDb

    /** Where the notes database lives. */
    val dbPath: String

    /** One-line description of what is linked in, e.g. "SQLCipher 4.x, SQLite 3.x, ...". */
    val versionsLine: String

    /** Runs every check against fresh temporary databases; never touches the notes database. */
    suspend fun runSelfCheck(): List<CheckItem>
}

/**
 * Opens the notes database in [dir] through Sealite. Fails with an exception when the library
 * cannot be used on this platform; the UI shows the message.
 */
fun openSampleServices(dir: String): SampleServices = SealiteServices(dir)

/** The app-private directory for the database and the demo key. */
expect fun appDataDir(): String

fun List<CheckItem>.allPassed(): Boolean = isNotEmpty() && all { it.passed }

/** Plain-text rendering, used by the console runner and the tests. */
fun List<CheckItem>.toReport(): String = buildString {
    for (item in this@toReport) {
        appendLine((if (item.passed) "[PASS] " else "[FAIL] ") + item.name)
        for (line in item.details) appendLine("         $line")
    }
    append(if (allPassed()) "ALL CHECKS PASSED" else "SOME CHECKS FAILED")
}
