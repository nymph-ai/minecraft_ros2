#!/bin/bash
set -euo pipefail

: "${DISPLAY:=:99}"

start_xvfb() {
    echo "[headless] starting Xvfb on ${DISPLAY}" >&2
    Xvfb "$DISPLAY" \
        -screen 0 1920x1080x24 \
        -ac \
        +extension GLX \
        +extension RANDR \
        +extension RENDER \
        -nolisten tcp \
        -noreset >/dev/null 2>&1 &
    XVFB_PID=$!
    echo "[headless] Xvfb started with PID ${XVFB_PID}" >&2
    echo $XVFB_PID
}

wait_for_display() {
    echo "[headless] waiting for display ${DISPLAY} to become available..." >&2
    for _ in $(seq 1 30); do
        if DISPLAY="$DISPLAY" xdpyinfo >/dev/null 2>&1; then
            echo "[headless] display ${DISPLAY} is ready" >&2
            return 0
        fi
        sleep 1
    done
    echo "[headless] display ${DISPLAY} did not come up in time" >&2
    return 1
}

XVFB_PID=$(start_xvfb)
cleanup() {
    if ps -p "$XVFB_PID" >/dev/null 2>&1; then
        kill "$XVFB_PID" || true
    fi
}
trap cleanup EXIT

if ! wait_for_display; then
    echo "Xvfb failed to start." >&2
    exit 1
fi

sync_mod_jar() {
    local cache_dir="${MOD_JAR_CACHE:-/opt/minecraft_ros2_mod}"
    local dest_dir="/ws/minecraft_ros2/run/mods"

    mkdir -p "$dest_dir"

    shopt -s nullglob
    local copied=false
    for jar in "$cache_dir"/minecraft_ros2-*.jar; do
        cp -f "$jar" "$dest_dir"/
        copied=true
    done
    shopt -u nullglob

    if [ "$copied" = false ]; then
        echo "[headless] warning: no packaged minecraft_ros2 jar found in ${cache_dir}" >&2
    fi
}

sync_mod_jar

echo "[headless] launching Minecraft client" >&2
./runClient.sh

