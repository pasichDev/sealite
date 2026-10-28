#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 pasichdev
#
# The staged Maven repository must hold the complete release: every Kotlin Multiplatform publication,
# including the three Apple klibs, which only a Mac can produce. A missing Apple target must stop the
# release, not silently ship a library without it.
#
# usage: verify-staging.sh <staging-dir> <version>
set -euo pipefail

staging="${1:?usage: verify-staging.sh <staging-dir> <version>}"
version="${2:?usage: verify-staging.sh <staging-dir> <version>}"
group_dir="$staging/io/github/pasichdev"
fail=0
err() { echo "::error::$*"; fail=1; }

need() {
  local artifact="$1" ext="$2"
  local file="$group_dir/$artifact/$version/$artifact-$version.$ext"
  [ -s "$file" ] || err "missing $file"
}

# Root publication (metadata), JVM and Android.
need sealite pom
need sealite module
need sealite-jvm jar
need sealite-jvm pom
need sealite-jvm module
need sealite-android aar
need sealite-android pom
need sealite-android module
# Apple klibs: iosArm64, iosSimulatorArm64, macosArm64.
for target in iosarm64 iossimulatorarm64 macosarm64; do
  need "sealite-$target" klib
  need "sealite-$target" pom
  need "sealite-$target" module
done
# Sources and documentation for every library artifact (a Central requirement).
for artifact in sealite sealite-jvm sealite-android sealite-iosarm64 sealite-iossimulatorarm64 sealite-macosarm64; do
  [ -s "$group_dir/$artifact/$version/$artifact-$version-sources.jar" ] || err "missing $artifact-$version-sources.jar"
  [ -s "$group_dir/$artifact/$version/$artifact-$version-javadoc.jar" ] || err "missing $artifact-$version-javadoc.jar"
done
# Gradle writes md5, sha1, sha256 and sha512 next to every published file; Central needs md5 and sha1.
unchecked="$(find "$staging" -type f ! -name 'maven-metadata.xml*' ! -name '*.md5' ! -name '*.sha1' ! -name '*.sha256' ! -name '*.sha512' \
  | while read -r f; do [ -s "$f.md5" ] && [ -s "$f.sha1" ] || echo "$f"; done)"
[ -z "$unchecked" ] || err "files without md5/sha1 checksums: $unchecked"

# The JVM jar carries the native libraries of every desktop platform.
jar="$group_dir/sealite-jvm/$version/sealite-jvm-$version.jar"
if [ -s "$jar" ]; then
  listing="$(unzip -Z1 "$jar")"
  for platform in osx_arm64 osx_x64 windows_x64 linux_x64 linux_arm64; do
    grep -q "^natives/$platform/" <<< "$listing" || err "sealite-jvm-$version.jar has no natives/$platform/"
  done
fi
# The AAR carries the four Android ABIs.
aar="$group_dir/sealite-android/$version/sealite-android-$version.aar"
if [ -s "$aar" ]; then
  listing="$(unzip -Z1 "$aar")"
  for abi in arm64-v8a armeabi-v7a x86_64 x86; do
    grep -q "^jni/$abi/libsealite_jni.so\$" <<< "$listing" || err "sealite-android-$version.aar has no jni/$abi/libsealite_jni.so"
  done
fi

if [ "$fail" -ne 0 ]; then
  echo "the staging repository is incomplete"
  exit 1
fi
echo "staging repository complete: $(find "$staging" -type f | wc -l | tr -d ' ') files"
