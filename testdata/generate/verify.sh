#!/bin/sh
# Opens every fixture with the sqlcipher CLI and checks rows, blob digest, user_version.
# Usage: verify.sh <sqlcipher-cli> <testdata-dir>
set -eu
CLI="$1"; DIR="$2"
RAW="x'000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f'"
PASS="correct horse battery staple"
fail=0
check() { # file, pre-sql
  out=$(printf '%s\n' "$2" \
    "SELECT id||'|'||title||'|'||length(body)||'|'||CASE WHEN length(body)=3 THEN hex(body) WHEN length(body)=4096 THEN '#' ELSE '' END FROM notes ORDER BY id;" \
    "SELECT 'user_version='||user_version FROM pragma_user_version;" \
    "SELECT 'blob2_sha=' || '' ;" | "$CLI" "$DIR/$1" 2>&1 | grep -v '^blob2_sha=' | grep -v '^ok$' | grep -v '^$') || true
  # compare the blob content separately
  sha=$(printf '%s\n' "$2" ".mode quote" "SELECT hex(body) FROM notes WHERE id=2;" | "$CLI" "$DIR/$1" 2>/dev/null | tail -1 | tr -d "'" | xxd -r -p | shasum -a 256 | cut -d' ' -f1)
  want_sha=$(awk 'BEGIN{for(i=0;i<4096;i++)printf "%02x",i%251}' | xxd -r -p | shasum -a 256 | cut -d' ' -f1)
  want="1|hello|3|00FF10
2|Привіт, світ 🌍|4096|#
3|empty body|0|
user_version=7"
  if [ "$out" = "$want" ] && [ "$sha" = "$want_sha" ]; then echo "OK   $1"; else echo "FAIL $1"; echo "$out"; fail=1; fi
}
check cli_raw_v4.db     "PRAGMA key = \"$RAW\";"
check cli_pass_v4.db    "PRAGMA key = '$PASS';"
check cli_pass_v3.db    "PRAGMA key = '$PASS'; PRAGMA cipher_compatibility = 3;"
check cli_raw_v4_wal.db "PRAGMA key = \"$RAW\";"
[ -f "$DIR/zetetic_raw_v4.db" ] && check zetetic_raw_v4.db "PRAGMA key = \"$RAW\";"
# negative checks
for f in cli_raw_v4.db cli_pass_v4.db cli_pass_v3.db cli_raw_v4_wal.db; do
  if printf "PRAGMA key = 'wrong';\nSELECT count(*) FROM sqlite_master;\n" | "$CLI" "$DIR/$f" >/dev/null 2>&1; then echo "FAIL wrong key opened $f"; fail=1; else echo "OK   wrong key rejected $f"; fi
done
if printf "PRAGMA key = '$PASS';\nSELECT count(*) FROM sqlite_master;\n" | "$CLI" "$DIR/cli_pass_v3.db" >/dev/null 2>&1; then echo "FAIL v3 opened with v4 defaults"; fail=1; else echo "OK   v3 file rejected under v4 defaults"; fi
# no plaintext header
for f in "$DIR"/*.db; do case "$(basename "$f")" in plain_*) continue;; esac; head -c 15 "$f" | grep -q 'SQLite format 3' && { echo "FAIL plaintext header $f"; fail=1; } || true; done
exit $fail
