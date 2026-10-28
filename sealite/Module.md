# Module Sealite

An encrypted `androidx.sqlite.SQLiteDriver` for Kotlin Multiplatform, built on SQLCipher Community
Edition. Room KMP (2.8.x and 3.x) and plain `androidx.sqlite` users get an encrypted database on
Android, iOS, macOS and the desktop JVM with one line, and the files are byte-compatible with
`net.zetetic:sqlcipher-android`, SQLCipher for Apple platforms and the `sqlcipher` command line
tool.

## Quick start

```kotlin
val key = SealiteKey.raw(databaseKey)          // 32 bytes from your key store
val driver = SealiteDriver(key)
key.close()                                    // the driver keeps its own copy

val db = Room.databaseBuilder<AppDatabase>(name = path)
    .setDriver(driver)
    .build()

// shutdown: db.close(), then driver.close()
```

Without Room:

```kotlin
SealiteDriver(SealiteKey.raw(databaseKey)).use { driver ->
    driver.open(path).use { connection ->
        connection.prepare("SELECT count(*) FROM sqlite_schema").use { it.step() }
    }
}
```

Key material is only ever `ByteArray` or `CharArray`: there is no `String` API, because a `String`
can not be wiped. Pick a [io.github.pasichdev.sealite.SealiteKey.Companion.raw] key whenever the
application owns the key; a [io.github.pasichdev.sealite.SealiteKey.Companion.passphrase] key runs
PBKDF2 (256 000 iterations by default) on every connection open.

A wrong key is reported as [io.github.pasichdev.sealite.SealiteException], not as
`androidx.sqlite.SQLiteException`, so Room's corruption recovery (`allowDataLossOnRecovery`) never
deletes an encrypted database because of it. A damaged encrypted file reports the same error as a
wrong key, so never delete the database yourself on `WRONG_KEY`.

## Configuration and errors

A [io.github.pasichdev.sealite.SealiteConfig] is built with a builder (`SealiteConfig { pageSize = 8192 }`,
`SealiteConfig.builder()` from Java, `toBuilder()` to change a copy) and defaults to SQLCipher 4
(`SealiteConfig.DEFAULT`). It must describe the file; otherwise the open fails like a wrong key.
SQLCipher's memory sanitization (`cipher_memory_security`) is always on.

Sealite's refusals are [io.github.pasichdev.sealite.SealiteException]s; read the reason with
[io.github.pasichdev.sealite.Sealite.errorOf], which also looks through the causes Room wraps them
in, and never parse messages. The result is a [io.github.pasichdev.sealite.SealiteError]
(`WRONG_KEY`, `NOT_ENCRYPTED`, `NOT_KEYED`, `PROVIDER_MISSING`, `REKEY_FAILED`, `REKEY_UNVERIFIED`)
or `null` for any other failure. Plain SQLite failures (I/O, constraints, a damaged page under the
right key) stay `androidx.sqlite.SQLiteException`.

## Maintenance

- `Sealite.check` tells, without writing, whether a key opens a file: `Ok`, `Missing`,
  `NotEncrypted` or `WrongKey`.
- `Sealite.rekey` changes the key in place and needs the file to itself. It is atomic: after a crash
  the file is entirely under the old or entirely under the new key. Persist the new key as pending
  before the call, make it current only after the call returned, and on a failure with
  `REKEY_UNVERIFIED` keep both keys and use `check` to find out which one opens the file.
- `Sealite.encryptPlaintext` writes an encrypted copy of a plaintext database (schema, rows,
  sequences, `user_version`, `application_id`, `auto_vacuum`; not the journal mode or the page size)
  to a temporary file and moves it into place only after `integrity_check` passed. The plaintext
  file is left alone.
- SQLCipher files with a plaintext header (`cipher_plaintext_header_size`) are not supported.
- Deleting or overwriting does not erase flash storage: old ciphertext, or the plaintext of a
  migrated database, may remain recoverable from the device.

## Security model

What Sealite protects: the database file, its `-wal` and `-journal` files and SQLCipher's temporary
data (kept in memory, never written unencrypted) against anyone who can read the storage but does
not have the key. Every connection is keyed and verified before it is returned; a wrong key fails
with `SealiteError.WRONG_KEY`, and a build that accidentally linked a plain SQLite fails with
`SealiteError.PROVIDER_MISSING` instead of silently writing plaintext.

What it does not protect: a compromised or rooted device, a debugger, or a memory dump of the
running process. While a driver is open its copy of the key is in memory. Wiping is best effort, not
a guarantee: on the JVM and ART the garbage collector may have moved the array before it was wiped,
and the OS may have swapped or dumped a page. Sealite's native code wipes its own copies explicitly
(`SecureZeroMemory` on Windows, `memset_s` on Apple platforms, a `memset` through a volatile function
pointer with a compiler barrier on Linux and Android). Anything the application decrypts is
plaintext in the heap like any other data.

SQLCipher treats a passphrase of the form `x'<hex>'` as a raw key. Never build a passphrase from
untrusted input.

## API overview

Everything public lives in [io.github.pasichdev.sealite]: [io.github.pasichdev.sealite.SealiteDriver],
[io.github.pasichdev.sealite.SealiteKey], [io.github.pasichdev.sealite.SealiteConfig] and the
[io.github.pasichdev.sealite.SealiteError] and the [io.github.pasichdev.sealite.Sealite] maintenance
object (`check`, `rekey`, `encryptPlaintext`, `errorOf`, `versions`).

# Package io.github.pasichdev.sealite

The public API of the library: the driver, its keys and configuration, and the maintenance
functions. Everything else is internal and carries no compatibility promise.
