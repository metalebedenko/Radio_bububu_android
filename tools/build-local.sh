#!/bin/sh
set -eu
TASK_PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$TASK_PROJECT_DIR"
exec ./gradlew --no-daemon "$@"
