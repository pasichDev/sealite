# Changelog

## [1.0.1](https://github.com/pasichDev/sealite/compare/v1.0.0...v1.0.1) (2026-10-28)


### Fixed

* encryptPlaintext only read-locks the plaintext database ([#8](https://github.com/pasichDev/sealite/issues/8)) ([8fca122](https://github.com/pasichDev/sealite/commit/8fca122dc01bdc8ca3b00a37ff64727731ea4d99))

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
