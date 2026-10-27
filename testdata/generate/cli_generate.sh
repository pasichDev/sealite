#!/bin/sh
# Generates the sqlcipher-CLI fixtures into the output directory.
# Usage: cli_generate.sh <path-to-sqlcipher-4.20.0-cli> <output-dir>
set -eu
CLI="$1"
OUT="$2"
mkdir -p "$OUT"

RAWKEY="x'000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f'"
PASS="correct horse battery staple"

# 4096-byte blob, byte i = i % 251
BLOB=$(awk 'BEGIN { for (i = 0; i < 4096; i++) printf "%02x", i % 251 }')

SCHEMA_ROWS="
CREATE TABLE notes(id INTEGER PRIMARY KEY, title TEXT, body BLOB);
INSERT INTO notes VALUES (1, 'hello', X'00FF10');
INSERT INTO notes VALUES (2, 'Привіт, світ 🌍', X'$BLOB');
INSERT INTO notes VALUES (3, 'empty body', X'');
PRAGMA user_version = 7;
"

gen() { # file, prelude-sql
  f="$OUT/$1"
  rm -f "$f" "$f-wal" "$f-shm" "$f-journal"
  printf '%s\n%s\n' "$2" "$SCHEMA_ROWS" | "$CLI" "$f" >/dev/null
}

gen cli_raw_v4.db  "PRAGMA key = \"$RAWKEY\";"
gen cli_pass_v4.db "PRAGMA key = '$PASS';"
gen cli_pass_v3.db "PRAGMA key = '$PASS'; PRAGMA cipher_compatibility = 3;"

# WAL: switch to WAL before writing, then checkpoint and close; the file stays in WAL mode.
f="$OUT/cli_raw_v4_wal.db"
rm -f "$f" "$f-wal" "$f-shm"
printf '%s\n' "PRAGMA key = \"$RAWKEY\";" "PRAGMA journal_mode = WAL;" "$SCHEMA_ROWS" \
  "PRAGMA wal_checkpoint(TRUNCATE);" | "$CLI" "$f" >/dev/null
ls "$f-wal" "$f-shm" 2>/dev/null && { echo "leftover wal/shm" >&2; exit 1; } || true
