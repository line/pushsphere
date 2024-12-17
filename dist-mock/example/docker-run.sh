#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "$0")"
EXAMPLE_DIR="$PWD"
cd ..

# Build and push the image to Docker engine.
../gradlew jibDockerBuild

# Spawn a new container with the example configuration.
docker run \
  --volume "$EXAMPLE_DIR/conf:/conf" \
  --publish 127.0.0.1:8080:8080 \
  --publish 127.0.0.1:8443:8443 \
  ghcr.io/line/pushsphere-mock
