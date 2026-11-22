#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")/.."
exec java -jar apps/sh-api/target/sh-api-0.1.0-SNAPSHOT.jar --dev-token "$@"
