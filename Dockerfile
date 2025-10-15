FROM ghcr.io/minecraft-ros2/ros2_java:latest

ENV DEBIAN_FRONTEND=noninteractive

# Install dependencies
SHELL ["/bin/bash", "-c"]
WORKDIR /ws/minecraft_ros2
COPY . .

RUN set -euxo pipefail \
    && apt-get update \
    && apt-get install -y \
        xvfb \
        mesa-utils \
        libgl1 \
        libgl1-mesa-dri \
        libgl1-mesa-glx \
        libglu1-mesa \
        libglvnd0 \
        libgles2 \
        libegl1 \
        libglx-mesa0 \
        libgbm1 \
        libxxf86vm1 \
        libopenal1 \
        libxi6 \
        libxrender1 \
        libxrandr2 \
        libxcursor1 \
        libxinerama1 \
        libnss3 \
        libasound2 \
        alsa-utils \
        pulseaudio \
        pulseaudio-utils \
        xserver-xorg-video-dummy \
        mesa-vulkan-drivers \
        procps \
        openjdk-21-jdk \
        curl \
        jq \
    && rviz_pkg="" \
    && if [ -n "${ROS_DISTRO:-}" ]; then \
        rviz_pkg="ros-${ROS_DISTRO}-rviz2"; \
    fi \
    && if [ -n "$rviz_pkg" ] && apt-cache show "$rviz_pkg" >/dev/null 2>&1; then \
        apt-get install -y "$rviz_pkg"; \
    elif apt-cache show ros-humble-rviz2 >/dev/null 2>&1; then \
        apt-get install -y ros-humble-rviz2; \
    else \
        echo "rviz2 package not available in APT sources; skipping install"; \
    fi \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# Configure ALSA to use a dummy/null device to prevent audio errors
# This creates a virtual null audio sink that accepts all audio but doesn't output anything
RUN mkdir -p /root/.asoundrc.d && \
    cat > /root/.asoundrc <<'ALSA_EOF'
# Define a null PCM device that discards all audio
pcm.null {
    type null
}

# Set null as the default playback device
pcm.!default {
    type plug
    slave.pcm "null"
}

# Define a dummy control device
ctl.!default {
    type null
}
ALSA_EOF

# Configure PulseAudio for virtual audio (optional - for audio recording)
# This allows audio to be captured if needed, while still working in headless mode
RUN mkdir -p /root/.config/pulse && \
    cat > /root/.config/pulse/default.pa <<'PULSE_EOF'
# Load the native protocol module for client connections
.include /etc/pulse/default.pa

# Create a null sink that discards audio (for silent operation)
load-module module-null-sink sink_name=virtual_audio sink_properties=device.description="Virtual_Audio_Sink"

# Optionally set the null sink as default (can be changed at runtime)
set-default-sink virtual_audio
PULSE_EOF

# Set environment variables for audio
# PULSE_SERVER=unix:/tmp/pulseaudio.socket can be set at runtime if needed
ENV ALSA_CARD=Null \
    AUDIODEV=null \
    SDL_AUDIODRIVER=pulseaudio

# Update alternatives to ensure JDK 21 is the default
RUN update-alternatives --set java /usr/lib/jvm/java-21-openjdk-amd64/bin/java || \
    update-alternatives --set java $(update-alternatives --list java | grep java-21 | head -n1) || \
    echo "Warning: Could not set java-21 as default"

COPY --chmod=755 <<EOF runRviz.sh
    source /opt/ros/kilted/setup.bash
    rviz2 -d ./minecraft.rviz
EOF

COPY --chmod=755 <<'EOF' authenticate.sh
#!/bin/bash
# Microsoft OAuth flow for Minecraft authentication
set -euo pipefail

REFRESH_TOKEN="${1:-}"

if [ -z "$REFRESH_TOKEN" ]; then
    echo "{\"error\": \"No refresh token provided\"}" >&2
    exit 1
fi

# Step 1: Get Microsoft access token from refresh token
echo "[Auth] Exchanging refresh token for access token..." >&2
MSA_RESPONSE=$(curl -s -X POST "https://login.live.com/oauth20_token.srf" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "client_id=00000000402b5328" \
    -d "grant_type=refresh_token" \
    -d "refresh_token=$REFRESH_TOKEN" \
    -d "scope=service::user.auth.xboxlive.com::MBI_SSL")

MSA_ACCESS_TOKEN=$(echo "$MSA_RESPONSE" | jq -r '.access_token // empty')
if [ -z "$MSA_ACCESS_TOKEN" ] || [ "$MSA_ACCESS_TOKEN" = "null" ]; then
    echo "[Auth] ERROR: Failed to get Microsoft access token" >&2
    echo "$MSA_RESPONSE" | jq . >&2
    exit 1
fi
echo "[Auth] Got Microsoft access token" >&2

# Step 2: Authenticate with Xbox Live
echo "[Auth] Authenticating with Xbox Live..." >&2
XBL_RESPONSE=$(curl -s -X POST "https://user.auth.xboxlive.com/user/authenticate" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json" \
    -d "{\"Properties\":{\"AuthMethod\":\"RPS\",\"SiteName\":\"user.auth.xboxlive.com\",\"RpsTicket\":\"$MSA_ACCESS_TOKEN\"},\"RelyingParty\":\"http://auth.xboxlive.com\",\"TokenType\":\"JWT\"}")

XBL_TOKEN=$(echo "$XBL_RESPONSE" | jq -r '.Token // empty')
USER_HASH=$(echo "$XBL_RESPONSE" | jq -r '.DisplayClaims.xui[0].uhs // empty')
if [ -z "$XBL_TOKEN" ] || [ "$XBL_TOKEN" = "null" ]; then
    echo "[Auth] ERROR: Failed to get Xbox Live token" >&2
    echo "[Auth] Xbox Live Response:" >&2
    echo "$XBL_RESPONSE" | jq -C . >&2 2>/dev/null || echo "$XBL_RESPONSE" >&2

    # Check if it's a token expiry issue
    ERROR_CODE=$(echo "$XBL_RESPONSE" | jq -r '.XErr // empty')
    if [ -n "$ERROR_CODE" ]; then
        echo "[Auth] Error code: $ERROR_CODE" >&2
    fi
    exit 1
fi
echo "[Auth] Got Xbox Live token" >&2

# Step 3: Get XSTS token
echo "[Auth] Getting XSTS token..." >&2
XSTS_RESPONSE=$(curl -s -X POST "https://xsts.auth.xboxlive.com/xsts/authorize" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json" \
    -d "{\"Properties\":{\"SandboxId\":\"RETAIL\",\"UserTokens\":[\"$XBL_TOKEN\"]},\"RelyingParty\":\"rp://api.minecraftservices.com/\",\"TokenType\":\"JWT\"}")

XSTS_TOKEN=$(echo "$XSTS_RESPONSE" | jq -r '.Token // empty')
if [ -z "$XSTS_TOKEN" ] || [ "$XSTS_TOKEN" = "null" ]; then
    echo "[Auth] ERROR: Failed to get XSTS token" >&2
    echo "$XSTS_RESPONSE" | jq . >&2
    exit 1
fi
echo "[Auth] Got XSTS token" >&2

# Step 4: Get Minecraft access token
echo "[Auth] Getting Minecraft access token..." >&2
MC_RESPONSE=$(curl -s -X POST "https://api.minecraftservices.com/authentication/login_with_xbox" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json" \
    -d "{\"identityToken\": \"XBL3.0 x=$USER_HASH;$XSTS_TOKEN\"}")

MC_ACCESS_TOKEN=$(echo "$MC_RESPONSE" | jq -r '.access_token // empty')
if [ -z "$MC_ACCESS_TOKEN" ] || [ "$MC_ACCESS_TOKEN" = "null" ]; then
    echo "[Auth] ERROR: Failed to get Minecraft access token" >&2
    echo "$MC_RESPONSE" | jq . >&2
    exit 1
fi
echo "[Auth] Got Minecraft access token" >&2

# Step 5: Get Minecraft profile (username and UUID)
echo "[Auth] Getting Minecraft profile..." >&2
PROFILE_RESPONSE=$(curl -s -X GET "https://api.minecraftservices.com/minecraft/profile" \
    -H "Authorization: Bearer $MC_ACCESS_TOKEN")

MC_USERNAME=$(echo "$PROFILE_RESPONSE" | jq -r '.name // empty')
MC_UUID=$(echo "$PROFILE_RESPONSE" | jq -r '.id // empty')

if [ -z "$MC_USERNAME" ] || [ "$MC_USERNAME" = "null" ]; then
    echo "[Auth] ERROR: Failed to get Minecraft profile" >&2
    echo "$PROFILE_RESPONSE" | jq . >&2
    exit 1
fi

echo "[Auth] Successfully authenticated as $MC_USERNAME (UUID: $MC_UUID)" >&2

# Output JSON with credentials
jq -n \
    --arg username "$MC_USERNAME" \
    --arg uuid "$MC_UUID" \
    --arg token "$MC_ACCESS_TOKEN" \
    '{username: $username, uuid: $uuid, accessToken: $token}'
EOF

COPY --chmod=755 <<'EOF' startClient.sh
#!/bin/bash
set -euo pipefail

echo "[startClient] Starting Minecraft ROS2 client..."
echo "[startClient] Memory info:"
free -h || echo "[startClient] free command not available"

# Set JAVA_HOME dynamically if not set
if [ -z "${JAVA_HOME:-}" ]; then
    export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
    echo "[startClient] JAVA_HOME set to: $JAVA_HOME"
fi

# Verify Java version
java -version 2>&1 | head -n3

# Start PulseAudio in daemon mode for virtual audio support
echo "[startClient] Starting PulseAudio daemon..."
pulseaudio --daemonize=yes --exit-idle-time=-1 --log-target=stderr 2>&1 || echo "[startClient] Warning: PulseAudio failed to start"

# Wait a moment for PulseAudio to initialize
sleep 1

# Verify PulseAudio is running
if pulseaudio --check; then
    echo "[startClient] PulseAudio is running"
    # List available sinks
    pactl list sinks short 2>&1 || true
else
    echo "[startClient] Warning: PulseAudio is not running, audio may not work"
fi

# Force software rendering via Mesa llvmpipe
export LIBGL_ALWAYS_SOFTWARE=1
export LIBGL_ALWAYS_INDIRECT=0
export LIBGL_DEBUG="${LIBGL_DEBUG:-error}"
export MESA_GL_VERSION_OVERRIDE="${MESA_GL_VERSION_OVERRIDE:-3.3}"
export MESA_GLSL_VERSION_OVERRIDE="${MESA_GLSL_VERSION_OVERRIDE:-330}"
export GALLIUM_DRIVER="${GALLIUM_DRIVER:-llvmpipe}"
export MESA_LOADER_DRIVER_OVERRIDE=llvmpipe

echo "[startClient] Mesa configuration:"
echo "  LIBGL_ALWAYS_SOFTWARE=$LIBGL_ALWAYS_SOFTWARE"
echo "  MESA_GL_VERSION_OVERRIDE=$MESA_GL_VERSION_OVERRIDE"
echo "  GALLIUM_DRIVER=$GALLIUM_DRIVER"

# Configure LWJGL/GLFW for software rendering and disable narrator
export JAVA_OPTS="-Dfml.earlyWindowControl=false -Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true -Dorg.lwjgl.util.DebugLoader=true -Dcom.mojang.text2speech.disable=true"

echo "[startClient] JAVA_OPTS=$JAVA_OPTS"
echo "[startClient] Starting Xvfb and runClient.sh..."
echo "[startClient] Note: First run will download Minecraft and dependencies (may take 5-10 minutes)..."
echo ""

# Start Xvfb with proper GLX configuration for software rendering
# Note: -s takes server args as a single quoted string
# Use sh -c to properly handle the command and ensure output goes to stdout
exec sh -c 'xvfb-run -a -s "-screen 0 1920x1080x24 -fbdir /tmp +extension GLX +render -noreset -ac" ./runClient.sh 2>&1'
EOF
