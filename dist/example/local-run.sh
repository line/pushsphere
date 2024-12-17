#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "$0")"
EXAMPLE_DIR="$PWD"
cd ..

# Build and run
../gradlew run --args="--config-dir '$EXAMPLE_DIR/conf'"
