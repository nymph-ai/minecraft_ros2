#!/usr/bin/env bash
set -euo pipefail

if [ -z "${ROS2JAVA_INSTALL_PATH:-}" ]; then
    echo "[runClient] ROS2JAVA_INSTALL_PATH is not set" >&2
    exit 1
fi

set +u
source "$ROS2JAVA_INSTALL_PATH/setup.bash"
set -u

# Check if refresh token is provided for online mode authentication
if [ -n "${MINECRAFT_REFRESH_TOKEN:-}" ]; then
    echo "[runClient] Refresh token detected, authenticating with Microsoft..." >&2

    # Run authentication script and capture output
    AUTH_OUTPUT=$(./authenticate.sh "$MINECRAFT_REFRESH_TOKEN")
    AUTH_EXIT_CODE=$?

    if [ $AUTH_EXIT_CODE -ne 0 ]; then
        echo "[runClient] ERROR: Authentication failed with exit code $AUTH_EXIT_CODE" >&2
        echo "[runClient] Falling back to offline mode" >&2
    else
        # Parse authentication response
        export MC_USERNAME=$(echo "$AUTH_OUTPUT" | jq -r '.username')
        export MC_UUID=$(echo "$AUTH_OUTPUT" | jq -r '.uuid')
        export MC_ACCESS_TOKEN=$(echo "$AUTH_OUTPUT" | jq -r '.accessToken')

        echo "[runClient] Successfully authenticated as $MC_USERNAME" >&2
        echo "[runClient] UUID: $MC_UUID" >&2
    fi
else
    echo "[runClient] No refresh token provided, using offline mode" >&2
fi

exec ./gradlew runClient --stacktrace
