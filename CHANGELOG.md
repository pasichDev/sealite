# Changelog

## [1.0.1](https://github.com/pasichDev/sealite/compare/v1.0.0...v1.0.1) (2026-11-10)


### Fixed

* check answers for a database that a crash left with a hot journal ([#28](https://github.com/pasichDev/sealite/issues/28)) ([3478817](https://github.com/pasichDev/sealite/commit/34788173abb0cdb416f59df2c4038dbc3ad31100))
* closing a connection checkpoints its wal again ([#27](https://github.com/pasichDev/sealite/issues/27)) ([6560154](https://github.com/pasichDev/sealite/commit/65601547b079968aee10d139c4273aa0128ccfc7))
* end a running statement before clearing its bindings ([#25](https://github.com/pasichDev/sealite/issues/25)) ([7c7391c](https://github.com/pasichDev/sealite/commit/7c7391c4c0f09cd4db5b0c780b6e0a5d63c65454))


### Performance

* **native:** build only the parts of openssl sqlcipher uses ([#18](https://github.com/pasichDev/sealite/issues/18)) ([551b9ca](https://github.com/pasichDev/sealite/commit/551b9ca37431eeddfc42d8bbe0ea193076e88d77))


### Documentation

* close the temporary key in every example, name the real temporary files ([#20](https://github.com/pasichDev/sealite/issues/20)) ([34b82ae](https://github.com/pasichDev/sealite/commit/34b82ae440ac87faa0ad119794bec9cdb29157cd))

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
