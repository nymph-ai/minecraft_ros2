#!/bin/bash
# Build minecraft_ros2 mod jar in a containerized ROS2 Java environment.
set -euo pipefail

cd "$(dirname "$0")"

ROOT_DIR="$(cd .. && pwd)"
OUTPUT_DIR="${ROOT_DIR}/data/built_mod"
GRADLE_CACHE_DIR="${ROOT_DIR}/data/gradle_cache"
BUILD_OUT_DIR="${GRADLE_CACHE_DIR}/build-out"
CLEAN=0

while [ $# -gt 0 ]; do
    case "$1" in
        --clean)
            CLEAN=1
            ;;
        --fast)
            CLEAN=0
            ;;
        *)
            echo "Usage: $0 [--clean|--fast]" >&2
            exit 1
            ;;
    esac
    shift
done

ROS2_JAVA_BASE_IMAGE="${ROS2_JAVA_BASE_IMAGE:-ros2_java_cyclone:latest}"
BUILDER_IMAGE="${BUILDER_IMAGE:-krenaia-mod-builder:latest}"
mkdir -p "${OUTPUT_DIR}"

if ! docker image inspect "${ROS2_JAVA_BASE_IMAGE}" >/dev/null 2>&1; then
    docker build -t "${ROS2_JAVA_BASE_IMAGE}" -f "${ROOT_DIR}/docker/ros2_java/Dockerfile" "${ROOT_DIR}"
fi

if [[ "${REBUILD_MOD_BUILDER:-0}" == "1" ]] || ! docker image inspect "${BUILDER_IMAGE}" >/dev/null 2>&1; then
    docker build \
        --build-arg ROS2_JAVA_BASE="${ROS2_JAVA_BASE_IMAGE}" \
        -t "${BUILDER_IMAGE}" \
        - <<'EOF'
ARG ROS2_JAVA_BASE
FROM ${ROS2_JAVA_BASE}
RUN apt-get update && \
    apt-get install -y openjdk-21-jdk && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"
EOF
fi

if ! mkdir -p "${GRADLE_CACHE_DIR}/gradle-home" \
             "${GRADLE_CACHE_DIR}/project-cache" \
             "${BUILD_OUT_DIR}" \
             "${GRADLE_CACHE_DIR}/neogradle-config"; then
    echo "ERROR: cannot create Gradle cache dirs under ${GRADLE_CACHE_DIR}" >&2
    echo "Fix ownership/permissions (for example: chown -R $(id -u):$(id -g) ${GRADLE_CACHE_DIR%/*}) and rerun." >&2
    exit 1
fi

DOCKER_UID="${SUDO_UID:-$(id -u)}"
DOCKER_GID="${SUDO_GID:-$(id -g)}"
GRADLE_TASKS="jar"
if [ "${CLEAN}" -eq 1 ]; then
    GRADLE_TASKS="clean jar"
fi

docker run --rm \
    -u "${DOCKER_UID}:${DOCKER_GID}" \
    -e HOME=/tmp \
    -e ROS2JAVA_INSTALL_PATH=/ws/ros2_java_ws/install \
    -e GRADLE_USER_HOME=/gradle-cache/gradle-home \
    -e NEOGRADLE_CONFIGURATION_DATA_DIR=/gradle-cache/neogradle-config \
    -v "$(pwd):/work" \
    -v "${GRADLE_CACHE_DIR}:/gradle-cache" \
    -w /work \
    "${BUILDER_IMAGE}" \
    bash -lc "set -eo pipefail; if [ -f /opt/ros/jazzy/setup.bash ]; then source /opt/ros/jazzy/setup.bash; fi; source /ws/ros2_java_ws/install/setup.bash; ./gradlew ${GRADLE_TASKS} --no-daemon --build-cache -g /gradle-cache/gradle-home --project-cache-dir /gradle-cache/project-cache -PbuildDir=/gradle-cache/build-out"

cp -f "${BUILD_OUT_DIR}"/libs/minecraft_ros2-*.jar "${OUTPUT_DIR}/"
ls -lh "${OUTPUT_DIR}"/minecraft_ros2-*.jar
