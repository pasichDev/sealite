# Contributing to Sealite

Thank you for helping. Bug reports, platform test results and pull requests are welcome. By taking
part you agree to the [Code of Conduct](CODE_OF_CONDUCT.md). Security issues go through the private
channel described in [SECURITY](.github/SECURITY.md), not into public issues.

## Requirements

- JDK 21 (the build runs on 17+; CI runs the JVM tests on Temurin 17, 21 and 25).
- A **Mac** to build and test everything. The Apple targets and the Apple native libraries need
  Xcode; the macOS x64, Windows, Linux and Android libraries are cross-compiled from the Mac.
- `tclsh` (to build the SQLCipher amalgamation), `perl` and `make` (OpenSSL), `curl`; `gpg` is
  optional (it verifies the OpenSSL release signature).
- The Android SDK (`ANDROID_HOME`, or `sdk.dir` in a git-ignored `local.properties`) with platform 37
  and the NDK 28 or newer (`ANDROID_NDK_HOME`, or any `ndk/28+` under the SDK).
- Network on the first build: Kotlin/Native downloads its LLVM and the Windows/Linux sysroots
  (about 2.5 GB into `~/.konan`, or `KONAN_DATA_DIR`) through `:internal:konan-sysroots`, and Gradle
  downloads the pinned SQLCipher and OpenSSL sources.
- `clang-format` 22.1.8 for the C glue (`brew install clang-format`, or `pipx install clang-format==22.1.8`).

Gradle is provided by the wrapper.

## How the natives are built

Gradle tasks in `build-logic/` download pinned, SHA-256 verified sources (SQLCipher, OpenSSL), build
the SQLCipher amalgamation, generate `sealite_rename.h` (every `sqlite3_*` / `sqlcipher_*` symbol
becomes `sealite_*`) and compile the C glue in `sealite/src/native/`:

- JVM and Android: a JNI library per OS and architecture, packaged as jar resources or `jniLibs`.
- Apple native targets: a static `libsealite.a`, embedded in the cinterop klib.
- Crypto: CommonCrypto on Apple platforms, a static OpenSSL `libcrypto` everywhere else.

Verification tasks fail the build if a library exports anything but its JNI entry points or defines
a symbol without the `sealite_` prefix.

```sh
./gradlew :sealite:buildAllCrossNatives   # Windows, Linux and the four Android ABIs
./gradlew :sealite:collectNativesForCi    # every library under sealite/build/natives-out
```

CI builds the natives once on macOS and every other job reuses them: pass
`-Psealite.prebuiltNatives=<dir>` (the layout of `natives-out`) and no native code is compiled:

```
<dir>/jvm/natives/<os>_<arch>/       osx_arm64, osx_x64, windows_x64, linux_x64, linux_arm64 (+ .sha256)
<dir>/android/jniLibs/<abi>/         arm64-v8a, armeabi-v7a, x86_64, x86
<dir>/apple/<target>/libsealite.a    ios_arm64, ios_simulator_arm64, macos_arm64_static
<dir>/apple/include/                 sqlite3.h and sealite_rename.h, as the libraries were built with
```

On a host that is not a Mac the natives can not be built (the build needs Xcode's clang and SDK) and
the Apple targets are disabled, so there `-Psealite.prebuiltNatives` is required; without it the build
stops with a message saying so. Take the directory from the `natives` artifact of a CI run, or from
`./gradlew :sealite:collectNativesForCi` on a Mac.

## Tests

The shared suite in `sealite/src/commonTest` runs on every target; the golden databases in
`testdata/` (see `testdata/README.md`) are embedded as Kotlin constants, so no resources are
needed on Kotlin/Native.

| Platform | Command |
|---|---|
| JVM, Apple native (on a Mac) | `./gradlew :sealite:allTests` |
| JVM only | `./gradlew :sealite:jvmTest` |
| Android | `ANDROID_SERIAL=<serial> ./gradlew :sealite:connectedAndroidDeviceTest` (an emulator or device; the variable keeps the run off other attached devices) |
| JVM on Windows / Linux | `./gradlew :sealite:jvmTest -Psealite.prebuiltNatives=<dir>` on that OS |

Each JVM test is limited to five minutes (JUnit's `timeout.default`, run on a separate thread so a
test stuck in native code fails instead of hanging the build).

A platform without passing tests is not a supported platform.

### Coverage

The Kotlin library code must stay at **100 % line and branch coverage** of the JVM target.
[Kover](https://github.com/Kotlin/kotlinx-kover) measures it, and `./gradlew build` (through
`check`) runs `koverVerify`, which fails below 100 %. The HTML and XML reports are in
`sealite/build/reports/kover/`.

- The measured run is `:sealite:jvmTest`, which executes the shared `commonTest` suite and the
  JVM-only tests (the native library loader, the JNI binding). Put new tests in `commonTest` unless
  the code under test exists on the JVM only.
- Kotlin/Native and Android code is not measured: Kover reads JVM bytecode and has no Kotlin/Native
  support. The Apple targets still run the whole `commonTest` suite, and Android runs it on a device.
- Code no test can reach (an I/O fault between two steps, a condition SQLite never produces, the
  Windows-only branch on a Unix host) is marked `@ExcludeFromCoverage`, with a comment saying why.
  Do not add the annotation to make a gate pass; write the test, or explain in the pull request
  why none can exist.
- `LoaderEnvironmentTest` needs different environment variables per run, so it is not part of
  `jvmTest`: the `jvmLoaderEnvTest*` tasks run it (they are part of `check`).
- The C glue has an opt-in report that is not part of `check`: `./gradlew :sealite:cCoverageReport`
  (an arm64 Mac) builds the JNI library with clang's coverage instrumentation, runs the JVM tests
  against it and writes line and branch coverage of `sealite_jni.c` and `sealite_key.c` (not the
  SQLCipher amalgamation) to `sealite/build/reports/c-coverage/`.

## Before you open a pull request

```sh
./gradlew spotlessApply     # ktlint for Kotlin and Gradle scripts, clang-format for C
./gradlew build             # tests, 100 % coverage gate, spotlessCheck, detekt, API check, Dokka
```

- If the public API changed on purpose, run `./gradlew :sealite:updateLegacyAbi` and commit the new
  `sealite/api/` dump. An unexpected API diff is a bug.
- Keys are `ByteArray` / `CharArray` only. Code that copies, holds or wipes key material (the
  `SealiteKey`, `OpenSequence` and native key files) gets extra scrutiny in review.
- New source files start with `// SPDX-License-Identifier: Apache-2.0` and the copyright line.

## Commits and pull requests

Commits and PR titles follow [Conventional Commits](https://www.conventionalcommits.org/):
`feat:`, `fix:`, `perf:`, `refactor:`, `docs:`, `test:`, `build:`, `ci:`, `chore:`, with an optional
scope, for example `fix(native): wipe the key buffer on every path`. Use `!` or a `BREAKING CHANGE:`
footer for incompatible changes. Pull requests are squash-merged, so the PR title is what lands in
the history and drives the version bump and the changelog; a CI check enforces its format.

## Continuous integration

Workflows live in `.github/workflows/`; the ones starting with `_` are reusable.

```
ci.yml  (pull requests, main)
 |- hygiene   forbidden wording, private paths, SPDX headers
 |- lint      Spotless (ktlint, clang-format), detekt; no natives needed
 |- natives   _natives.yml: macOS arm64, builds every native library once, then checks them
 |               (import allowlists, glibc ceiling, 16 KiB alignment, macOS minimum OS, checksums)
 |   `- test  _test.yml: runs against exactly those libraries
 |        |- apple    macOS arm64: check = macOS and iOS-simulator tests, JVM tests, 100 % Kover gate, ABI, detekt, Dokka
 |        |- jvm      linux-x64 (JDK 17, 21; 25 on main), linux-arm64, windows-x64, macos-x64 (the only run of osx_x64)
 |        |- android  emulators: API 24 x86, API 35 x86_64, API 35 with 16 KiB pages (AVD snapshots cached)
 |        `- sample   the desktop and Android sample apps assemble
 `- ci-ok     fails unless every job above succeeded
pr-title.yml  the pull request title is a Conventional Commit
codeql.yml    CodeQL for the C glue
security.yml  zizmor, the linter for the workflows themselves
scorecard.yml OpenSSF Scorecard
release-please.yml, release.yml   see Releases
```

- **`ci-ok` is the only check branch protection needs** (plus `semantic` from `pr-title.yml`). It
  reports on every pull request, whatever the matrix looks like.
- To rerun, use "Re-run failed jobs" on the run. The jobs that download the natives take them from the
  artifact of that run, so a rerun tests the same libraries. Nothing is retried automatically except the
  boot of an Android emulator, once; a failing test is never rerun.
- A hung test fails after five minutes. If a job hangs anyway, it prints thread dumps of every JVM
  (including the Gradle test workers) before its step timeout, and uploads them with the test reports as
  the `reports-*` artifacts of the run.
- All actions are pinned by commit SHA (Dependabot keeps them current, in one grouped pull request),
  every job asks for the least permissions it needs, and `zizmor` checks the workflows.

## Releases

release-please keeps a release pull request open on `main`. It runs with a token of its own
(`RELEASE_PLEASE_TOKEN`), so CI runs on that pull request and the tag it pushes starts `release.yml`.
Merging the release pull request bumps `version` in `gradle.properties`, updates `CHANGELOG.md` and
tags `vX.Y.Z`; then `release.yml`:

1. checks that the tag is the version in `gradle.properties` and that its commit is on `main`;
2. builds every native library from the tag (no caches) and runs the full test matrix against them;
3. builds the Maven repository once, into a local directory, attests the native libraries and the
   library files (build provenance) and the SBOM, and records their checksums;
4. checks that the repository is complete (all Apple klibs included) and that a separate consumer
   project, resolving the library only from that directory, opens an encrypted database;
5. after the approval of the `release` environment: signs the staged files with the release key and
   uploads exactly those files to the Central Portal as one deployment. Central validates it, and
   **nothing is released yet**: the owner presses Publish in the Portal;
6. completes the GitHub release with the changelog section, `SHA256SUMS`, the SBOM and the links to the
   attestations, and only then publishes it. release-please creates the release as a draft
   (`"draft": true`) and pushes the tag right away (`"force-tag-creation": true`, because GitHub does
   not create the tag of a draft), so the release is invisible until every step above succeeded; a
   failed run leaves a draft. A download can be checked with
   `gh attestation verify <file> --repo pasichdev/sealite`;
7. builds the API documentation (`./gradlew :sealite:dokkaGeneratePublicationHtml`) and deploys it to
   GitHub Pages, <https://pasichdev.github.io/sealite/>, once the release is published.

The Portal upload is a script (`.github/scripts/central-upload.sh`) and not the Gradle plugin's
`publishToMavenCentral`, because the plugin uploads only what its own Gradle run built, and a release
must publish the files that were tested and attested, not a rebuild of them.

Never edit `CHANGELOG.md` by hand: release-please writes every version section from the commit
history, and a hand-written section for the same version would appear twice. A curated overview of a
release goes in `.github/release-notes/<version>.md`; the release workflow places it above the
generated entry in the GitHub release.

### Repository setup (maintainers)

- Secret `RELEASE_PLEASE_TOKEN`: a fine-grained personal access token for this repository with
  Contents, Pull requests and Workflows read and write. `release-please.yml` fails with a message
  without it; there is no fallback to the default token.
- Environment `release` with required reviewers, restricted to `v*` tags, holding the secrets
  `ORG_GRADLE_PROJECT_mavenCentralUsername`, `ORG_GRADLE_PROJECT_mavenCentralPassword` (the Portal user
  token), `ORG_GRADLE_PROJECT_signingInMemoryKey` and `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`.
- GitHub Pages enabled with the source "GitHub Actions" (Settings, Pages), and the site URL
  `https://pasichdev.github.io/sealite/` as the repository website. The `docs` job deploys through the
  `github-pages` environment; if that environment restricts branches or tags, allow `v*` tags.
- Branch protection (or a ruleset) on `main`: require `ci-ok` and `semantic`.
