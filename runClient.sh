#!/usr/bin/env bash
set -euo pipefail

if [ -z "${ROS2JAVA_INSTALL_PATH:-}" ]; then
    echo "[runClient] ROS2JAVA_INSTALL_PATH is not set" >&2
    exit 1
fi

set +u
source "$ROS2JAVA_INSTALL_PATH/setup.bash"
set -u
exec ./gradlew runClient --stacktrace
