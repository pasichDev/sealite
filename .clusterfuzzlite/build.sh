#!/bin/bash -eu
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 pasichdev
#
# Builds the libFuzzer target fuzz/sealite_fuzz.c against the SQLCipher amalgamation. ClusterFuzzLite
# provides CC, CFLAGS (sanitizer and coverage instrumentation), LIB_FUZZING_ENGINE, SRC and OUT.
# SQLCipher's version and sha256 are read from the Gradle build so there is one place to bump them.

set -eu
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." 2>/dev/null && pwd)"
[ -f "$root/fuzz/sealite_fuzz.c" ] || root="$SRC/sealite"
build_file="$root/build-logic/src/main/kotlin/sealite.native.gradle.kts"
version="$(sed -n 's/^val sqlcipherVersion = "\(.*\)"/\1/p' "$build_file")"
sha256="$(sed -n 's/^val sqlcipherSha256 = "\(.*\)"/\1/p' "$build_file")"
[ -n "$version" ] && [ -n "$sha256" ]

work="$(mktemp -d)"
curl -fsSL -o "$work/sqlcipher.tar.gz" "https://github.com/sqlcipher/sqlcipher/archive/refs/tags/v${version}.tar.gz"
echo "${sha256}  $work/sqlcipher.tar.gz" | sha256sum -c -
tar -xzf "$work/sqlcipher.tar.gz" -C "$work"
mkdir "$work/bld"
(
  cd "$work/bld"
  # The sanitizer flags are for the objects we compile below, not for the build tools of the amalgamation.
  CFLAGS= CXXFLAGS= LDFLAGS= "$work"/sqlcipher-*/configure --with-tempstore=yes CFLAGS=-DSQLITE_HAS_CODEC > configure.log
  CFLAGS= CXXFLAGS= LDFLAGS= make sqlite3.c > make.log
)

# The flags of the shipped library (NativeFlags.kt), without the progress handler switch (the harness
# uses it to stop runaway queries) and with the same threading mode.
defines=(
  -DSQLITE_HAS_CODEC -DSQLCIPHER_CRYPTO_OPENSSL -DSQLITE_TEMP_STORE=3 -DSQLITE_THREADSAFE=2
  -DSQLITE_EXTRA_INIT=sqlcipher_extra_init -DSQLITE_EXTRA_SHUTDOWN=sqlcipher_extra_shutdown
  -DSQLITE_OMIT_LOAD_EXTENSION -DSQLITE_DEFAULT_MEMSTATUS=0 -DSQLITE_SECURE_DELETE
  -DSQLITE_ENABLE_FTS3 -DSQLITE_ENABLE_FTS3_PARENTHESIS -DSQLITE_ENABLE_FTS4 -DSQLITE_ENABLE_FTS5
  -DSQLITE_ENABLE_JSON1 -DSQLITE_ENABLE_MATH_FUNCTIONS -DSQLITE_ENABLE_RTREE -DSQLITE_ENABLE_STAT4
  -DSQLITE_ENABLE_COLUMN_METADATA -DSQLITE_ENABLE_NORMALIZE -DSQLITE_OMIT_DEPRECATED
  -DSQLITE_OMIT_SHARED_CACHE -DSQLITE_HAVE_ISNAN -DHAVE_USLEEP=1
)

"$CC" $CFLAGS "${defines[@]}" -w -I "$work/bld" \
  "$root/fuzz/sealite_fuzz.c" "$work/bld/sqlite3.c" \
  $LIB_FUZZING_ENGINE -lcrypto -lpthread -ldl -lm -o "$OUT/sealite_fuzz"

cp "$root/fuzz/sealite_fuzz.dict" "$OUT/sealite_fuzz.dict"
printf '[libfuzzer]\nmax_len = 4096\ntimeout = 20\n' > "$OUT/sealite_fuzz.options"
(cd "$root/fuzz/corpus" && zip -q -j "$OUT/sealite_fuzz_seed_corpus.zip" ./*)
rm -rf "$work"
