FROM ghcr.io/minecraft-ros2/ros2_java:latest AS modbuilder
ENV ROS2JAVA_INSTALL_PATH=/ws/ros2_java_ws/install
WORKDIR /src

# Install Java 21 for NeoGradle (requires Java 17+)
RUN apt-get update && \
    apt-get install -y openjdk-21-jdk && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Set Java 21 as the default Java version
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY minecraft_ros2/gradlew minecraft_ros2/gradle.properties minecraft_ros2/settings.gradle minecraft_ros2/build.gradle ./
COPY minecraft_ros2/gradle ./gradle
RUN chmod +x gradlew
RUN ./gradlew --no-daemon dependencies || true
COPY minecraft_ros2/ .
RUN ./gradlew --no-daemon clean jar
RUN ./gradlew --no-daemon create1.21.11ClientExtraJar writeMinecraftClasspathClient

FROM ghcr.io/minecraft-ros2/ros2_java:latest

ENV DEBIAN_FRONTEND=noninteractive
ENV MOD_JAR_CACHE=/opt/minecraft_ros2_mod
SHELL ["/bin/bash", "-c"]
WORKDIR /ws/minecraft_ros2

# Install system dependencies early so this layer stays cached unless the Dockerfile changes.
RUN <<EOF
    apt-get update
    apt-get install -y \
        openjdk-21-jdk \
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
        mesa-vulkan-drivers \
        python3-pip
    apt-get clean
    rm -rf /var/lib/apt/lists
EOF

# Ensure Gradle and client run with Java 21 in the runtime image.
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY minecraft_ros2/ .
COPY --from=modbuilder /src/build/libs/ ${MOD_JAR_CACHE}/
COPY --from=modbuilder /src/.gradle /ws/minecraft_ros2/.gradle
COPY --from=modbuilder /root/.gradle /root/.gradle
COPY --from=modbuilder /src/build /ws/minecraft_ros2/build

# Copy image capture service files (build context is now parent directory)
RUN mkdir -p /opt/image_capture
COPY bot_controller/image_capture.py /opt/image_capture/image_capture.py
COPY bot_controller/image_bridge.py /opt/image_capture/image_bridge.py
COPY bot_controller/requirements.txt /opt/image_capture/requirements.txt

# Install Python dependencies for image capture
RUN pip3 install --no-cache-dir -r /opt/image_capture/requirements.txt

RUN install -m 644 headless_xorg.conf /etc/X11/xorg-headless.conf && \
    chmod +x headless_client_entry.sh && \
    chmod +x /opt/image_capture/image_capture.py && \
    chmod +x /opt/image_capture/image_bridge.py

COPY --chmod=755 <<EOF runRviz.sh
    source /opt/ros/humble/setup.bash
    rviz2 -d ./minecraft.rviz
EOF
