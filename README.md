# Sealite

[![CI](https://github.com/pasichdev/sealite/actions/workflows/ci.yml/badge.svg)](https://github.com/pasichdev/sealite/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.pasichdev/sealite)](https://central.sonatype.com/artifact/io.github.pasichdev/sealite)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

An encrypted `androidx.sqlite.SQLiteDriver` for Kotlin Multiplatform, built on
[SQLCipher](https://www.zetetic.net/sqlcipher/) Community Edition. Room KMP (2.8.x and 3.x) and
plain `androidx.sqlite` get an encrypted database on Android, iOS, macOS and the desktop JVM with
one line:

```kotlin
Room.databaseBuilder<AppDatabase>(name = path)
    .setDriver(SealiteDriver(SealiteKey.raw(databaseKey)))
    .build()
```

> **Not affiliated with Zetetic.** SQLCipher is a trademark of Zetetic LLC. The name is used here
> only to describe file-format compatibility. Sealite bundles the open-source SQLCipher Community
> Edition (BSD-3-Clause); see [NOTICE](NOTICE).

## Why

- **One driver everywhere.** The same code, the same file format and the same test suite on every
  target. No CocoaPods, no hand-linked SQLCipher on iOS, no separate Android library.
- **File compatible.** Same key, same file as `net.zetetic:sqlcipher-android` 4.x, SQLCipher for
  Apple platforms and the `sqlcipher` command line tool. Databases written by those libraries are
  checked into the test suite and opened on every target.
- **Fails closed.** A connection is returned only after the key was applied and the database
  verified. A wrong key, or a build that accidentally linked a plain SQLite, is an error with a typed
  `SealiteError`, never a silently unencrypted file.
- **No clashes.** Every SQLite and SQLCipher symbol in the native code is renamed (`sealite_*`),
  so the library can sit next to `androidx.sqlite:sqlite-bundled`, the iOS system SQLite or
  Firebase in the same process.
- **Keys are bytes, not strings.** `ByteArray` / `CharArray` only, wiped after use. There is no
  `String` key API.

## Supported platforms

Every target is built by CI; the last column says where its tests actually ran.

| Platform | Targets | Minimum | Crypto provider | Tests run on |
|---|---|---|---|---|
| Android | arm64-v8a, armeabi-v7a, x86_64, x86 | API 24 | OpenSSL 3 | arm64 emulator, API 34 (local); x86_64 API 34 and x86 API 24 emulators (CI) |
| iOS | arm64, simulator arm64 | iOS 14 | CommonCrypto | simulator arm64; the arm64 device library is built, not run |
| macOS native | arm64 | macOS 11 | CommonCrypto | macOS arm64 |
| JVM on macOS | arm64, x64 | macOS 11, JDK 17+ | CommonCrypto | arm64; the x64 library is built and checked, not run |
| JVM on Windows | x64 | Windows 10, JDK 17+ | OpenSSL 3 | CI runner (windows-latest) |
| JVM on Linux | x64, arm64 | glibc 2.19 (x64) / 2.25 (arm64), JDK 17+ | OpenSSL 3 | CI runners (ubuntu-latest, ubuntu-24.04-arm) |

A platform counts as supported once its tests pass; "CI" entries become supported when the CI run is
green. Android 15+ devices with 16 KB pages are supported (the libraries are linked with a 16 KB
page size). tvOS, watchOS and Linux/Windows native (non-JVM) targets are not offered.

## Install

```kotlin
// build.gradle.kts of a Kotlin Multiplatform module
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.pasichdev:sealite:1.0.0")
        }
    }
}
```

`androidx.sqlite:sqlite` is an `api` dependency, so `SQLiteDriver` and the connection types are
available without declaring it.

## Quick start

### With Room 2.8 (`androidx.room`)

```kotlin
// commonMain
fun buildDatabase(builder: RoomDatabase.Builder<AppDatabase>, key: ByteArray): AppDatabase {
    val sealiteKey = SealiteKey.raw(key)         // 32 bytes
    val driver = SealiteDriver(sealiteKey)       // keeps its own copy
    sealiteKey.close()
    key.fill(0)                                  // wipe your copy
    return builder.setDriver(driver).build()
}

// androidMain
val builder = Room.databaseBuilder<AppDatabase>(context, context.getDatabasePath("app.db").absolutePath)

// iosMain / desktopMain
val builder = Room.databaseBuilder<AppDatabase>(name = "/path/to/app.db")
```

### With Room 3 (`androidx.room3`)

The driver interface is the same; only the Room package changes.

```kotlin
import androidx.room3.Room

val db = Room.databaseBuilder<AppDatabase>(name = path)
    .setDriver(SealiteDriver(SealiteKey.raw(key)))
    .build()
```

### Without Room

```kotlin
SealiteDriver(SealiteKey.raw(key)).use { driver ->
    driver.open(path).use { connection ->
        connection.execSQL("CREATE TABLE IF NOT EXISTS note (id INTEGER PRIMARY KEY, body TEXT NOT NULL)")
        connection.prepare("SELECT count(*) FROM note").use { it.step(); println(it.getLong(0)) }
    }
}
```

Shut down in this order: close the `RoomDatabase`, then the `SealiteDriver` (which wipes its key).

> [!NOTE]
> **A wrong key never makes Room delete your database.** Room's corruption recovery
> (`allowDataLossOnRecovery(true)` in Room 3) deletes a database only when opening fails with
> `androidx.sqlite.SQLiteException`. Sealite reports a wrong key, a plaintext file and its other
> refusals as `SealiteException`, which is not a `SQLiteException`, so Room rethrows it instead of
> recovering by deletion. A test covers this for Room 2.8 and Room 3. An encrypted database is
> indistinguishable from random bytes, so a damaged file also reports `WRONG_KEY`: never answer it
> by deleting the database yourself.

## Key management

Use a **raw key**: 32 random bytes that your application owns. SQLCipher then runs no key
derivation, so opening a connection is fast (Room opens up to five per database), and a random key
has more entropy than any passphrase.

```kotlin
val key = SealiteKey.raw(bytes)                    // 32 bytes, used as is
val keyWithSalt = SealiteKey.raw(bytes, salt16)    // for files whose salt is not in the header
val fromPassword = SealiteKey.passphrase(chars)    // PBKDF2, 256 000 iterations by default
```

Where the 32 bytes come from:

- **Android.** Generate a random key once, encrypt it with an AES-GCM key held in the **Android
  Keystore** (non-exportable, hardware-backed where available), and store the ciphertext in app
  storage. Decrypt it at start-up.
- **iOS and macOS.** Store the random key as a **Keychain** item
  (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, or a stricter class if the app only runs in
  the foreground).
- **Desktop JVM.** Keep it in the OS credential store: macOS Keychain, Windows Credential Manager /
  DPAPI, or the Secret Service (libsecret) on Linux.
- **User-entered password.** Derive the key yourself with a slow KDF (Argon2id, scrypt) and pass the
  result to `SealiteKey.raw`, or use `SealiteKey.passphrase` and accept SQLCipher's PBKDF2 cost on
  every connection open.

Every factory copies its input. Wipe your array right after creating the key, and close the
`SealiteKey` once the driver has been constructed.

### `SealiteConfig`

```kotlin
val config = SealiteConfig {
    compatibility = SealiteConfig.Compatibility.SQLCIPHER_4 // or SQLCIPHER_3 for files written by SQLCipher 3
    kdfIterations = null                                    // PBKDF2 iterations for passphrase keys; null = default
    pageSize = null                                         // power of two, 512..65536; null = default
}
SealiteDriver(key, config)
// SealiteConfig.DEFAULT is the SQLCipher 4 defaults; SealiteDriver(key) uses it.
// Java: SealiteConfig.builder().compatibility(...).build()
// Changing a copy: config.toBuilder().pageSize(8192).build()
```

A `SealiteConfig` is built only through its builder, so options can be added in minor releases
without breaking binaries. The defaults match SQLCipher 4 (AES-256-CBC, HMAC-SHA512,
PBKDF2-HMAC-SHA512 with 256 000 iterations, 4096-byte pages). The configuration must describe the
file: a database written with other settings reports `SealiteError.WRONG_KEY`, like a wrong key.
`kdfIterations` only affects passphrase keys, and lowering it weakens them.

Sealite always turns on SQLCipher's memory sanitization (`cipher_memory_security`): freed memory
that held decrypted pages or keys is overwritten. There is no switch; the setting is library-wide in
SQLCipher and can not be turned off again once it is on.

### Errors: `SealiteException`, `SealiteError` and `Sealite.errorOf`

When Sealite refuses a database it throws `SealiteException`, whose `error` is a `SealiteError`.
Room may wrap it; `Sealite.errorOf(e)` finds it in the cause chain, by type (never by message
text). Ordinary SQLite failures (I/O, constraints, a damaged page under the right key, a closed
connection) stay `androidx.sqlite.SQLiteException`, as with any driver.

```kotlin
try {
    openDatabase()
} catch (e: SealiteException) {           // or catch (e: Exception) + Sealite.errorOf(e)
    when (e.error) {
        SealiteError.WRONG_KEY -> askForTheKeyAgain()
        SealiteError.NOT_ENCRYPTED -> offerMigration()
        else -> reportBug(e)               // NOT_KEYED, PROVIDER_MISSING, REKEY_*
    }
}
```

| `SealiteError` | Meaning |
|---|---|
| `WRONG_KEY` | The key or the `SealiteConfig` does not open the file (also a damaged file) |
| `NOT_ENCRYPTED` | The file is a plain SQLite database, or a SQLCipher file with a plaintext header |
| `NOT_KEYED` | SQLCipher reports the connection as not keyed (`cipher_status`) |
| `PROVIDER_MISSING` | The linked SQLite has no encryption: a build error, never a user error |
| `REKEY_FAILED` | `Sealite.rekey` failed before the new key was committed: the old key still works |
| `REKEY_UNVERIFIED` | `Sealite.rekey` committed the new key but could not verify it: keep both keys |

New values may be added in minor releases: keep an `else` branch when matching.

### Threading

A connection and its statements share one lock. Calls from different threads are serialized, so a
connection is safe to use from several threads, but they do not run in parallel (Room already gives
each connection to one coroutine at a time). `close()` on a connection interrupts a running query
(`sqlite3_interrupt`), waits for the call in progress, finalizes the statements still open and then
closes the handle; after that, every call on the connection or one of its statements throws
`SQLiteException` (`SQLITE_MISUSE`, "connection is closed") instead of touching freed memory.
Closing a statement is idempotent and waits for the connection's lock; it does not interrupt.

Text and blobs behave the same on every platform: `getText` on SQL `NULL` returns `""` (use
`isNull`), text with embedded `U+0000` round-trips intact, an unpaired UTF-16 surrogate bound with
`bindText` is stored as `U+FFFD`, and an empty `ByteArray` is stored as an empty BLOB (not `NULL`).
SQL that compiles to nothing (empty, whitespace or comments only) is rejected with `SQLITE_MISUSE`.

### Maintenance: `Sealite`

```kotlin
Sealite.check(path, key)                         // Ok | Missing | NotEncrypted | WrongKey, file untouched
Sealite.rekey(path, oldKey, newKey)              // change the key in place (close other connections first)
Sealite.encryptPlaintext(plain, encrypted, key)  // encrypted copy of an unencrypted database
Sealite.errorOf(throwable)                       // SealiteError? from any exception
Sealite.versions                                 // what is linked in, see below
```

Every function has an overload that takes a `SealiteConfig`.

`Sealite.check` lets the app branch before Room opens the file. It opens the file read-only and
never writes to it (SQLite may still create a `-shm` file next to a WAL database); an empty file is
`Ok` (a new database), a plaintext header is `NotEncrypted`, and anything the key does not open,
including random bytes, is `WrongKey`.

**`Sealite.rekey`** re-encrypts in place from `oldKey` to `newKey`. It needs the file to itself:
close the `RoomDatabase` first. The file is locked exclusively for the whole operation, so a
connection that holds a lock (an open transaction, a reader in WAL mode) makes it fail with
`SQLITE_BUSY`. A merely idle connection holds no lock in the rollback-journal mode and does not stop
it, so do not rely on that; it is then left with a stale key and its next read fails. A WAL is
checkpointed before the key changes. After the commit the file is reopened with `newKey` and checked
with `PRAGMA quick_check`.

SQLite's journal makes the change atomic: at any moment, crashes included, the file is entirely under
the old key or entirely under the new one. Store keys accordingly:

1. Persist `newKey` as *pending*, keeping `oldKey`.
2. Call `Sealite.rekey`.
3. Only when it returns, make `newKey` current and discard `oldKey`.
4. On start-up with a pending key: `Sealite.check` with `newKey`; if that is not `Ok`, `check` with
   `oldKey`.

Failures: `WRONG_KEY` if `oldKey` does not open the file; `REKEY_FAILED` (or a plain `SQLITE_BUSY`)
when it failed before the commit, so the file is still under `oldKey`; `REKEY_UNVERIFIED` when it
failed after the commit, so the file is most likely under `newKey`, and you keep **both** keys until
`Sealite.check` tells which one opens it.

**`Sealite.encryptPlaintext`** writes an encrypted copy of a plaintext database with
`sqlcipher_export`. What is copied: the schema (tables, indexes, views, triggers), all rows,
`AUTOINCREMENT` sequences, `user_version`, `application_id` and `auto_vacuum`. What is not: the
journal mode (Room sets its own) and the page size (it comes from the `SealiteConfig`). The copy is
built in a temporary file `<encrypted>.sealite-tmp` in one transaction, checked with
`PRAGMA integrity_check`, and only then moved into place atomically: the destination either does not
exist or is complete, and a failure removes the temporary file. It throws `IllegalArgumentException`
without touching anything if the source is missing or not a plaintext database, or the destination
or one of its `-journal`, `-wal` or `-shm` files already exists. The plaintext file is left as it is:
delete it and its sidecar files once the copy is in use.

Files written with SQLCipher's `cipher_plaintext_header_size` (a plaintext header on an encrypted
file) are **not supported**: they start with the SQLite magic and Sealite reports them as
`NOT_ENCRYPTED`.

**Deleting does not erase flash storage.** After `rekey`, the old pages are overwritten in place, but
flash media (phone storage, SSDs) may keep old ciphertext in blocks the file system no longer
references; after `encryptPlaintext` and deleting the plaintext file, the plaintext may remain
recoverable from the device. Neither operation is a secure erase.

## Migrating from `net.zetetic:sqlcipher-android`

The file format is the same, so there is nothing to convert:

| Before (`SupportOpenHelperFactory(bytes)`) | After |
|---|---|
| `bytes` is the ASCII of `x'<64 hex>'` (a raw key) | `SealiteKey.raw(the 32 key bytes)` |
| `bytes` is a passphrase | `SealiteKey.passphrase(chars)` (UTF-8, same bytes) |
| database created by SQLCipher 3.x | add `SealiteConfig { compatibility = SealiteConfig.Compatibility.SQLCIPHER_3 }` |
| plaintext database | `Sealite.encryptPlaintext(plainPath, encryptedPath, key)` |

Swap `SupportOpenHelperFactory` for `setDriver(SealiteDriver(key))` on the Room builder. Open the
existing file on a copy first and compare `Sealite.check`.

## Security model and limitations

**What it protects.** The database file, its `-wal` and `-journal` files and SQLCipher's temporary
data (kept in memory with `SQLITE_TEMP_STORE=3`, never written unencrypted) against anyone who can
read the storage without the key: a lost device, a backup, a copied file.

**What it does not protect.**

- A **compromised or rooted device**, a debugger, or a memory dump of the running process. While a
  driver is open its copy of the key is in memory, and everything the application reads is
  plaintext in its heap.
- **Memory wiping is best effort, never a guarantee.** On the JVM and ART the garbage collector may
  have moved or copied an array before it was wiped, the JIT may keep copies in registers or on the
  stack, and the OS may have swapped or dumped a page. Sealite's own native code wipes its copies of
  the key explicitly: `SecureZeroMemory` on Windows, `memset_s` on Apple platforms and a `memset`
  through a volatile function pointer with a compiler barrier on Linux and Android. SQLCipher's own
  buffers are sanitized by `cipher_memory_security`, which Sealite always enables.
- **Metadata** such as the file size and modification times.
- **Old data on flash storage.** Overwriting or deleting a file does not erase the blocks it used
  (see `Sealite.rekey` and `Sealite.encryptPlaintext` above).
- **Passphrase strings that look like `x'<hex>'`.** SQLCipher itself treats such a passphrase as a
  raw key. Never build a passphrase from untrusted input.
- The security of the key is the application's job: a key stored next to the database protects
  nothing.

Report vulnerabilities privately; see [SECURITY](.github/SECURITY.md).

## Desktop packaging

The JVM artifact carries the native library for each supported OS and architecture and loads it
in this order:

1. the absolute file named by the `sealite.library.path` system property;
2. the app-resources directory of Compose Desktop (`compose.application.resources.dir`), which is
   where `nativeDistributions` puts files you add under the resources root;
3. the copy inside the jar, extracted to a per-version cache directory (`~/Library/Caches`,
   `%LOCALAPPDATA%`, `$XDG_CACHE_HOME`) after its SHA-256 was checked. This is meant for
   development and tests.

A **notarized macOS app with the hardened runtime refuses to load an extracted, unsigned dylib.**
Ship `libsealite_jni.dylib` in the app resources so that packaging signs it with the rest of the
bundle, and notarize as usual. On the JVM, run with `--enable-native-access=ALL-UNNAMED` on JDK 24
and later to silence the native-access warning.

## FAQ

**Does it work with Room's connection pool and WAL?** Yes. Room owns the
connection pool (`hasConnectionPool` is `false`), exactly as with the bundled driver, and the WAL
files are encrypted.

**Can I open the file with the `sqlcipher` CLI or DB Browser?** Yes, with the same key and
`PRAGMA cipher_compatibility` as the file was written with.

**Why is opening slow with a passphrase?** PBKDF2 runs on every connection open, five times with
Room's pool. Use a raw key.

**Can I use it next to `sqlite-bundled`?** Yes; symbols are renamed and libraries are linked with
hidden visibility.

**What happens if the key is wrong?** A `SealiteException` with `error ==`
`SealiteError.WRONG_KEY`; the file is not modified.

**I need to know which SQLCipher I am running.** `Sealite.versions`.

## Versions

| Sealite | SQLCipher | SQLite | OpenSSL (Windows, Linux, Android) | Apple |
|---|---|---|---|---|
| 1.0.0 | 4.20.0 Community | 3.54.1 | 3.5.x | CommonCrypto |

A SQLCipher or OpenSSL security release is followed by a patch release of Sealite.

## Building and contributing

See [CONTRIBUTING](CONTRIBUTING.md). Changes are tracked in [CHANGELOG](CHANGELOG.md).

## License

Apache-2.0, see [LICENSE](LICENSE). Bundled components and their licenses are listed in
[NOTICE](NOTICE) and `licenses/`: SQLCipher Community Edition (BSD-3-Clause), SQLite (public
domain), OpenSSL (Apache-2.0) and parts ported from AndroidX SQLite (Apache-2.0).
