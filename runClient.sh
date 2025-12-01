#!/bin/bash
set -euo pipefail

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

# Auto-connect to server if MC_SERVER is set
if [[ -n "${MC_SERVER:-}" ]]; then
    ARGS+=("--quickPlayMultiplayer" "${MC_SERVER}")
fi

if [[ ${#ARGS[@]} -gt 0 ]]; then
    ARG_STRING="$(printf '%s ' "${ARGS[@]}")"
    ARG_STRING="${ARG_STRING% }"
    ./gradlew runClient --stacktrace --args="${ARG_STRING}"
else
    ./gradlew runClient --stacktrace
fi