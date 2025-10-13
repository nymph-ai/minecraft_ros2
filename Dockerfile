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
        xserver-xorg-video-dummy \
        mesa-vulkan-drivers \
        procps \
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

COPY --chmod=755 <<EOF runRviz.sh
    source /opt/ros/kilted/setup.bash
    rviz2 -d ./minecraft.rviz
EOF

COPY --chmod=755 <<'EOF' startClient.sh
#!/bin/bash
set -euo pipefail

echo "[startClient] Starting Minecraft ROS2 client..."
echo "[startClient] Memory info:"
free -h || echo "[startClient] free command not available"

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

# Configure LWJGL/GLFW for software rendering
export JAVA_OPTS="-Dfml.earlyWindowControl=false -Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true -Dorg.lwjgl.util.DebugLoader=true"

echo "[startClient] JAVA_OPTS=$JAVA_OPTS"
echo "[startClient] Starting Xvfb and runClient.sh..."
echo "[startClient] Note: First run will download Minecraft and dependencies (may take 5-10 minutes)..."
echo ""

# Start Xvfb with proper GLX configuration for software rendering
# Note: -s takes server args as a single quoted string
# Use sh -c to properly handle the command and ensure output goes to stdout
exec sh -c 'xvfb-run -a -s "-screen 0 1920x1080x24 -fbdir /tmp +extension GLX +render -noreset -ac" ./runClient.sh 2>&1'
EOF
