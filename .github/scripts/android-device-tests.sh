#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 pasichdev
#
# The body of the android-emulator-runner step: the emulator is up when this runs. The marker file
# tells the workflow that a failure after this point is a test failure, which is never retried.
set -eu

touch "${RUNNER_TEMP}/android-tests-started"
exec "$(dirname "$0")/run-with-dumps.sh" "${RUNNER_TEMP}/dumps" 1800 -- \
  ./gradlew :sealite:connectedAndroidDeviceTest "-Psealite.prebuiltNatives=${PREBUILT}" --stacktrace
