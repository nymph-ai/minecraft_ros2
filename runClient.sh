#!/bin/bash
set -euo pipefail

# Prefer the baked Gradle cache from the image to avoid rebuilding on startup.
export GRADLE_USER_HOME=/root/.gradle

# Some of the ROS setup helpers expect COLCON_TRACE to exist even if empty.
if [ -z "${COLCON_TRACE+x}" ]; then
    export COLCON_TRACE=
fi

# Temporarily disable nounset while sourcing to avoid exit-on-unbound.
set +u
source "$ROS2JAVA_INSTALL_PATH"/setup.bash
set -u

ARGS=()

if [[ -n "${MC_USERNAME:-}" ]]; then
    ARGS+=("--username" "${MC_USERNAME}")
fi

if [[ -n "${MC_UUID:-}" ]]; then
    ARGS+=("--uuid" "${MC_UUID}")
fi

if [[ -n "${MC_ACCESS_TOKEN:-}" ]]; then
    ARGS+=("--accessToken" "${MC_ACCESS_TOKEN}")
fi

if [[ -n "${MC_USER_TYPE:-}" ]]; then
    ARGS+=("--userType" "${MC_USER_TYPE}")
fi

if [[ -n "${MC_VERSION_NAME:-}" ]]; then
    ARGS+=("--versionName" "${MC_VERSION_NAME}")
fi

# Clear stale NeoForge userdev temp artifacts that can break create1.21.11ClientExtraJar.
rm -rf /ws/minecraft_ros2/build/tmp/create1.21.11ClientExtraJar || true

# Auto-connect to server if MC_SERVER is set
if [[ -n "${MC_SERVER:-}" ]]; then
    ARGS+=("--quickPlayMultiplayer" "${MC_SERVER}")
fi

run_gradle() {
    local offline_flag=()
    if [[ "${1:-}" == "offline" ]]; then
        offline_flag=(--offline)
    fi

    if [[ ${#ARGS[@]} -gt 0 ]]; then
        ARG_STRING="$(printf '%s ' "${ARGS[@]}")"
        ARG_STRING="${ARG_STRING% }"
        ./gradlew --no-daemon "${offline_flag[@]}" runClient --stacktrace --args="${ARG_STRING}"
    else
        ./gradlew --no-daemon "${offline_flag[@]}" runClient --stacktrace
    fi
}

# Prefer offline if caches are present; fall back to online once to hydrate caches.
if [[ -f /ws/minecraft_ros2/.gradle/caches/minecraft/versions/1.21.11/metadata.json ]]; then
    run_gradle offline
else
    run_gradle online
fi
