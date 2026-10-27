<!-- The PR title must be a Conventional Commit, for example "fix(native): wipe the buffer on error". -->

## What and why

## How it was tested

- [ ] `./gradlew build` passes
- [ ] Platforms tested: <!-- jvm / macosArm64 / iosSimulatorArm64 / android / windows / linux -->

## Checklist

- [ ] Public API unchanged, or `sealite/api/` was regenerated on purpose
- [ ] No key material is logged, copied into a `String` or left unwiped
- [ ] Docs and `CHANGELOG`-relevant behaviour described above
- [ ] New source files carry the SPDX header
