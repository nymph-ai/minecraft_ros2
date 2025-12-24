#!/bin/bash
set -eo pipefail  # Removed -u to allow unbound variables in ROS setup scripts

: "${MC_HEADLESS_MODE:=egl}"

# Xvfb/X11 path (kept for fallback; enable by setting MC_HEADLESS_MODE=x11).
# : "${DISPLAY:=:99}"
# start_xvfb() {
#     echo "[headless] starting Xvfb on ${DISPLAY}" >&2
#     Xvfb "$DISPLAY" \
#         -screen 0 1920x1080x24 \
#         -ac \
#         +extension GLX \
#         +extension RANDR \
#         +extension RENDER \
#         -nolisten tcp \
#         -noreset >/dev/null 2>&1 &
#     XVFB_PID=$!
#     echo "[headless] Xvfb started with PID ${XVFB_PID}" >&2
#     echo $XVFB_PID
# }
# wait_for_display() {
#     echo "[headless] waiting for display ${DISPLAY} to become available..." >&2
#     for _ in $(seq 1 30); do
#         if DISPLAY="$DISPLAY" xdpyinfo >/dev/null 2>&1; then
#             echo "[headless] display ${DISPLAY} is ready" >&2
#             return 0
#         fi
#         sleep 1
#     done
#     echo "[headless] display ${DISPLAY} did not come up in time" >&2
#     return 1
# }

start_xorg() {
    : "${DISPLAY:=:99}"
    export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/xdg}"
    mkdir -p "${XDG_RUNTIME_DIR}"
    chmod 0700 "${XDG_RUNTIME_DIR}"

    local config=""
    if [ -f /usr/lib/xorg/modules/drivers/nvidia_drv.so ] || [ -f /usr/lib/x86_64-linux-gnu/nvidia/xorg/nvidia_drv.so ]; then
        config="/etc/X11/xorg-nvidia-headless.conf"
    elif [ -f /usr/lib/xorg/modules/drivers/modesetting_drv.so ]; then
        config="/etc/X11/xorg-modesetting-headless.conf"
    else
        config="/etc/X11/xorg-headless.conf"
    fi
    echo "[headless] starting Xorg on ${DISPLAY} with config ${config}" >&2
    Xorg "$DISPLAY" \
        -config "${config}" \
        -noreset \
        -nolisten tcp \
        -logfile /tmp/Xorg.log >/dev/null 2>&1 &
    XORG_PID=$!
    echo "[headless] Xorg started with PID ${XORG_PID}" >&2
    echo $XORG_PID
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

if [ "${MC_HEADLESS_MODE}" = "xorg" ]; then
    XORG_PID=$(start_xorg)
    cleanup_xorg() {
        if ps -p "$XORG_PID" >/dev/null 2>&1; then
            kill "$XORG_PID" || true
        fi
    }
    trap cleanup_xorg EXIT
    if ! wait_for_display; then
        echo "Xorg failed to start. Check /tmp/Xorg.log" >&2
        exit 1
    fi
elif [ "${MC_HEADLESS_MODE}" = "x11" ]; then
    : "${DISPLAY:=:99}"
    # XVFB_PID=$(start_xvfb)
    # cleanup() {
    #     if ps -p "$XVFB_PID" >/dev/null 2>&1; then
    #         kill "$XVFB_PID" || true
    #     fi
    # }
    # trap cleanup EXIT
    # if ! wait_for_display; then
    #     echo "Xvfb failed to start." >&2
    #     exit 1
    # fi
    echo "[headless] X11 mode requested but Xvfb startup is commented out." >&2
    echo "[headless] Set MC_HEADLESS_MODE=xorg for NVIDIA or re-enable Xvfb in this script." >&2
fi

ensure_mod_dir() {
    local dest_dir="/ws/minecraft_ros2/run/mods"
    mkdir -p "$dest_dir"
}

install_baritone_mod() {
    local dest_dir="/ws/minecraft_ros2/run/mods"
    local baritone_src=""
    rm -f "${dest_dir}/baritone-api-forge-1.15.0.jar" \
        "${dest_dir}/baritone-api-neoforge-1.15.0.jar" \
        "${dest_dir}/baritone-standalone-neoforge-1.15.0.jar"
    if [ -f "${dest_dir}/baritone-unoptimized-neoforge-1.15.0.jar" ]; then
        echo "[headless] Baritone mod jar already present in ${dest_dir}" >&2
        return
    fi
    if [ -f /opt/minecraft_ros2_libs/baritone-unoptimized-neoforge-1.15.0.jar ]; then
        baritone_src="/opt/minecraft_ros2_libs/baritone-unoptimized-neoforge-1.15.0.jar"
    elif [ -f /ws/minecraft_ros2/libs/baritone-unoptimized-neoforge-1.15.0.jar ]; then
        baritone_src="/ws/minecraft_ros2/libs/baritone-unoptimized-neoforge-1.15.0.jar"
    fi
    if [ -n "$baritone_src" ]; then
        cp -f "$baritone_src" "$dest_dir/"
        echo "[headless] ensured Baritone mod jar in ${dest_dir}" >&2
    else
        echo "[headless] WARNING: Baritone jar not found; pathfinding will be unavailable" >&2
    fi
}

ensure_mod_dir
install_baritone_mod
if [ -n "${MC_ASSETS_DIR:-}" ]; then
    mkdir -p "${MC_ASSETS_DIR}"
fi


ensure_asset_index() {
    if [ -z "${MC_ASSETS_DIR:-}" ]; then
        return
    fi
    if [ -d "${MC_ASSETS_DIR}/indexes" ]; then
        return
    fi
    echo "[headless] ensuring asset index in ${MC_ASSETS_DIR}" >&2
    MINECRAFT_HOME="${MINECRAFT_HOME:-/opt/minecraft}" \
    MC_ASSETS_DIR="${MC_ASSETS_DIR}" \
    PREFETCH_ASSETS=false \
    PYTHONUNBUFFERED=1 \
    python3 -u /ws/minecraft_ros2/tools/prefetch_client.py || {
        echo "[headless] WARNING: asset index fetch failed" >&2
    }
}

ensure_asset_index

ensure_quickplay_dir() {
    if [ -z "${MC_SERVER:-}" ]; then
        return
    fi
    local base_dir="${MC_GAME_DIR:-/ws/minecraft_ros2/run}"
    local qp_dir="${MC_QUICKPLAY_PATH:-${base_dir}/quickplay}"
    if [ -e "${qp_dir}" ] && [ ! -d "${qp_dir}" ]; then
        local backup="${qp_dir}.bak.$(date +%s)"
        mv "${qp_dir}" "${backup}"
        echo "[headless] quickplay path was a file, moved to ${backup}" >&2
    fi
    mkdir -p "${qp_dir}"
}

ensure_quickplay_dir

# Optional: download asset objects into a writable volume (can take time).
prefetch_assets() {
    if [ "${MC_ASSETS_PREFETCH:-false}" != "true" ]; then
        return
    fi
    echo "[headless] prefetching Minecraft assets into ${MC_ASSETS_DIR:-/ws/minecraft_ros2/run/assets}" >&2
    MINECRAFT_HOME="${MINECRAFT_HOME:-/opt/minecraft}" \
    MC_ASSETS_DIR="${MC_ASSETS_DIR:-/ws/minecraft_ros2/run/assets}" \
    PREFETCH_ASSETS=true \
    PYTHONUNBUFFERED=1 \
    python3 -u /ws/minecraft_ros2/tools/prefetch_client.py || {
        echo "[headless] WARNING: asset prefetch failed" >&2
    }
}

prefetch_assets

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
    source /opt/ros/jazzy/setup.bash || {
        echo "[headless] ERROR: Failed to source ROS setup" >&2
        return 1
    }
    # Leave nounset disabled to avoid ROS setup using unbound variables.
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
#     source /opt/ros/jazzy/setup.bash || {
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
    source /opt/ros/jazzy/setup.bash || {
        echo "[headless] ERROR: Failed to source ROS setup for Foxglove bridge" >&2
        return 1
    }
    # Leave nounset disabled to avoid ROS setup using unbound variables.
    if ! command -v ros2 &> /dev/null || ! ros2 pkg prefix foxglove_bridge >/dev/null 2>&1; then
        echo "[headless] WARNING: Foxglove bridge not installed, skipping" >&2
        return 0
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

echo "[headless] launching Minecraft client (no Gradle)" >&2
python3 /ws/minecraft_ros2/tools/launch_client.py
