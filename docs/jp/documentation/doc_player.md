# プレイヤー操作

minecraft_ros2内のプレイヤーはTwistメッセージを与えることで操作することができます。

### ROS 2 Interface

| Type                  | Topic Name           |
| --------------------- | -------------------- |
| `geometry_msgs/Twist` | `/cmd_vel`           |

### オプション: Baritone 連携

`minecraft_ros2` と一緒に [Baritone](https://github.com/cabaletta/baritone) クライアント MOD が導入されている場合、ROS 2 から直接 Baritone を制御できます。これにより、Minecraft ワールド内の指定位置まで自律経路探索を実行できます。

| Type                  | Topic Name                    | 説明 |
| --------------------- | ----------------------------- | ---- |
| `geometry_msgs/Point` | `/minecraft/baritone/goal`    | Baritone が向かう目標ブロック位置 |
| `std_msgs/Empty`      | `/minecraft/baritone/cancel`  | 現在の Baritone 経路追従を中止 |

Baritone がクライアント側で検出された場合にのみ、これらのトピックが有効になります。`minecraft_ros2` 側で追加の設定は不要です。