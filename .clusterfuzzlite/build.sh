#!/bin/bash -eu
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 pasichdev
#
# Builds the libFuzzer target fuzz/sealite_fuzz.c against the SQLCipher amalgamation. ClusterFuzzLite
# provides CC, CFLAGS (sanitizer and coverage instrumentation), LIB_FUZZING_ENGINE, SRC and OUT.
# SQLCipher's and OpenSSL's versions and sha256 are read from the Gradle build so there is one place to
# bump them. OpenSSL 3 is built from source, statically, with the same instrumentation as the harness: the
# base image ships OpenSSL 1.1, which SQLCipher's provider (EVP_MAC) does not build against. Only the sha256
# of the tarball is checked here, not the PGP signature the Gradle build also checks.

set -eu
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." 2>/dev/null && pwd)"
[ -f "$root/fuzz/sealite_fuzz.c" ] || root="$SRC/sealite"
build_file="$root/build-logic/src/main/kotlin/sealite.native.gradle.kts"
version="$(sed -n 's/^val sqlcipherVersion = "\(.*\)"/\1/p' "$build_file")"
sha256="$(sed -n 's/^val sqlcipherSha256 = "\(.*\)"/\1/p' "$build_file")"
cross_file="$root/build-logic/src/main/kotlin/io/github/pasichdev/sealite/build/SealiteCrossPlugin.kt"
ossl_version="$(sed -n 's/^ *val osslVersion = "\(.*\)"/\1/p' "$cross_file")"
ossl_sha256="$(sed -n 's/^ *val osslSha256 = "\(.*\)"/\1/p' "$cross_file")"
[ -n "$version" ] && [ -n "$sha256" ] && [ -n "$ossl_version" ] && [ -n "$ossl_sha256" ]

work="$(mktemp -d)"
curl -fsSL -o "$work/sqlcipher.tar.gz" "https://github.com/sqlcipher/sqlcipher/archive/refs/tags/v${version}.tar.gz"
echo "${sha256}  $work/sqlcipher.tar.gz" | sha256sum -c -
tar -xzf "$work/sqlcipher.tar.gz" -C "$work"

curl -fsSL -o "$work/openssl.tar.gz" \
  "https://github.com/openssl/openssl/releases/download/openssl-${ossl_version}/openssl-${ossl_version}.tar.gz"
echo "${ossl_sha256}  $work/openssl.tar.gz" | sha256sum -c -
tar -xzf "$work/openssl.tar.gz" -C "$work"
mkdir "$work/ossl"
(
  cd "$work/ossl"
  # Instrumented like the harness (CC and CFLAGS), static, only what SQLCipher's provider calls.
  CC="$CC" CFLAGS="$CFLAGS" "$work/openssl-${ossl_version}/Configure" no-shared no-tests no-apps no-docs \
    no-module no-engine no-legacy no-ssl no-tls no-dtls no-quic no-comp no-autoload-config > configure.log
  make -j"$(nproc)" build_libs > make.log
)
ossl_include=(-I "$work/openssl-${ossl_version}/include" -I "$work/ossl/include")

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

"$CC" $CFLAGS "${defines[@]}" -w -I "$work/bld" "${ossl_include[@]}" \
  "$root/fuzz/sealite_fuzz.c" "$work/bld/sqlite3.c" \
  $LIB_FUZZING_ENGINE "$work/ossl/libcrypto.a" -lpthread -ldl -lm -o "$OUT/sealite_fuzz"

cp "$root/fuzz/sealite_fuzz.dict" "$OUT/sealite_fuzz.dict"
printf '[libfuzzer]\nmax_len = 4096\ntimeout = 20\n' > "$OUT/sealite_fuzz.options"
(cd "$root/fuzz/corpus" && zip -q -j "$OUT/sealite_fuzz_seed_corpus.zip" ./*)
rm -rf "$work"
