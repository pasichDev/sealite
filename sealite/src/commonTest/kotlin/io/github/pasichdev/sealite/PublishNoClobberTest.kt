// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import io.github.pasichdev.sealite.internal.publishWithoutReplacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The no-clobber guarantee of publishing a finished copy, on the file operation itself: a hard link on
 * the JVM and Apple, `renameat2(RENAME_NOREPLACE)` on Android 30 and later, a locked check and rename
 * below that. What is asserted holds on every one of them: one winner among competing Sealite
 * publications, a destination that already exists is never replaced, and neither the loser's file nor
 * the sidecars next to the destination are touched.
 *
 * Not asserted, because it is the documented limit of Android 24 to 29: a writer that does not take
 * Sealite's lock creating the destination between the check and the rename.
 */
class PublishNoClobberTest {
    private val directories = mutableListOf<String>()

    private fun newTarget(): String {
        val path = newTempDatabasePath()
        directories += path.substring(0, maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\')))
        return path
    }

    @AfterTest
    fun cleanUp() {
        for (directory in directories) {
            // The Android fallback leaves its lock file behind, so the directory is emptied by name.
            for (name in listDirectory(directory)) deleteDatabaseFiles("$directory/$name")
        }
        directories.clear()
    }

    private fun source(target: String, name: String, vararg bytes: Byte): String =
        "$target.$name".also { writeFileBytes(it, bytes) }

    private val sidecars = listOf("-wal", "-shm", "-journal")

    @Test
    fun aDestinationThatExistsIsNeverReplacedAndNothingAroundItChanges() {
        val target = newTarget()
        val theirs = byteArrayOf(1, 2, 3, 4)
        writeFileBytes(target, theirs)
        for ((i, suffix) in sidecars.withIndex()) writeFileBytes(target + suffix, byteArrayOf(i.toByte(), 7))
        val mine = source(target, "mine", 9, 9)

        assertFailsWith<IllegalStateException> { publishWithoutReplacing(mine, target) }

        assertContentEquals(theirs, readFileBytes(target))
        for ((i, suffix) in sidecars.withIndex()) {
            assertContentEquals(
                byteArrayOf(i.toByte(), 7),
                readFileBytes(target + suffix),
            )
        }
        assertContentEquals(byteArrayOf(9, 9), readFileBytes(mine), "the rejected copy stays where it was")
    }

    @Test
    fun anEmptyDestinationCreatedByAnotherWriterIsNotReplacedEither() {
        val target = newTarget()
        writeFileBytes(target, ByteArray(0))
        val mine = source(target, "mine", 5)

        assertFailsWith<IllegalStateException> { publishWithoutReplacing(mine, target) }

        assertContentEquals(ByteArray(0), readFileBytes(target))
        assertContentEquals(byteArrayOf(5), readFileBytes(mine))
    }

    @Test
    fun aPublishToAFreeNameMovesTheFileAndLeavesTheSidecarsAlone() {
        val target = newTarget()
        for (suffix in sidecars) writeFileBytes(target + suffix, byteArrayOf(3))
        val mine = source(target, "mine", 1, 2)

        publishWithoutReplacing(mine, target)

        assertContentEquals(byteArrayOf(1, 2), readFileBytes(target))
        assertNull(readFileBytes(mine), "the source is gone")
        for (suffix in sidecars) assertContentEquals(byteArrayOf(3), readFileBytes(target + suffix))
        // A second publication of the same name is now a rejected one.
        val late = source(target, "late", 8)
        assertFailsWith<IllegalStateException> { publishWithoutReplacing(late, target) }
        assertContentEquals(byteArrayOf(1, 2), readFileBytes(target))
    }

    @Test
    fun competingPublicationsToOneDestinationHaveExactlyOneWinner() = runTest(timeout = 3.minutes) {
        repeat(8) { round ->
            val target = newTarget()
            val sidecar = byteArrayOf(round.toByte())
            writeFileBytes(target + "-wal", sidecar)
            val contenders = 8
            val sources = List(contenders) { source(target, "from$it", it.toByte(), (it * 2).toByte()) }
            val outcomes =
                withContext(Dispatchers.Default) {
                    coroutineScope {
                        sources
                            .map { path ->
                                async { runCatching { publishWithoutReplacing(path, target) } }
                            }.awaitAll()
                    }
                }
            val failures = outcomes.mapNotNull { it.exceptionOrNull() }
            assertEquals(1, outcomes.count { it.isSuccess }, "round $round: $failures")
            assertTrue(failures.all { it is IllegalStateException }, "round $round: $failures")

            val winner = sources.indices.single { readFileBytes(sources[it]) == null }
            assertContentEquals(
                byteArrayOf(winner.toByte(), (winner * 2).toByte()),
                readFileBytes(target),
                "round $round",
            )
            for (i in sources.indices.filter { it != winner }) {
                assertContentEquals(
                    byteArrayOf(i.toByte(), (i * 2).toByte()),
                    readFileBytes(sources[i]),
                    "loser $i keeps its file",
                )
            }
            assertContentEquals(sidecar, readFileBytes(target + "-wal"))
            assertNull(readFileBytes(target + "-shm"))
            assertNull(readFileBytes(target + "-journal"))
        }
    }

    @Test
    fun competingMigrationsThatFindTheDestinationTakenLeaveItsContentsAndSidecarsAlone() =
        runTest(timeout = 3.minutes) {
            val plain = newTarget()
            val encrypted = newTarget()
            writeFileBytes(plain, PLAINTEXT_DATABASE)
            // The destination is a database that someone else (another process, another library) wrote.
            withDb(encrypted, rawKey(1)) { it.createNotes("theirs") }
            val before = readFileBytes(encrypted)
            val outcomes =
                withContext(Dispatchers.Default) {
                    coroutineScope {
                        List(
                            4,
                        ) { async { runCatching { Sealite.encryptPlaintext(plain, encrypted, rawKey(2)) } } }.awaitAll()
                    }
                }
            assertTrue(outcomes.all { it.isFailure }, "no migration may succeed: $outcomes")
            assertTrue(outcomes.all { it.exceptionOrNull() is IllegalArgumentException }, "$outcomes")
            assertContentEquals(before, readFileBytes(encrypted))
            assertEquals(listOf("theirs"), withDb(encrypted, rawKey(1)) { it.notes() })
            assertContentEquals(PLAINTEXT_DATABASE, readFileBytes(plain))
            assertEquals(
                emptyList(),
                listDirectory(directories.last()).filter { ".sealite-" in it },
                "temporary files left",
            )
            for (suffix in sidecars) assertNull(readFileBytes(encrypted + suffix), suffix)
        }
}
