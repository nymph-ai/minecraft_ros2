#!/bin/bash
# Build the mod jar and place it in data/built_mod without pulling extra deps.
set -euo pipefail

cd "$(dirname "$0")"

export ROS2JAVA_INSTALL_PATH=/ws/ros2_java_ws/install

if [ -d "$ROS2JAVA_INSTALL_PATH" ]; then
    echo "Using local ROS2 Java installation"
    ./gradlew clean jar --no-daemon
else
    echo "Using Docker container for build environment only..."
    docker run --rm \
        -v "$(pwd):/work" \
        -v "$(pwd)/../data/gradle_cache:/root/.gradle" \
        -w /work \
        ghcr.io/minecraft-ros2/ros2_java:latest \
        bash -c "source /ws/ros2_java_ws/install/setup.bash && ./gradlew clean jar --no-daemon --no-build-cache"
fi

mkdir -p ../data/built_mod
for jar in build/libs/minecraft_ros2-*.jar; do
    cp -f "$jar" ../data/built_mod/
done

ls -lh ../data/built_mod/minecraft_ros2-*.jar
