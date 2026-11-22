#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
if [[ -r "${SH_ENV_FILE:-$HOME/.config/super-harness/mysql.env}" ]]; then
  set -a
  source "${SH_ENV_FILE:-$HOME/.config/super-harness/mysql.env}"
  set +a
fi
: "${MYSQL_DATABASE:?set MYSQL_DATABASE}" "${AGENTSCOPE_MYSQL_DATABASE:?set AGENTSCOPE_MYSQL_DATABASE}"
: "${MYSQL_PASSWORD:?set MYSQL_PASSWORD}" "${MYSQL_USER:?set MYSQL_USER}"
mkdir -p .local
keyfile="${SH_DEV_KEY_FILE:-$HOME/.config/super-harness/dev-jwt.key}"
mkdir -p "$(dirname "$keyfile")"
if [[ ! -s "$keyfile" ]]; then
  if [[ -s .local/jwt.key ]]; then
    mv .local/jwt.key "$keyfile"
  else
    (set -o noclobber; openssl rand -base64 48 > "$keyfile")
  fi
fi
unset SH_DEV_KEY
export SPRING_PROFILES_ACTIVE=local
case "${1:-}" in
  ""|--no-build) ;;
  --build) ./mvnw -q -DskipTests package ;;
  *) echo "Usage: scripts/dev-up.sh [--build|--no-build]" >&2; exit 2 ;;
esac
for role in api reconciler worker; do
  [[ -r "apps/sh-$role/target/sh-$role-0.1.0-SNAPSHOT.jar" ]] || { echo "Missing $role jar. Build with ./mvnw package." >&2; exit 1; }
done
for role in api reconciler worker; do
  pidfile=".local/$role.pid"
  command=""
  if [[ -r "$pidfile" ]]; then command=$(ps -p "$(cat "$pidfile")" -o command= || true); fi
  if [[ "$command" == *"$PWD/.local/releases/$role-"* || "$command" == *"apps/sh-$role/target/sh-$role-0.1.0-SNAPSHOT.jar"* ]]; then
    signature=$(shasum -a 256 "apps/sh-$role/target/sh-$role-0.1.0-SNAPSHOT.jar" | cut -d' ' -f1)
    if [[ "$command" == *"$PWD/.local/releases/$role-$signature.jar"* ]]; then
      echo "$role already running" >&2
    else
      echo "$role is running a different build. Activate the current jar with scripts/dev-down.sh && scripts/dev-up.sh." >&2
    fi
    continue
  fi
  if [[ "$role" == api ]]; then export SH_DEV_KEY="$(cat "$keyfile")"; else unset SH_DEV_KEY; fi
  mkdir -p .local/releases
  source_jar="apps/sh-$role/target/sh-$role-0.1.0-SNAPSHOT.jar"
  signature=$(shasum -a 256 "$source_jar" | cut -d' ' -f1)
  jar="$PWD/.local/releases/$role-$signature.jar"
  if [[ ! -f "$jar" ]]; then cp "$source_jar" "$jar.tmp.$$"; mv "$jar.tmp.$$" "$jar"; fi
  nohup java -jar "$jar" >".local/$role.log" 2>&1 </dev/null &
  echo $! >"$pidfile"
done
for attempt in {1..60}; do
  ready=true
  for role in api reconciler worker; do
    if ! kill -0 "$(cat ".local/$role.pid")" 2>/dev/null; then echo "$role exited; inspect .local/$role.log" >&2; exit 1; fi
    if ! grep -q "Started .*Application" ".local/$role.log"; then ready=false; fi
  done
  if "$ready" && curl -fsS "http://127.0.0.1:${SERVER_PORT:-8080}/actuator/health" >/dev/null 2>&1; then
    echo "http://127.0.0.1:${SERVER_PORT:-8080}"
    exit 0
  fi
  sleep 1
done
echo 'Startup failed; inspect .local/*.log' >&2
exit 1
