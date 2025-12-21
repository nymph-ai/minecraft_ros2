#!/usr/bin/env python3
import json
import os
import subprocess
from pathlib import Path


OS_NAME = "linux"
FEATURE_FLAGS = {
    "has_quick_plays_support": False,
    "is_quick_play_singleplayer": False,
    "is_quick_play_multiplayer": False,
    "is_quick_play_realms": False,
    "has_custom_resolution": False,
    "is_demo_user": False,
}


def load_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def resolve_home(base: Path) -> Path:
    if (base / "versions").exists():
        return base
    if (base / ".minecraft" / "versions").exists():
        return base / ".minecraft"
    return base


def find_version_json(versions_dir: Path, neoforge_version: str | None, explicit: str | None) -> Path:
    if explicit:
        return versions_dir / explicit / f"{explicit}.json"
    candidates = []
    for candidate in versions_dir.glob("*/*.json"):
        if candidate.parent.name != candidate.stem:
            continue
        name = candidate.stem.lower()
        if "neoforge" not in name:
            continue
        if neoforge_version and neoforge_version not in candidate.stem:
            continue
        candidates.append(candidate)
    if not candidates:
        raise RuntimeError("No NeoForge version json found in versions directory")
    candidates.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    return candidates[0]


def rule_applies(rule: dict, features: dict) -> bool:
    os_rule = rule.get("os")
    if os_rule:
        name = os_rule.get("name")
        if name and name != OS_NAME:
            return False
    feature_rule = rule.get("features")
    if feature_rule:
        for key, expected in feature_rule.items():
            if features.get(key) != expected:
                return False
    return True


def is_allowed(rules: list, features: dict) -> bool:
    allowed = False
    for rule in rules:
        if rule_applies(rule, features):
            allowed = rule.get("action", "allow") == "allow"
    return allowed


def collect_libraries(version_data: dict, versions_dir: Path) -> list[dict]:
    libraries = []
    if "inheritsFrom" in version_data:
        parent_id = version_data["inheritsFrom"]
        parent_json = versions_dir / parent_id / f"{parent_id}.json"
        parent_data = load_json(parent_json)
        libraries.extend(collect_libraries(parent_data, versions_dir))
    libraries.extend(version_data.get("libraries", []))
    return libraries


def build_classpath(
    libraries: list[dict],
    libraries_dir: Path,
    versions_dir: Path,
    version_data: dict,
    jar_override: Path | None = None,
) -> str:
    entries = []
    for library in libraries:
        rules = library.get("rules")
        if rules and not is_allowed(rules, FEATURE_FLAGS):
            continue
        downloads = library.get("downloads", {})
        artifact = downloads.get("artifact")
        if artifact and artifact.get("path"):
            entries.append(str(libraries_dir / artifact["path"]))
    if jar_override and jar_override.exists():
        entries.append(str(jar_override))
    else:
        jar_id = version_data.get("jar", version_data.get("id"))
        jar_path = versions_dir / jar_id / f"{jar_id}.jar"
        if jar_path.exists():
            entries.append(str(jar_path))
    return ":".join(entries)


def append_ros2_classpath(classpath: list[str]) -> None:
    ros2_root = Path(os.environ.get("ROS2JAVA_INSTALL_PATH", "/ws/ros2_java_ws/install"))
    packages = ["rcljava", "rcljava_common"]
    messages = [
        "geometry_msgs",
        "std_msgs",
        "builtin_interfaces",
        "sensor_msgs",
        "tf2_msgs",
        "simulation_interfaces",
        "minecraft_msgs",
    ]
    for pkg in packages:
        jar = ros2_root / pkg / "share" / pkg / "java" / f"{pkg}.jar"
        if jar.exists():
            classpath.append(str(jar))
    for msg in messages:
        jar = ros2_root / msg / "share" / msg / "java" / f"{msg}_messages.jar"
        if jar.exists():
            classpath.append(str(jar))


def append_extra_libs(classpath: list[str]) -> None:
    extra_dir = Path(os.environ.get("MC_EXTRA_LIB_DIR", "/opt/minecraft_ros2_libs"))
    if not extra_dir.exists():
        return
    for jar in sorted(extra_dir.glob("*.jar")):
        classpath.append(str(jar))


def extend_java_library_path(jvm_args: list[str]) -> None:
    ros2_root = Path(os.environ.get("ROS2JAVA_INSTALL_PATH", "/ws/ros2_java_ws/install"))
    extra_paths = []
    for base in (ros2_root / "rcljava" / "lib" / "jni", ros2_root / "rcljava_common" / "lib"):
        if base.exists():
            extra_paths.append(base)
    if ros2_root.exists():
        for path in ros2_root.rglob("*.so"):
            lib_dir = path.parent
            if lib_dir not in extra_paths:
                extra_paths.append(lib_dir)
    if not extra_paths:
        return
    ld_library = os.environ.get("LD_LIBRARY_PATH", "")
    for path in extra_paths:
        if str(path) not in ld_library.split(":"):
            ld_library = f"{ld_library}:{path}" if ld_library else str(path)
    os.environ["LD_LIBRARY_PATH"] = ld_library
    for idx, arg in enumerate(jvm_args):
        if arg.startswith("-Djava.library.path="):
            current = arg.split("=", 1)[1]
            for path in extra_paths:
                if str(path) in current.split(":"):
                    continue
                current = f"{current}:{path}"
            jvm_args[idx] = f"-Djava.library.path={current}"
            return
    jvm_args.append(f"-Djava.library.path={':'.join(str(p) for p in extra_paths)}")


def apply_realms_bypass(jvm_args: list[str]) -> None:
    if os.environ.get("MC_SKIP_REALMS_CHECK", "false").lower() != "true":
        return
    flags = {
        "MC_DEBUG_ENABLED": "true",
        "MC_DEBUG_BYPASS_REALMS_VERSION_CHECK": "true",
    }
    for key, value in flags.items():
        flag = f"-D{key}={value}"
        if flag not in jvm_args:
            jvm_args.append(flag)


def apply_netty_native_settings(jvm_args: list[str]) -> None:
    if os.environ.get("MC_NETTY_NO_NATIVE", "false").lower() != "true":
        return
    flag = "-Dio.netty.transport.noNative=true"
    if flag not in jvm_args:
        jvm_args.append(flag)


def resolve_args(args: list, variables: dict) -> list[str]:
    resolved = []
    for entry in args:
        if isinstance(entry, str):
            resolved.append(entry)
            continue
        if not isinstance(entry, dict):
            continue
        rules = entry.get("rules")
        if rules and not is_allowed(rules, FEATURE_FLAGS):
            continue
        value = entry.get("value", [])
        if isinstance(value, str):
            resolved.append(value)
        else:
            resolved.extend(value)
    expanded = []
    for arg in resolved:
        for key, value in variables.items():
            arg = arg.replace("${" + key + "}", value)
        expanded.append(arg)
    return expanded


def merge_arguments(parent_args: dict | None, child_args: dict | None) -> dict:
    merged = {"jvm": [], "game": []}
    for key in ("jvm", "game"):
        for source in (parent_args, child_args):
            if source and source.get(key):
                merged[key].extend(source[key])
    return merged


def launch():
    base_home = Path(os.environ.get("MINECRAFT_HOME", "/opt/minecraft"))
    minecraft_home = resolve_home(base_home)
    versions_dir = minecraft_home / "versions"
    libraries_dir = minecraft_home / "libraries"
    version_id = os.environ.get("MC_VERSION_ID")
    neoforge_version = os.environ.get("NEOFORGE_VERSION")

    version_json = find_version_json(versions_dir, neoforge_version, version_id)
    version_data = load_json(version_json)
    if "inheritsFrom" in version_data:
        parent_id = version_data["inheritsFrom"]
        parent_json = versions_dir / parent_id / f"{parent_id}.json"
        parent_data = load_json(parent_json)
    else:
        parent_data = version_data

    libraries = collect_libraries(version_data, versions_dir)
    classpath_entries = build_classpath(
        libraries,
        libraries_dir,
        versions_dir,
        version_data,
    ).split(":")
    append_ros2_classpath(classpath_entries)
    append_extra_libs(classpath_entries)
    classpath = ":".join(classpath_entries)

    assets_root = minecraft_home / "assets"
    assets_override = os.environ.get("MC_ASSETS_DIR")
    if assets_override:
        assets_root = Path(assets_override)
        assets_root.mkdir(parents=True, exist_ok=True)
    asset_index = parent_data.get("assetIndex", {})
    assets_index_name = asset_index.get("id", "legacy")
    natives_dir = minecraft_home / "natives" / version_data["id"]
    mc_server = os.environ.get("MC_SERVER")
    quickplay_path = os.environ.get("MC_QUICKPLAY_PATH")
    if mc_server and not quickplay_path:
        quickplay_path = str(Path(os.environ.get("MC_GAME_DIR", "/ws/minecraft_ros2/run")) / "quickplay")
    if mc_server:
        FEATURE_FLAGS["has_quick_plays_support"] = True
        FEATURE_FLAGS["is_quick_play_multiplayer"] = True

    variables = {
        "auth_player_name": os.environ.get("MC_USERNAME", "Player"),
        "auth_uuid": os.environ.get("MC_UUID", "00000000000000000000000000000000"),
        "auth_access_token": os.environ.get("MC_ACCESS_TOKEN", ""),
        "clientid": os.environ.get("MC_CLIENT_ID", ""),
        "auth_xuid": os.environ.get("MC_XUID", ""),
        "user_type": os.environ.get("MC_USER_TYPE", "msa"),
        "game_directory": os.environ.get("MC_GAME_DIR", "/ws/minecraft_ros2/run"),
        "assets_root": str(assets_root),
        "assets_index_name": assets_index_name,
        "quickPlayPath": quickplay_path or "",
        "quickPlaySingleplayer": "",
        "quickPlayMultiplayer": mc_server or "",
        "quickPlayRealms": "",
        "resolution_width": "854",
        "resolution_height": "480",
        "natives_directory": str(natives_dir),
        "classpath": classpath,
        "library_directory": str(libraries_dir),
        "version_name": version_data.get("id", "unknown"),
        "version_type": version_data.get("type", "release"),
        "launcher_name": "minecraft_ros2",
        "launcher_version": "1",
    }

    arguments = merge_arguments(parent_data.get("arguments"), version_data.get("arguments"))
    if arguments.get("jvm") or arguments.get("game"):
        jvm_args = resolve_args(arguments.get("jvm", []), variables)
        game_args = resolve_args(arguments.get("game", []), variables)
    else:
        jvm_args = [
            "-Djava.library.path=" + variables["natives_directory"],
            "-cp",
            classpath,
        ]
        game_args = variables.get("minecraftArguments", "").split()

    if mc_server and not any(arg.startswith("--quickPlay") for arg in game_args):
        game_args.extend(["--quickPlayMultiplayer", mc_server])

    main_class = version_data.get("mainClass")
    if not main_class:
        raise RuntimeError("mainClass not found in version json")

    if main_class == "net.neoforged.fml.startup.Client":
        for flag in (
            "-Dfml.deobfuscatedEnvironment=false",
            "-Dneoforge.fml.deobfuscatedEnvironment=false",
            "-Dneoforge.fml.production=true",
        ):
            if flag not in jvm_args:
                jvm_args.append(flag)
    extend_java_library_path(jvm_args)
    apply_realms_bypass(jvm_args)
    apply_netty_native_settings(jvm_args)

    print(f"[launcher] quickPlayMultiplayer={mc_server or ''}")
    print(f"[launcher] mainClass={main_class}")
    print(f"[launcher] jvm_args={' '.join(jvm_args)}")
    print(f"[launcher] game_args={' '.join(game_args)}")

    cmd = ["java"] + jvm_args + [main_class] + game_args
    os.execvp(cmd[0], cmd)


if __name__ == "__main__":
    launch()
