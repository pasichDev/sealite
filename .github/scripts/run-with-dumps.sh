#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 pasichdev
#
# Runs a command and, if it is still running after <seconds>, takes thread dumps of every JVM
# (thread-dumps.sh) while it keeps running. The step's own timeout then ends a real hang, and the
# dumps are already on disk to be uploaded; a hang is visible as more than a cancelled runner.
#
# The watchdog is a polling loop in this process, not a background `sleep`: a leftover background
# process would keep the step's output open after the command ends, and runners that wait for the
# output to close (android-emulator-runner does) would then hang until their timeout.
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

elapsed=0
dumped=false
while kill -0 "$command_pid" 2> /dev/null; do
  sleep 5
  elapsed=$((elapsed + 5))
  if [ "$dumped" = false ] && [ "$elapsed" -ge "$after" ] && kill -0 "$command_pid" 2> /dev/null; then
    echo "::warning::still running after ${after}s: thread dumps of all JVMs"
    "$here/thread-dumps.sh" "$out" || true
    dumped=true
  fi
done

wait "$command_pid"
exit $?
