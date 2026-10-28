// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite

import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import io.github.pasichdev.sealite.internal.OpenSequence
import io.github.pasichdev.sealite.internal.RawDb
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A real [RawDb] with hooks to make one step of [Sealite.rekey] fail. */
private class FaultyRawDb(
    private val delegate: RawDb,
    private val onPrepare: (String) -> SQLiteStatement? = { null },
    private val onRekey: (RawDb, ByteArray) -> Unit = { db, key -> db.rekey(key) },
    private val stuckInTransaction: Boolean = false,
) : RawDb {
    override fun key(key: ByteArray) = delegate.key(key)

    override fun rekey(key: ByteArray) = onRekey(delegate, key)

    override fun prepare(sql: String): SQLiteStatement = onPrepare(sql) ?: delegate.prepare(sql)

    override fun inTransaction(): Boolean = stuckInTransaction || delegate.inTransaction()

    override fun close() = delegate.close()
}

private const val CHECKPOINT = "PRAGMA wal_checkpoint(TRUNCATE)"

class RekeyFaultTest {
    private val paths = mutableListOf<String>()
    private val rows = (1..20).map { "row-$it" }
    private val sqliteFailure = SQLiteException("Error code: 10, message: disk I/O error")

    private fun newPath(wal: Boolean = false): String = newTempDatabasePath().also { path ->
        paths += path
        withDb(path, rawKey(1)) { db ->
            if (wal) db.execSQL("PRAGMA journal_mode = WAL")
            db.createNotes(*rows.toTypedArray())
        }
    }

    @AfterTest
    fun cleanUp() {
        paths.forEach(::deleteDatabaseFiles)
        paths.clear()
    }

    private fun rekey(
        path: String,
        old: SealiteKey = rawKey(1),
        wrap: (index: Int, RawDb) -> RawDb = { _, db -> db },
    ) {
        var opened = 0
        Sealite.rekey(path, old, rawKey(2), SealiteConfig.DEFAULT) { name, flags, key, config ->
            wrap(opened++, OpenSequence.openVerified(name, flags, key, config))
        }
    }

    private fun failing(sql: String, nth: Int = 1, answer: () -> SQLiteStatement): (String) -> SQLiteStatement? {
        var seen = 0
        return { if (it == sql && ++seen == nth) answer() else null }
    }

    private fun assertUnder(path: String, old: Boolean) {
        val (opens, closed) = if (old) rawKey(1) to rawKey(2) else rawKey(2) to rawKey(1)
        assertEquals(rows, withDb(path, opens) { it.notes() })
        assertEquals(SealiteError.WRONG_KEY, openError(path, closed))
    }

    private fun failure(block: () -> Unit): SealiteException = assertFailsWith<SealiteException>(block = block)

    private fun assertFailedBeforeCommit(e: SealiteException, cause: Throwable?) {
        assertEquals(SealiteError.REKEY_FAILED, e.error)
        if (cause != null) assertSame(cause, e.cause)
    }

    @Test
    fun theOldKeyOpeningWithASqliteErrorIsRekeyFailed() {
        val path = newPath()
        var calls = 0
        val e =
            failure {
                Sealite.rekey(path, rawKey(1), rawKey(2), SealiteConfig.DEFAULT) { _, _, _, _ ->
                    calls++
                    throw sqliteFailure
                }
            }
        assertFailedBeforeCommit(e, sqliteFailure)
        assertEquals(1, calls)
        assertUnder(path, old = true)
    }

    @Test
    fun failureAcquiringTheExclusiveLockIsRekeyFailed() {
        val path = newPath()
        val e =
            failure {
                rekey(path) { _, db ->
                    FaultyRawDb(db, onPrepare = { sql -> if (sql == "BEGIN EXCLUSIVE") throw sqliteFailure else null })
                }
            }
        assertFailedBeforeCommit(e, sqliteFailure)
        assertUnder(path, old = true)
    }

    @Test
    fun theRekeyCallFailingIsRekeyFailed() {
        val path = newPath()
        val e =
            failure {
                rekey(path) { _, db -> FaultyRawDb(db, onRekey = { _, _ -> throw sqliteFailure }) }
            }
        assertFailedBeforeCommit(e, sqliteFailure)
        assertUnder(path, old = true)
    }

    @Test
    fun aCommitThatDidNotHappenIsRekeyFailed() {
        val path = newPath()
        // The pages never made it: rekey returned, but the transaction is still open.
        val e =
            failure {
                rekey(path) { _, db -> FaultyRawDb(db, onRekey = { _, _ -> }, stuckInTransaction = true) }
            }
        assertFailedBeforeCommit(e, null)
        assertTrue(e.message.orEmpty().contains("could not be committed"), e.message)
        assertUnder(path, old = true)
    }

    @Test
    fun checkpointFailingBeforeTheRekeyIsRekeyFailed() {
        val path = newPath(wal = true)
        val e =
            failure {
                rekey(path) { _, db ->
                    FaultyRawDb(db, onPrepare = failing(CHECKPOINT) { throw sqliteFailure })
                }
            }
        assertFailedBeforeCommit(e, sqliteFailure)
        assertUnder(path, old = true)
    }

    @Test
    fun checkpointBlockedBeforeTheRekeyIsRekeyFailed() {
        val path = newPath(wal = true)
        val e =
            failure {
                rekey(path) { _, db ->
                    FaultyRawDb(db, onPrepare = failing(CHECKPOINT) { FakeStatement("1", true) })
                }
            }
        assertEquals(SealiteError.REKEY_FAILED, e.error)
        assertTrue(e.cause is SQLiteException, "${e.cause}")
        assertUnder(path, old = true)
    }

    @Test
    fun checkpointFailingAfterTheCommitIsRekeyUnverified() {
        val path = newPath(wal = true)
        val e =
            failure {
                rekey(path) { _, db ->
                    FaultyRawDb(db, onPrepare = failing(CHECKPOINT, nth = 2) { throw sqliteFailure })
                }
            }
        assertEquals(SealiteError.REKEY_UNVERIFIED, e.error)
        assertSame(sqliteFailure, e.cause)
        assertUnder(path, old = false)
    }

    @Test
    fun checkpointBlockedAfterTheCommitIsRekeyUnverified() {
        val path = newPath(wal = true)
        val e =
            failure {
                rekey(path) { _, db ->
                    val blocked = failing(CHECKPOINT, nth = 2) { FakeStatement("1", true) }
                    FaultyRawDb(db, onPrepare = blocked)
                }
            }
        assertEquals(SealiteError.REKEY_UNVERIFIED, e.error)
        assertTrue(e.cause is SQLiteException, "${e.cause}")
        assertUnder(path, old = false)
    }

    @Test
    fun reopenFailingWithASqliteErrorIsRekeyUnverified() {
        val path = newPath()
        var opened = 0
        val e =
            failure {
                Sealite.rekey(path, rawKey(1), rawKey(2), SealiteConfig.DEFAULT) { name, flags, key, config ->
                    if (opened++ == 0) OpenSequence.openVerified(name, flags, key, config) else throw sqliteFailure
                }
            }
        assertEquals(SealiteError.REKEY_UNVERIFIED, e.error)
        assertSame(sqliteFailure, e.cause)
        assertUnder(path, old = false)
    }

    @Test
    fun reopenRefusedBySealiteIsRekeyUnverified() {
        val path = newPath()
        var opened = 0
        val refusal = SealiteException(SealiteError.WRONG_KEY, "refused")
        val e =
            failure {
                Sealite.rekey(path, rawKey(1), rawKey(2), SealiteConfig.DEFAULT) { name, flags, key, config ->
                    if (opened++ == 0) OpenSequence.openVerified(name, flags, key, config) else throw refusal
                }
            }
        assertEquals(SealiteError.REKEY_UNVERIFIED, e.error)
        assertSame(refusal, e.cause)
        assertUnder(path, old = false)
    }

    @Test
    fun aQuickCheckThatIsNotOkIsRekeyUnverified() {
        val path = newPath()
        val e =
            failure {
                rekey(path) { index, db ->
                    val bad = failing("PRAGMA quick_check") { FakeStatement("*** bad page", true) }
                    if (index == 0) db else FaultyRawDb(db, onPrepare = bad)
                }
            }
        assertEquals(SealiteError.REKEY_UNVERIFIED, e.error)
        assertTrue(e.message.orEmpty().contains("quick_check after rekey reported *** bad page"), e.message)
        assertUnder(path, old = false)
    }

    @Test
    fun aWrongOldKeyIsStillWrongKey() {
        val path = newPath()
        assertEquals(SealiteError.WRONG_KEY, failure { rekey(path, old = rawKey(9)) }.error)
        assertUnder(path, old = true)
    }
}
