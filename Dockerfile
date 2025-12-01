FROM ghcr.io/minecraft-ros2/ros2_java:latest AS modbuilder
ENV ROS2JAVA_INSTALL_PATH=/ws/ros2_java_ws/install
WORKDIR /src

COPY gradlew gradle.properties settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew
RUN ./gradlew --no-daemon dependencies || true
COPY . .
RUN ./gradlew --no-daemon clean jar

FROM ghcr.io/minecraft-ros2/ros2_java:latest

ENV DEBIAN_FRONTEND=noninteractive
ENV MOD_JAR_CACHE=/opt/minecraft_ros2_mod
SHELL ["/bin/bash", "-c"]
WORKDIR /ws/minecraft_ros2

# Install system dependencies early so this layer stays cached unless the Dockerfile changes.
RUN <<EOF
    apt-get update
    apt-get install -y \
        ros-humble-rviz2 \
        xvfb \
        xauth \
        x11-utils \
        xserver-xorg-core \
        xserver-xorg-video-dummy \
        libxi6 \
        libxrender1 \
        libxtst6 \
        libgl1 \
        libglu1-mesa \
        libgl1-mesa-dri \
        libgl1-mesa-glx \
        libglx-mesa0 \
        libglapi-mesa \
        libosmesa6 \
        mesa-utils \
        mesa-vulkan-drivers
    apt-get clean
    rm -rf /var/lib/apt/lists
EOF

COPY . .
COPY --from=modbuilder /src/build/libs/ ${MOD_JAR_CACHE}/

RUN install -m 644 headless_xorg.conf /etc/X11/xorg-headless.conf && \
    chmod +x headless_client_entry.sh

COPY --chmod=755 <<EOF runRviz.sh
    source /opt/ros/humble/setup.bash
    rviz2 -d ./minecraft.rviz
EOF
