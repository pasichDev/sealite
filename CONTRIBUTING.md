# Contributing to Sealite

Thank you for helping. Bug reports, platform test results and pull requests are welcome. By taking
part you agree to the [Code of Conduct](CODE_OF_CONDUCT.md). Security issues go through the private
channel described in [SECURITY](.github/SECURITY.md), not into public issues.

## Requirements

- JDK 21 (the build runs on 17+; CI uses Temurin 21).
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

CI builds the natives once on macOS and the other jobs reuse them: pass
`-Psealite.prebuiltNatives=<dir>` (the layout of `natives-out`: `jvm/natives/<os>_<arch>/` and
`android/jniLibs/<abi>/`) and no native code is compiled.

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

- If the public API changed on purpose, run `./gradlew :sealite:updateKotlinAbi` and commit the new
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

## Releases

release-please keeps a release pull request open on `main`. Merging it bumps `version` in
`gradle.properties`, updates `CHANGELOG.md` and creates the tag; the release workflow then builds
all natives and publishes to Maven Central after approval of the `release` environment.

Never edit `CHANGELOG.md` by hand: release-please writes every version section from the commit
history, and a hand-written section for the same version would appear twice. A curated overview of a
release goes in `.github/release-notes/<version>.md`; the release workflow places it above the
generated entry in the GitHub release.
