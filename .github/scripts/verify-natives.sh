#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 pasichdev
#
# Static checks over the natives a build produced, run before anything is uploaded:
#   - the .sha256 next to every JVM library matches the library;
#   - glibc ceiling: the Linux libraries import no symbol newer than the Kotlin/Native sysroot allows;
#   - Android: every LOAD segment is aligned to 16 KiB (Android 15+ devices with 16 KiB pages);
#   - Apple: the macOS dylibs and static library target macOS 11.0, the iOS libraries iOS 14.0.
# The imports/exports allowlists are enforced by the Gradle verifyCrossExports* tasks.
#
# usage: verify-natives.sh <natives-dir>
# env:   LLVM_BIN   directory with llvm-objdump and llvm-readelf (the NDK's toolchain); PATH otherwise
#        GLIBC_MAX_X64, GLIBC_MAX_ARM64   ceilings (defaults: the sysroots' glibc, 2.19 and 2.25)
set -euo pipefail

dir="${1:?usage: verify-natives.sh <natives-dir>}"
dir="$(cd "$dir" && pwd)"
bin() { if [ -n "${LLVM_BIN:-}" ]; then echo "${LLVM_BIN}/$1"; else echo "$1"; fi; }
objdump="$(bin llvm-objdump)"
readelf="$(bin llvm-readelf)"
glibc_max_x64="${GLIBC_MAX_X64:-2.19}"
glibc_max_arm64="${GLIBC_MAX_ARM64:-2.25}"
min_macos="11.0"
fail=0
summary="${GITHUB_STEP_SUMMARY:-/dev/null}"

err() { echo "::error::$*"; fail=1; }

# true when $1 <= $2 as dotted versions
version_le() {
  awk -v a="$1" -v b="$2" 'BEGIN {
    na = split(a, x, "."); nb = split(b, y, ".");
    n = (na > nb) ? na : nb;
    for (i = 1; i <= n; i++) {
      p = x[i] + 0; q = y[i] + 0;
      if (p < q) exit 0;
      if (p > q) exit 1;
    }
    exit 0
  }'
}

{
  echo "### Native library checks"
  echo
  echo "| Library | Check | Result |"
  echo "|---|---|---|"
} >> "$summary"
row() { echo "| $1 | $2 | $3 |" >> "$summary"; }

# 1. checksums ------------------------------------------------------------------------------------
hasher() { if command -v sha256sum > /dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
count=0
while IFS= read -r lib; do
  expected="$(tr -d '[:space:]' < "${lib}.sha256")"
  actual="$(hasher "$lib")"
  rel="${lib#"$dir"/}"
  if [ "$expected" != "$actual" ]; then
    err "$rel: sha256 file says $expected, the library is $actual"
    row "$rel" "sha256" "MISMATCH"
  fi
  count=$((count + 1))
done < <(find "$dir/jvm/natives" -type f ! -name '*.sha256' | sort)
[ "$count" -ge 5 ] || err "expected the JVM libraries of 5 platforms, found $count"
echo "checksums: $count JVM libraries"

# 2. glibc ceiling ---------------------------------------------------------------------------------
for platform in linux_x64:"$glibc_max_x64" linux_arm64:"$glibc_max_arm64"; do
  name="${platform%%:*}"
  ceiling="${platform##*:}"
  lib="$dir/jvm/natives/$name/libsealite_jni.so"
  [ -f "$lib" ] || { err "missing $lib"; continue; }
  highest="$("$objdump" -T "$lib" | grep -o 'GLIBC_[0-9][0-9.]*' | sed 's/^GLIBC_//' | sort -u \
    | sort -t. -k1,1n -k2,2n -k3,3n | tail -n 1)"
  highest="${highest:-0}"
  if version_le "$highest" "$ceiling"; then
    echo "glibc: $name needs at most GLIBC_$highest (ceiling $ceiling)"
    row "jvm/natives/$name" "highest GLIBC symbol (ceiling $ceiling)" "$highest"
  else
    err "$name imports GLIBC_$highest, above the ceiling $ceiling"
    row "jvm/natives/$name" "highest GLIBC symbol (ceiling $ceiling)" "$highest, TOO NEW"
  fi
done

# 3. Android 16 KiB alignment ------------------------------------------------------------------------
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
  lib="$dir/android/jniLibs/$abi/libsealite_jni.so"
  [ -f "$lib" ] || { err "missing $lib"; continue; }
  worst=""
  loads=0
  while read -r align; do
    loads=$((loads + 1))
    if [ $((align)) -lt 16384 ]; then worst="$align"; fi
  done < <("$readelf" -lW "$lib" | awk '$1 == "LOAD" { print $NF }')
  if [ "$loads" -eq 0 ]; then
    err "$abi: no LOAD segment found"
  elif [ -n "$worst" ]; then
    err "$abi: a LOAD segment is aligned to $worst, below 0x4000 (16 KiB)"
    row "android/jniLibs/$abi" "LOAD alignment >= 16 KiB" "$worst, TOO SMALL"
  else
    echo "alignment: $abi $loads LOAD segments, all >= 16 KiB"
    row "android/jniLibs/$abi" "LOAD alignment >= 16 KiB" "ok ($loads segments)"
  fi
done

# 4. macOS minimum OS ------------------------------------------------------------------------------
if command -v vtool > /dev/null 2>&1; then
  for name in osx_arm64 osx_x64; do
    lib="$dir/jvm/natives/$name/libsealite_jni.dylib"
    [ -f "$lib" ] || { err "missing $lib"; continue; }
    minos="$(vtool -show-build "$lib" | awk '$1 == "minos" { print $2 }' | sort -u | tr '\n' ' ' | sed 's/ $//')"
    if [ "$minos" = "$min_macos" ]; then
      echo "minos: $name $minos"
      row "jvm/natives/$name" "macOS minimum OS" "$minos"
    else
      err "$name has minos '$minos', expected $min_macos"
      row "jvm/natives/$name" "macOS minimum OS" "'$minos', expected $min_macos"
    fi
  done
  if [ -d "$dir/apple" ]; then
    for platform in ios_arm64:14.0 ios_simulator_arm64:14.0 macos_arm64_static:11.0; do
      name="${platform%%:*}"
      expected="${platform##*:}"
      lib="$dir/apple/$name/libsealite.a"
      [ -f "$lib" ] || { err "missing $lib"; continue; }
      # otool prints the build-version command of every object in the archive.
      minos="$(otool -l "$lib" 2> /dev/null | awk '$1 == "minos" { print $2 }' | sort -u | tr '\n' ' ' | sed 's/ $//' || true)"
      if [ "$minos" = "$expected" ]; then
        echo "minos: apple/$name $minos"
        row "apple/$name/libsealite.a" "minimum OS of the objects" "$minos"
      else
        err "apple/$name/libsealite.a has minos '$minos', expected $expected"
        row "apple/$name/libsealite.a" "minimum OS of the objects" "'$minos', expected $expected"
      fi
    done
  fi
else
  echo "vtool not available: skipping the macOS minimum OS check"
  row "jvm/natives/osx_*" "macOS minimum OS" "skipped (no vtool)"
fi

# 5. Apple payload is complete --------------------------------------------------------------------
if [ -d "$dir/apple" ]; then
  for part in ios_arm64/libsealite.a ios_simulator_arm64/libsealite.a macos_arm64_static/libsealite.a \
    include/sqlite3.h include/sealite_rename.h; do
    [ -s "$dir/apple/$part" ] || err "missing apple/$part"
  done
fi

if [ "$fail" -ne 0 ]; then
  echo "native library checks failed"
  exit 1
fi
echo "native library checks passed"
