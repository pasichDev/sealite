# Changelog

## [2.0.0](https://github.com/pasichDev/sealite/compare/v1.0.0...v2.0.0) (2026-10-28)


### ⚠ BREAKING CHANGES

* Sealite's refusals (wrong key, plaintext file, not keyed, missing encryption, rekey failures) are thrown as SealiteException carrying a SealiteError, no longer as androidx.sqlite.SQLiteException with a message token. Sealite.errorOf finds it in the cause chain by type. Room's corruption recovery deletes a database only for SQLiteException, so a wrong key can no longer make Room delete it.

### Added

* encrypted androidx.sqlite driver for Kotlin Multiplatform ([fcf8632](https://github.com/pasichDev/sealite/commit/fcf8632a764723cf47a5479be7ba0ad60ba6006a))
* release readiness for 1.0.0 ([#4](https://github.com/pasichDev/sealite/issues/4)) ([36ec9ad](https://github.com/pasichDev/sealite/commit/36ec9add741f5d4c2b7f5088b4af5f1db72831e4))

## 1.0.0 (2026-10-28)

First stable release. See the [release notes](https://github.com/pasichDev/sealite/releases/tag/v1.0.0)
for the full overview.

### Added

* `SealiteDriver`: an `androidx.sqlite.SQLiteDriver` that opens every connection encrypted with
  SQLCipher Community Edition 4.20.0 (SQLite 3.54.1); works with Room 2.8, Room 3 and plain
  `androidx.sqlite` on Android, iOS, macOS and the desktop JVM (macOS, Windows, Linux).
* `SealiteKey` (raw and passphrase keys, wiped on close), `SealiteConfig` (builder; SQLCipher 3 or 4
  compatibility, KDF iterations, page size).
* `SealiteException` with a typed `SealiteError`, and `Sealite.errorOf`; a wrong key never makes
  Room's corruption recovery delete the database.
* `Sealite.check`, `Sealite.rekey` (atomic and verified), `Sealite.encryptPlaintext` (a verified copy
  published without replacing anything) and `Sealite.versions`.
* File compatibility with `net.zetetic:sqlcipher-android` 4.x, SQLCipher for Apple platforms and the
  `sqlcipher` command line tool, verified by golden fixtures on every target.
