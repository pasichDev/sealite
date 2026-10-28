#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 pasichdev
#
# Signs the staged Maven repository and uploads it to the Central Portal as one deployment, then waits
# for the Portal's validation. It never releases: the deployment is left in VALIDATED for the owner
# to publish in the Portal UI.
#
# Why this is a script and not Gradle: the Vanniktech plugin (0.37) uploads only what the same Gradle
# invocation built; it has no task that takes a prepared repository. The release builds the
# repository once (and tests and attests exactly that), so the upload must take the files as they are:
#   1. the staged files are checked against the checksums recorded when they were attested;
#   2. each gets a detached .asc signature (the only thing added; the staged files are not touched);
#   3. maven-metadata.xml is left out (a deployment bundle holds the version's files only);
#   4. the bundle is POSTed to /api/v1/publisher/upload with publishingType=USER_MANAGED.
#
# env (required):  STAGING_DIR        the staged repository (io/github/... at its root)
#                  VERSION            the release version, for the deployment name
#                  SHA256SUMS_FILE    "<sha256>  <path relative to STAGING_DIR>" lines made at staging
#                  CENTRAL_USERNAME, CENTRAL_PASSWORD   Portal user token
#                  SIGNING_KEY        ASCII-armored secret key, or its base64 body without headers and
#                                     newlines (the form the Vanniktech plugin takes)
#                  SIGNING_KEY_PASSWORD
# env (optional):  CENTRAL_API        the Portal publisher API (default: central.sonatype.com), for tests
#                  OUT_DIR            where the bundle goes (default: ./central-bundle)
#                  VALIDATION_TIMEOUT seconds to wait for validation (default 1200)
set -euo pipefail

: "${STAGING_DIR:?}" "${VERSION:?}" "${SHA256SUMS_FILE:?}"
: "${CENTRAL_USERNAME:?}" "${CENTRAL_PASSWORD:?}" "${SIGNING_KEY:?}" "${SIGNING_KEY_PASSWORD:?}"
out="${OUT_DIR:-central-bundle}"
timeout_s="${VALIDATION_TIMEOUT:-1200}"
api="${CENTRAL_API:-https://central.sonatype.com/api/v1/publisher}" # CENTRAL_API: for tests against a stand-in server

staging="$(cd "$STAGING_DIR" && pwd)"
mkdir -p "$out"
out="$(cd "$out" && pwd)"

# 1. exactly what was attested -------------------------------------------------------------------------
echo "Checking the staged files against $SHA256SUMS_FILE"
actual="$(cd "$staging" && find . -type f ! -name '*.asc' | sed 's|^\./||' | LC_ALL=C sort)"
recorded="$(sed 's/^[0-9a-f]*  //' "$SHA256SUMS_FILE" | LC_ALL=C sort)"
if [ "$actual" != "$recorded" ]; then
  echo "::error::the staged repository differs from the recorded file list"
  diff <(echo "$recorded") <(echo "$actual") || true
  exit 1
fi
(cd "$staging" && if command -v sha256sum > /dev/null 2>&1; then sha256sum -c "$SHA256SUMS_FILE"; else shasum -a 256 -c "$SHA256SUMS_FILE"; fi) > /dev/null

# 2. signatures ------------------------------------------------------------------------------------
export GNUPGHOME
GNUPGHOME="$(mktemp -d)"
chmod 700 "$GNUPGHOME"
trap 'gpgconf --kill gpg-agent > /dev/null 2>&1 || true; rm -rf "$GNUPGHOME"' EXIT
printf 'allow-loopback-pinentry\n' > "$GNUPGHOME/gpg-agent.conf"

key_file="$GNUPGHOME/signing-key.asc"
case "$SIGNING_KEY" in
  -----BEGIN*) printf '%s\n' "$SIGNING_KEY" > "$key_file" ;;
  *)
    {
      echo '-----BEGIN PGP PRIVATE KEY BLOCK-----'
      echo
      printf '%s' "$SIGNING_KEY" | tr -d '[:space:]' | fold -w 64
      echo
      echo '-----END PGP PRIVATE KEY BLOCK-----'
    } > "$key_file"
    ;;
esac
gpg --batch --quiet --import "$key_file"
rm -f "$key_file"
fingerprint="$(gpg --batch --list-secret-keys --with-colons | awk -F: '$1 == "fpr" { print $10; exit }')"
[ -n "$fingerprint" ] || { echo "::error::no secret key could be imported from SIGNING_KEY"; exit 1; }
echo "Signing with key ${fingerprint: -16}"

bundle_root="$out/root"
rm -rf "$bundle_root" "$out/bundle.zip"
mkdir -p "$bundle_root"
# Everything but maven-metadata.xml and its checksums.
cp -R "$staging/." "$bundle_root/"
find "$bundle_root" -type f -name 'maven-metadata.xml*' -delete

signed=0
while IFS= read -r -d '' file; do
  case "$file" in
    *.md5 | *.sha1 | *.sha256 | *.sha512 | *.asc) continue ;;
  esac
  printf '%s' "$SIGNING_KEY_PASSWORD" | gpg --batch --yes --quiet --pinentry-mode loopback --passphrase-fd 0 \
    --local-user "$fingerprint" --armor --detach-sign --output "$file.asc" "$file"
  signed=$((signed + 1))
done < <(find "$bundle_root" -type f -print0)
echo "Signed $signed files"

# 3. bundle ----------------------------------------------------------------------------------------
(cd "$bundle_root" && zip -q -r -X -D "$out/bundle.zip" .)
echo "Bundle: $(du -h "$out/bundle.zip" | cut -f1), $(unzip -Z1 "$out/bundle.zip" | wc -l | tr -d ' ') entries"

# 4. upload and wait for validation ----------------------------------------------------------------
token="$(printf '%s:%s' "$CENTRAL_USERNAME" "$CENTRAL_PASSWORD" | base64 | tr -d '\n')"
echo "::add-mask::$token"
name="io.github.pasichdev:sealite:$VERSION"
response="$out/upload-response.txt"
code="$(curl --silent --show-error --output "$response" --write-out '%{http_code}' --request POST \
  --header "Authorization: Bearer $token" --form "bundle=@$out/bundle.zip" \
  "$api/upload?name=$name&publishingType=USER_MANAGED")"
if [ "$code" != "201" ]; then
  echo "::error::the Portal answered HTTP $code to the upload"
  cat "$response"
  exit 1
fi
id="$(tr -d '[:space:]' < "$response")"
echo "Deployment id: $id"
echo "deployment_id=$id" >> "${GITHUB_OUTPUT:-/dev/null}"

deadline=$((SECONDS + timeout_s))
state=""
while [ "$SECONDS" -lt "$deadline" ]; do
  status="$(curl --silent --show-error --fail --request POST --header "Authorization: Bearer $token" "$api/status?id=$id")"
  state="$(printf '%s' "$status" | jq -r '.deploymentState')"
  echo "state: $state"
  case "$state" in
    VALIDATED | PUBLISHING | PUBLISHED) break ;;
    FAILED)
      echo "::error::Central validation failed"
      printf '%s' "$status" | jq .
      exit 1
      ;;
  esac
  sleep 15
done
case "$state" in
  VALIDATED | PUBLISHING | PUBLISHED) ;;
  *) echo "::error::the deployment is still '$state' after ${timeout_s}s"; exit 1 ;;
esac

{
  echo "### Maven Central"
  echo
  echo "Deployment \`$id\` for \`$name\` passed validation (state: $state)."
  echo
  if [ "$state" = "VALIDATED" ]; then
    echo "It is **not released**. Review it and press Publish at https://central.sonatype.com/publishing/deployments (or drop it there)."
  fi
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
