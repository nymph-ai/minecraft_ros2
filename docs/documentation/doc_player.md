# Player Control

The player in `minecraft_ros2` can be controlled by publishing a Twist message.

### ROS 2 Interface

| Type                  | Topic Name           |
| --------------------- | -------------------- |
| `geometry_msgs/Twist` | `/cmd_vel`           |

### Optional Baritone Integration

If the [Baritone](https://github.com/cabaletta/baritone) client mod is installed alongside `minecraft_ros2`, you can steer it directly from ROS 2. This allows autonomous pathfinding to specific locations inside the Minecraft world.

| Type                  | Topic Name                    | Notes |
| --------------------- | ----------------------------- | ----- |
| `geometry_msgs/Point` | `/minecraft/baritone/goal`    | Target block position for Baritone to navigate towards. |
| `std_msgs/Empty`      | `/minecraft/baritone/cancel`  | Cancel the current Baritone path following task. |

Baritone control topics are only available when the mod is detected on the running client. No additional configuration is required inside `minecraft_ros2`.