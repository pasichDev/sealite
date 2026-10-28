#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 pasichdev
#
# Adds the two components no dependency scanner can see to the CycloneDX SBOM of the Gradle
# dependencies: SQLCipher and OpenSSL are compiled into every native library, so they appear in no
# Gradle configuration. Their versions and the sha256 of the source archives are read from the build
# files that pin and verify them.
#
# usage: sbom-vendored.sh <sbom-in.json> <sbom-out.json>
set -euo pipefail

in="${1:?usage: sbom-vendored.sh <in> <out>}"
out="${2:?usage: sbom-vendored.sh <in> <out>}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
native="$root/build-logic/src/main/kotlin/sealite.native.gradle.kts"
cross="$root/build-logic/src/main/kotlin/io/github/pasichdev/sealite/build/SealiteCrossPlugin.kt"

pin() { sed -n "s/^[[:space:]]*val $2 = \"\\(.*\\)\"/\\1/p" "$1" | head -n 1; }
sqlcipher_version="$(pin "$native" sqlcipherVersion)"
sqlcipher_sha="$(pin "$native" sqlcipherSha256)"
openssl_version="$(pin "$cross" osslVersion)"
openssl_sha="$(pin "$cross" osslSha256)"
for v in sqlcipher_version sqlcipher_sha openssl_version openssl_sha; do
  [ -n "${!v}" ] || { echo "::error::could not read $v from the build files"; exit 1; }
done

mkdir -p "$(dirname "$out")"
jq \
  --arg sv "$sqlcipher_version" --arg ss "$sqlcipher_sha" \
  --arg ov "$openssl_version" --arg os "$openssl_sha" '
  .components = ((.components // []) + [
    {
      "type": "library",
      "bom-ref": ("pkg:github/sqlcipher/sqlcipher@v" + $sv),
      "name": "SQLCipher",
      "version": $sv,
      "description": "Compiled into every native library of Sealite (amalgamation, symbols renamed to sealite_*).",
      "purl": ("pkg:github/sqlcipher/sqlcipher@v" + $sv),
      "licenses": [{"license": {"id": "BSD-3-Clause"}}],
      "hashes": [{"alg": "SHA-256", "content": $ss}],
      "externalReferences": [{"type": "distribution", "url": ("https://github.com/sqlcipher/sqlcipher/archive/refs/tags/v" + $sv + ".tar.gz")}]
    },
    {
      "type": "library",
      "bom-ref": ("pkg:generic/openssl@" + $ov),
      "name": "OpenSSL",
      "version": $ov,
      "description": "libcrypto, linked statically into the Windows, Linux and Android native libraries (Apple targets use CommonCrypto).",
      "purl": ("pkg:generic/openssl@" + $ov),
      "licenses": [{"license": {"id": "Apache-2.0"}}],
      "hashes": [{"alg": "SHA-256", "content": $os}],
      "externalReferences": [{"type": "distribution", "url": ("https://github.com/openssl/openssl/releases/download/openssl-" + $ov + "/openssl-" + $ov + ".tar.gz")}]
    }
  ])' "$in" > "$out"
echo "SBOM: $(jq '.components | length' "$out") components, with SQLCipher $sqlcipher_version and OpenSSL $openssl_version"
