# Baritone Integration for Minecraft 1.21.11 (NeoForge)

We now ship `libs/baritone-api-neoforge-1.15.0.jar` for compile-time and `baritone-unoptimized-neoforge-1.15.0.jar` for runtime. `build_baritone.sh` patches the NeoForge mods metadata to allow 1.21.11 until upstream ships a matching release, and rewrites the runtime jar to use the 1.21.11 `RenderType` package. The runtime jar is loaded as a mod (copied into the mods folder), not just a classpath library.

Baritone is enabled by default via config (`enableBaritone=true`). You can override it at runtime with `MINECRAFT_ROS2_ENABLE_BARITONE=true|false`.

## Rebuilding (only if you need to)

We keep a local cache under `data/cache/baritone/1.21.11` so you only download once. If the cache exists, `build_baritone.sh` will just copy the API jar into `minecraft_ros2/libs/` and the runtime jar into `data/built_mod/`.

```bash
cd krenaia
./build_baritone.sh
```

1. Fork https://github.com/cabaletta/baritone
2. Update its `gradle.properties` to `minecraft_version=1.21.11` and a matching loader
3. Build the API jar
4. Copy the API jar to `libs/baritone-api-neoforge-1.15.0.jar`
5. Copy the runtime jar to `data/built_mod/baritone-unoptimized-neoforge-1.15.0.jar`

## What’s already in place

- ROS 2 integration topics and test scripts remain the same
- The runtime jar is copied into the mods folder at runtime
- Docker images copy the jar automatically when building the client/server
