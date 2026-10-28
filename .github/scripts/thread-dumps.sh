#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 pasichdev
#
# Thread dumps of every JVM on the machine: the Gradle client and daemon, and the Gradle test workers
# that run the tests. A hung test is otherwise visible only as a runner cancelled hours later.
#
# usage: thread-dumps.sh <outdir>
set -u

out="${1:?usage: thread-dumps.sh <outdir>}"
mkdir -p "$out"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"

listing="$(jcmd -l 2>&1 || true)"
printf '# jcmd -l at %s\n%s\n' "$stamp" "$listing" > "$out/jvms-$stamp.txt"
echo "$listing"

# "<pid> <main class or jar> ..."; skip the jcmd process itself.
printf '%s\n' "$listing" | awk '$1 ~ /^[0-9]+$/ && $2 !~ /JCmd$/ { print $1 }' | while read -r pid; do
  {
    printf '# pid %s: %s\n' "$pid" "$(printf '%s\n' "$listing" | awk -v p="$pid" '$1 == p { $1 = ""; print }')"
    jcmd "$pid" Thread.print -l 2>&1 || jstack -l "$pid" 2>&1 || true
  } > "$out/threads-$pid-$stamp.txt"
done
ls -l "$out" || true
