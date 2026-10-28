#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 pasichdev
#
# Runs a command and, if it is still running after <seconds>, takes thread dumps of every JVM
# (thread-dumps.sh) while it keeps running. The step's own timeout then ends a real hang, and the
# dumps are already on disk to be uploaded; a hang is visible as more than a cancelled runner.
#
# usage: run-with-dumps.sh <outdir> <seconds> -- <command> [args...]
set -u

out="${1:?usage: run-with-dumps.sh <outdir> <seconds> -- <command> [args...]}"
after="${2:?usage: run-with-dumps.sh <outdir> <seconds> -- <command> [args...]}"
shift 2
[ "${1:-}" = "--" ] && shift
here="$(cd "$(dirname "$0")" && pwd)"

"$@" &
command_pid=$!

(
  sleep "$after"
  echo "::warning::still running after ${after}s: thread dumps of all JVMs"
  "$here/thread-dumps.sh" "$out"
) &
watchdog_pid=$!

wait "$command_pid"
status=$?
kill "$watchdog_pid" > /dev/null 2>&1 || true
wait "$watchdog_pid" > /dev/null 2>&1 || true
exit "$status"
