#!/bin/bash
set -o errexit
set -o nounset
set -o pipefail


cd "$(dirname "$0")"
cd ..


REPO_NAME="$(basename "${PWD}")"
BUILD_IMAGE_NAME=amazoncorretto:11
BUILD_TAG="$(git branch --show-current)"
BUILD_MEMORY=4g


docker \
  run \
  --interactive \
  --tty \
  --user root \
  --rm \
  --volume "/var/run/docker.sock:/var/run/docker.sock" \
  --volume "${PWD}:/repo" \
  --workdir /repo \
  --env npm_config_cache=/repo/.npm \
  --env GRADLE_USER_HOME=/repo/.gradle \
  --env "GRADLE_OPTS=-Xmx${BUILD_MEMORY}" \
  "${BUILD_IMAGE_NAME}" \
  bash -c "
    set -euo pipefail
    set -x

    # Build
    ./gradlew --no-daemon clean build '${REPO_NAME}-web:installDist'

    # Restore file permissions
    chown --recursive '$(id --user):$(id --group)' .
  "


# Clean old image
IMAGE_NAME="${USER}/${REPO_NAME}:${BUILD_TAG}"
docker rmi -f "${IMAGE_NAME}"

# Build application image
DOCKERFILE=Dockerfile.slim
docker build -t ${IMAGE_NAME} -f "${DOCKERFILE}" .
