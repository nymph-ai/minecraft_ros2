# Minecraft\_ros2
[![build mod](https://github.com/minecraft-ros2/minecraft_ros2/actions/workflows/build_test.yaml/badge.svg)](https://github.com/minecraft-ros2/minecraft_ros2/actions/workflows/build_test.yaml)
[![build docker image](https://github.com/minecraft-ros2/minecraft_ros2/actions/workflows/docker_build_main.yaml/badge.svg)](https://github.com/minecraft-ros2/minecraft_ros2/actions/workflows/docker_build_main.yaml)

A Minecraft mod that enables communication with ROS 2. It allows for LiDAR simulation, sensor data transmission, command reception, and more.

## Optional Integrations

- **Baritone pathfinding** – When the [Baritone](https://github.com/cabaletta/baritone) client mod is present, `minecraft_ros2` exposes ROS 2 topics for submitting navigation targets (`/minecraft/baritone/goal`) and cancelling active runs (`/minecraft/baritone/cancel`).

## Documents
- [**English Document**](https://minecraft-ros2.github.io/minecraft_ros2/)
- [**日本語ドキュメント**](https://minecraft-ros2.github.io/minecraft_ros2/jp/)
