# Baritone Integration for Minecraft 1.21.11 (NeoForge)

We now ship `libs/baritone-api-forge-1.15.0.jar`, which targets Minecraft 1.21.6+ and works with 1.21.11 on NeoForge. No extra steps are needed for local testing—the jar is already wired in `build.gradle` and copied alongside the mod when you build.

## Pull the tracked artifact

```bash
cd minecraft_ros2
dvc pull libs/baritone-api-forge-1.15.0.jar.dvc
```

## Rebuilding (only if you need to)

1. Fork https://github.com/cabaletta/baritone
2. Update its `gradle.properties` to `minecraft_version=1.21.11` and a matching loader
3. Build the API jar
4. Copy the result to `libs/baritone-api-forge-1.15.0.jar`
5. Prefer to pin a published release with DVC:
   `dvc import-url --force https://github.com/cabaletta/baritone/releases/download/v1.15.0/baritone-api-forge-1.15.0.jar libs/baritone-api-forge-1.15.0.jar`
   then `git add libs/baritone-api-forge-1.15.0.jar.dvc`

## What’s already in place

- ROS 2 integration topics and test scripts remain the same
- The mod classpath includes Baritone via the `libs` folder
- Docker images copy the jar automatically when building the client/server
