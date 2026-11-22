#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
for role in worker reconciler api; do
  file=".local/$role.pid"
  [[ -r "$file" ]] || continue
  pid=$(cat "$file")
  command=$(ps -p "$pid" -o command= || true)
  if [[ "$command" == *"$PWD/.local/releases/$role-"* || "$command" == *"apps/sh-$role/target/sh-$role-0.1.0-SNAPSHOT.jar"* ]]; then
    kill -TERM "$pid"
    for _ in {1..30}; do kill -0 "$pid" 2>/dev/null || break; sleep .2; done
  fi
  rm -f "$file"
done
