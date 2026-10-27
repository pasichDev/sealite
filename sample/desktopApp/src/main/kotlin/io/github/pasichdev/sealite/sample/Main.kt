package io.github.pasichdev.sealite.sample

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if ("--selfcheck" in args) {
        exitProcess(runHeadlessSelfCheck())
    }
    application {
        Window(onCloseRequest = ::exitApplication, title = "Sealite sample") {
            App()
        }
    }
}

/** Prints the self-check report; 0 when everything passed. */
private fun runHeadlessSelfCheck(): Int = try {
    val services = openSampleServices(appDataDir())
    try {
        println(services.versionsLine)
        val results = runBlocking { services.runSelfCheck() }
        println(results.toReport())
        if (results.allPassed()) 0 else 1
    } finally {
        services.close()
    }
} catch (e: Throwable) {
    println("Self-check could not start: ${e::class.simpleName}: ${e.message}")
    2
}
