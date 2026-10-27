package io.github.pasichdev.sealite.sample

import io.github.pasichdev.sealite.Sealite
import io.github.pasichdev.sealite.SealiteDriver
import io.github.pasichdev.sealite.SealiteKey
import kotlinx.coroutines.Dispatchers

internal class SealiteServices(private val dir: String) : SampleServices {

    private val keyBytes: ByteArray = DemoKey.loadOrCreate(dir)
    private val driver: SealiteDriver
    override val dbPath: String = "$dir/notes.db"
    override val db: SampleDb

    init {
        val key = SealiteKey.raw(keyBytes)
        try {
            driver = SealiteDriver(key) // keeps its own copy of the key
        } finally {
            key.close()
        }
        db = sampleDbBuilder(dbPath)
            .setDriver(driver)
            .setQueryCoroutineContext(Dispatchers.Default)
            .build()
    }

    override val versionsLine: String = Sealite.versions.let {
        "sealite ${it.sealite} | SQLCipher ${it.sqlcipher} | SQLite ${it.sqlite} | ${it.cryptoProvider} ${it.cryptoProviderVersion}"
    }

    override suspend fun runSelfCheck(): List<CheckItem> = SelfCheck(dir, keyBytes).run()

    override fun close() {
        db.close() // the database first, then the driver, then our copy of the key
        driver.close()
        keyBytes.fill(0)
    }
}
