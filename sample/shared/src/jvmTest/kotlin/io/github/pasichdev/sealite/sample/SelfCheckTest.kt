package io.github.pasichdev.sealite.sample

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class SelfCheckTest {

    @Test
    fun everySelfCheckPasses() {
        val dir: Path = Files.createTempDirectory("sealite-sample-test")
        try {
            val services = openSampleServices(dir.toString())
            try {
                val results = runBlocking { services.runSelfCheck() }
                println(results.toReport())
                assertTrue(results.size >= 5, "expected the five checks, got ${results.size}")
                assertTrue(results.allPassed(), results.toReport())
            } finally {
                services.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun notesRoundTripThroughRoom() {
        val dir: Path = Files.createTempDirectory("sealite-sample-test")
        try {
            val services = openSampleServices(dir.toString())
            try {
                val dao = services.db.noteDao()
                runBlocking { dao.insert(Note(text = "hello", createdAtMillis = 1)) }
                val all = runBlocking { dao.observeAll().first() }
                assertTrue(all.single().text == "hello")
            } finally {
                services.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
