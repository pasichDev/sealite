package io.github.pasichdev.sealite.sample

import androidx.sqlite.SQLiteException
import io.github.pasichdev.sealite.Sealite
import io.github.pasichdev.sealite.SealiteDriver
import io.github.pasichdev.sealite.SealiteError
import io.github.pasichdev.sealite.SealiteKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * Proves, on this device, that the library really encrypts: it creates its own throwaway
 * databases in [dir] (never the notes database) and inspects the bytes on disk.
 *
 * [rightKey] is borrowed, not copied or wiped here; every key made from it is closed.
 */
internal class SelfCheck(private val dir: String, private val rightKey: ByteArray) {

    private val dbPath = "$dir/selfcheck.db"
    private val copyPath = "$dir/selfcheck-rekey.db"

    suspend fun run(): List<CheckItem> = withContext(Dispatchers.Default) {
        Files.mkdirs(dir)
        cleanUp()
        val items = mutableListOf<CheckItem>()
        try {
            items += item("Library versions") { versions(it) }
            val marker = "SEALITE-MARKER-" + secureRandomBytes(8).joinToString("") { b ->
                (b.toInt() and 0xFF).toString(16).padStart(2, '0')
            }
            val phase = try {
                writeAndSnapshot(marker)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                null.also { failure ->
                    val why = listOf("could not create the test database: ${describe(e)}")
                    items += CheckItem("Database file is not plaintext SQLite", false, why)
                    items += CheckItem("Marker text is not in db / -wal bytes", false, why)
                    items += CheckItem("Wrong key is rejected, file unchanged", false, why)
                    items += CheckItem("Rekey round trip on a temp copy", false, why)
                }
            }
            if (phase != null) {
                items += item("Database file is not plaintext SQLite") { headerCheck(phase, it) }
                items += item("Marker text is not in db / -wal bytes") { markerCheck(phase, marker, it) }
                items += item("Wrong key is rejected, file unchanged") { wrongKeyCheck(it) }
                items += item("Rekey round trip on a temp copy") { rekeyRoundTrip(phase.rows, it) }
            }
        } finally {
            cleanUp()
        }
        items
    }

    // ---- checks ----------------------------------------------------------------------------

    private fun versions(details: MutableList<String>): Boolean {
        val v = Sealite.versions
        details += "sealite ${v.sealite}"
        details += "sqlite ${v.sqlite}"
        details += "sqlcipher ${v.sqlcipher}"
        details += "provider ${v.cryptoProvider} ${v.cryptoProviderVersion}"
        return v.sqlite.isNotBlank() && v.sqlcipher.isNotBlank() && v.cryptoProvider.isNotBlank()
    }

    private class Snapshot(
        val rows: Int,
        val mainWhileOpen: ByteArray,
        val walWhileOpen: ByteArray?,
        val mainAfterClose: ByteArray,
    )

    /**
     * Creates the database through Room and Sealite, inserts the marker, and captures the bytes
     * while Room is still open (so the -wal still holds the rows) and again after it closed.
     */
    private suspend fun writeAndSnapshot(marker: String): Snapshot {
        val key = SealiteKey.raw(rightKey)
        val driver = SealiteDriver(key)
        key.close()
        val room = sampleDbBuilder(dbPath).setDriver(driver).setQueryCoroutineContext(Dispatchers.Default).build()
        val rows = 20
        val whileOpenMain: ByteArray
        val whileOpenWal: ByteArray?
        try {
            val dao = room.noteDao()
            val now = Clock.System.now().toEpochMilliseconds()
            dao.insert(Note(text = marker, createdAtMillis = now))
            repeat(rows - 1) { dao.insert(Note(text = "$marker filler $it", createdAtMillis = now)) }
            whileOpenMain = Files.read(dbPath) ?: error("no database file after the insert")
            whileOpenWal = Files.read("$dbPath-wal")
        } finally {
            room.close()
            driver.close()
        }
        val afterClose = Files.read(dbPath) ?: error("no database file after close")
        return Snapshot(rows, whileOpenMain, whileOpenWal, afterClose)
    }

    private fun headerCheck(s: Snapshot, details: MutableList<String>): Boolean {
        val header = s.mainAfterClose.copyOf(minOf(16, s.mainAfterClose.size))
        details += "first 16 bytes: " + header.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        details += "plaintext SQLite header is 53 51 4c 69 74 65 20 66 6f 72 6d 61 74 20 33 00"
        details += "file size ${s.mainAfterClose.size} bytes"
        return !startsWith(s.mainAfterClose, PLAINTEXT_HEADER)
    }

    private fun markerCheck(s: Snapshot, marker: String, details: MutableList<String>): Boolean {
        val needle = marker.encodeToByteArray()
        val schema = "CREATE TABLE".encodeToByteArray()
        // Positive control: the scanner must see the marker when it is there.
        val control = ByteArray(40) + needle + ByteArray(40)
        val scannerWorks = indexOf(control, needle) == 40
        details += "scanner finds the marker in a known buffer: $scannerWorks"

        var clean = scannerWorks
        fun scan(name: String, bytes: ByteArray?) {
            if (bytes == null) {
                details += "$name: absent"
                return
            }
            val hitMarker = indexOf(bytes, needle) >= 0
            val hitSchema = indexOf(bytes, schema) >= 0
            details += "$name (${bytes.size} bytes): marker found=$hitMarker, schema text found=$hitSchema"
            if (hitMarker || hitSchema) clean = false
        }
        scan("db while open", s.mainWhileOpen)
        scan("-wal while open", s.walWhileOpen)
        scan("db after close", s.mainAfterClose)
        if (s.walWhileOpen == null || s.walWhileOpen.isEmpty()) {
            details += "note: no -wal data on this platform/mode, so only the main file was really scanned"
        }
        return clean
    }

    private fun wrongKeyCheck(details: MutableList<String>): Boolean {
        val before = Files.read(dbPath) ?: error("database vanished")
        val wrong = secureRandomBytes(DemoKey.SIZE)
        if (wrong.contentEquals(rightKey)) wrong[0] = (wrong[0].toInt() xor 1).toByte()
        var ok = true
        try {
            val wrongKey = SealiteKey.raw(wrong)
            val checked = try {
                Sealite.check(dbPath, wrongKey)
            } finally {
                wrongKey.close()
            }
            details += "Sealite.check(wrong key) = $checked"
            if (checked != Sealite.CheckResult.WrongKey) ok = false

            val driverKey = SealiteKey.raw(wrong)
            val driver = SealiteDriver(driverKey)
            driverKey.close()
            try {
                driver.open(dbPath).close()
                details += "SealiteDriver(wrong key).open: opened (unexpected)"
                ok = false
            } catch (e: SQLiteException) {
                val error = Sealite.errorOf(e)
                val rejected = error == SealiteError.WRONG_KEY
                details += "SealiteDriver(wrong key).open: SQLiteException, SealiteError=$error"
                if (!rejected) ok = false
            } finally {
                driver.close()
            }
        } finally {
            wrong.fill(0)
        }

        val after = Files.read(dbPath)
        val unchanged = after != null && after.contentEquals(before)
        details += "file unchanged after the attempts: $unchanged (${before.size} bytes)"
        if (!unchanged) ok = false

        val rightKeyObj = SealiteKey.raw(rightKey)
        val rightResult = try {
            Sealite.check(dbPath, rightKeyObj)
        } finally {
            rightKeyObj.close()
        }
        details += "Sealite.check(right key) = $rightResult"
        if (rightResult != Sealite.CheckResult.Ok) ok = false
        return ok
    }

    private fun rekeyRoundTrip(expectedRows: Int, details: MutableList<String>): Boolean {
        val original = Files.read(dbPath) ?: error("database vanished")
        Files.write(copyPath, original)
        val newBytes = secureRandomBytes(DemoKey.SIZE)
        try {
            fun key(bytes: ByteArray) = SealiteKey.raw(bytes)
            fun check(bytes: ByteArray): Sealite.CheckResult = key(bytes).use { Sealite.check(copyPath, it) }
            fun rekey(from: ByteArray, to: ByteArray) {
                key(from).use { oldKey -> key(to).use { newKey -> Sealite.rekey(copyPath, oldKey, newKey) } }
            }

            var ok = true
            rekey(rightKey, newBytes)
            val newOk = check(newBytes)
            val oldRejected = check(rightKey)
            val rowsNew = countNotes(copyPath, newBytes)
            details += "after rekey: new key = $newOk, old key = $oldRejected, rows with new key = $rowsNew (expected $expectedRows)"
            if (newOk != Sealite.CheckResult.Ok || oldRejected != Sealite.CheckResult.WrongKey || rowsNew != expectedRows) ok = false

            rekey(newBytes, rightKey)
            val backOld = check(rightKey)
            val backNew = check(newBytes)
            val rowsBack = countNotes(copyPath, rightKey)
            details += "after rekey back: original key = $backOld, other key = $backNew, rows = $rowsBack"
            if (backOld != Sealite.CheckResult.Ok || backNew != Sealite.CheckResult.WrongKey || rowsBack != expectedRows) ok = false
            return ok
        } finally {
            newBytes.fill(0)
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun countNotes(path: String, keyBytes: ByteArray): Int {
        val key = SealiteKey.raw(keyBytes)
        val driver = SealiteDriver(key)
        key.close()
        try {
            driver.open(path).use { connection ->
                connection.prepare("SELECT count(*) FROM Note").use { statement ->
                    check(statement.step()) { "no row from count(*)" }
                    return statement.getLong(0).toInt()
                }
            }
        } finally {
            driver.close()
        }
    }

    private inline fun item(name: String, block: (MutableList<String>) -> Boolean): CheckItem {
        val details = mutableListOf<String>()
        return try {
            CheckItem(name, block(details), details)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            details += "error: ${describe(e)}"
            CheckItem(name, false, details)
        }
    }

    private fun describe(e: Throwable) = "${e::class.simpleName}: ${e.message}"

    private fun cleanUp() {
        Files.deleteDatabase(dbPath)
        Files.deleteDatabase(copyPath)
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean =
        bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private companion object {
        val PLAINTEXT_HEADER = "SQLite format 3\u0000".encodeToByteArray()
    }
}
