#!/bin/sh
# Builds testdata/plain_rich.db, a plaintext SQLite database with every schema object kind that
# Sealite.encryptPlaintext must carry over. Usage: plain_rich.sh <sqlcipher-or-sqlite3-cli> <out-file>
set -eu
CLI=$1
OUT=$2
rm -f "$OUT"
"$CLI" "$OUT" <<'SQL'
PRAGMA auto_vacuum=2;
PRAGMA page_size=4096;
CREATE TABLE item(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, qty INTEGER NOT NULL DEFAULT 0, data BLOB);
CREATE TABLE audit(id INTEGER PRIMARY KEY AUTOINCREMENT, msg TEXT);
CREATE INDEX item_name ON item(name);
CREATE UNIQUE INDEX item_name_qty ON item(name, qty);
CREATE VIEW item_big AS SELECT id, name FROM item WHERE qty > 5;
CREATE TRIGGER item_ai AFTER INSERT ON item BEGIN INSERT INTO audit(msg) VALUES('added ' || new.name); END;
INSERT INTO item(name, qty, data) VALUES('apple', 3, x'00ff10'),('Привіт 🌍', 10, zeroblob(0)),('cherry', 7, NULL);
PRAGMA user_version=5;
PRAGMA application_id=305419896;
SQL
