#!/bin/bash
set -eo pipefail  # Removed -u to allow unbound variables in ROS setup scripts

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

# Start image capture service in background (runs in same container for reliable discovery)
start_image_capture() {
    echo "[headless] starting image capture service" >&2
    if [ ! -f /opt/image_capture/image_capture.py ]; then
        echo "[headless] WARNING: image_capture.py not found, skipping image capture service" >&2
        return
    fi
    # Source ROS setup (disable error on unbound vars temporarily)
    # The ROS setup script uses unbound variables, so we need to disable -u
    set +u || true
    source /opt/ros/humble/setup.bash || {
        echo "[headless] ERROR: Failed to source ROS setup" >&2
        return 1
    }
    # Re-enable -u if it was set (but we removed it from top-level, so this is just for safety)
    set -u || true
    export IMAGE_TOPIC=${IMAGE_TOPIC:-/player/image_raw}
    export SAVE_IMAGES=${SAVE_IMAGES:-false}
    export IMAGE_SAVE_DIR=${IMAGE_SAVE_DIR:-/tmp/minecraft_images}
    export IMAGE_QUEUE_SIZE=${IMAGE_QUEUE_SIZE:-10}
    mkdir -p "$(dirname /tmp/image_capture.log)"
    python3 /opt/image_capture/image_capture.py > /tmp/image_capture.log 2>&1 &
    IMAGE_CAPTURE_PID=$!
    echo "[headless] image capture started with PID ${IMAGE_CAPTURE_PID}" >&2
    # Give it a moment to start, then check if it's still running
    sleep 2
    if ! ps -p "$IMAGE_CAPTURE_PID" >/dev/null 2>&1; then
        echo "[headless] WARNING: image capture process died immediately, check /tmp/image_capture.log" >&2
        cat /tmp/image_capture.log >&2 || true
    fi
}

start_image_capture

# Image bridge disabled - Foxglove can subscribe directly to Java publisher
# The bridge was causing message duplication and rate issues
# start_image_bridge() {
#     echo "[headless] starting image bridge for visualization" >&2
#     if [ ! -f /opt/image_capture/image_bridge.py ]; then
#         echo "[headless] WARNING: image_bridge.py not found, skipping bridge" >&2
#         return
#     fi
#     set +u || true
#     source /opt/ros/humble/setup.bash || {
#         echo "[headless] ERROR: Failed to source ROS setup for bridge" >&2
#         return 1
#     }
#     set -u || true
#     python3 /opt/image_capture/image_bridge.py > /tmp/image_bridge.log 2>&1 &
#     IMAGE_BRIDGE_PID=$!
#     echo "[headless] image bridge started with PID ${IMAGE_BRIDGE_PID}" >&2
#     sleep 2
#     if ! ps -p "$IMAGE_BRIDGE_PID" >/dev/null 2>&1; then
#         echo "[headless] WARNING: image bridge process died immediately, check /tmp/image_bridge.log" >&2
#         cat /tmp/image_bridge.log >&2 || true
#     fi
# }
#
# start_image_bridge

echo "[headless] image bridge disabled - Foxglove subscribes directly to Java publisher" >&2

# Start Foxglove bridge for visualization (runs in same container for reliable discovery)
start_foxglove_bridge() {
    echo "[headless] starting Foxglove bridge for visualization" >&2
    set +u || true
    source /opt/ros/humble/setup.bash || {
        echo "[headless] ERROR: Failed to source ROS setup for Foxglove bridge" >&2
        return 1
    }
    set -u || true
    # Install Foxglove bridge if not already installed
    if ! command -v ros2 &> /dev/null || ! ros2 pkg list | grep -q foxglove_bridge; then
        echo "[headless] Installing Foxglove bridge..." >&2
        apt-get update -qq && apt-get install -y -qq ros-humble-foxglove-bridge > /dev/null 2>&1 || {
            echo "[headless] WARNING: Failed to install Foxglove bridge" >&2
            return 1
        }
        source /opt/ros/humble/setup.bash
    fi
    FOXGLOVE_PORT=${FOXGLOVE_PORT:-8765}
    ros2 launch foxglove_bridge foxglove_bridge_launch.xml port:=$FOXGLOVE_PORT > /tmp/foxglove_bridge.log 2>&1 &
    FOXGLOVE_BRIDGE_PID=$!
    echo "[headless] Foxglove bridge started with PID ${FOXGLOVE_BRIDGE_PID} on port $FOXGLOVE_PORT" >&2
    sleep 3
    if ! ps -p "$FOXGLOVE_BRIDGE_PID" >/dev/null 2>&1; then
        echo "[headless] WARNING: Foxglove bridge process died immediately, check /tmp/foxglove_bridge.log" >&2
        cat /tmp/foxglove_bridge.log >&2 || true
    fi
}

start_foxglove_bridge

cleanup_image_capture() {
    if [ -n "${IMAGE_CAPTURE_PID:-}" ] && ps -p "$IMAGE_CAPTURE_PID" >/dev/null 2>&1; then
        echo "[headless] stopping image capture service (PID ${IMAGE_CAPTURE_PID})" >&2
        kill "$IMAGE_CAPTURE_PID" || true
    fi
}
# cleanup_image_bridge() {
#     if [ -n "${IMAGE_BRIDGE_PID:-}" ] && ps -p "$IMAGE_BRIDGE_PID" >/dev/null 2>&1; then
#         echo "[headless] stopping image bridge (PID ${IMAGE_BRIDGE_PID})" >&2
#         kill "$IMAGE_BRIDGE_PID" || true
#     fi
# }
cleanup_foxglove_bridge() {
    if [ -n "${FOXGLOVE_BRIDGE_PID:-}" ] && ps -p "$FOXGLOVE_BRIDGE_PID" >/dev/null 2>&1; then
        echo "[headless] stopping Foxglove bridge (PID ${FOXGLOVE_BRIDGE_PID})" >&2
        kill "$FOXGLOVE_BRIDGE_PID" || true
    fi
}
trap cleanup_image_capture EXIT
# trap cleanup_image_bridge EXIT
trap cleanup_foxglove_bridge EXIT

echo "[headless] launching Minecraft client" >&2
./runClient.sh

