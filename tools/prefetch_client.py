#!/usr/bin/env python3
import json
import os
import shutil
import sys
import time
import http.client
import urllib.request
import urllib.error
import zipfile
from pathlib import Path


MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest.json"
OS_NAME = "linux"


def download(url: str, dest: Path, *, retries: int | None = None) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists():
        return
    attempts = retries if retries is not None else int(os.environ.get("PREFETCH_RETRIES", "5"))
    backoff = float(os.environ.get("PREFETCH_BACKOFF", "0.5"))
    for attempt in range(1, attempts + 1):
        try:
            with urllib.request.urlopen(url) as response, dest.open("wb") as handle:
                shutil.copyfileobj(response, handle)
            return
        except (urllib.error.URLError, http.client.HTTPException, OSError) as exc:
            if attempt >= attempts:
                raise
            wait = backoff * attempt
            print(f"Download failed ({exc}); retrying in {wait:.1f}s: {url}")
            time.sleep(wait)


def load_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def resolve_home(base: Path) -> Path:
    if (base / "versions").exists():
        return base
    if (base / ".minecraft" / "versions").exists():
        return base / ".minecraft"
    return base


def download_version_json(versions_dir: Path, version_id: str) -> Path:
    manifest_path = versions_dir / "version_manifest.json"
    download(MANIFEST_URL, manifest_path)
    manifest = load_json(manifest_path)
    for entry in manifest.get("versions", []):
        if entry.get("id") == version_id:
            version_json = versions_dir / version_id / f"{version_id}.json"
            download(entry["url"], version_json)
            return version_json
    raise RuntimeError(f"Version {version_id} not found in manifest")


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


def rule_applies(rule: dict) -> bool:
    os_rule = rule.get("os")
    if not os_rule:
        return True
    name = os_rule.get("name")
    if name and name != OS_NAME:
        return False
    return True


def is_allowed(rules: list) -> bool:
    allowed = False
    for rule in rules:
        if rule_applies(rule):
            allowed = rule.get("action", "allow") == "allow"
    return allowed


def collect_libraries(version_data: dict) -> list[dict]:
    libraries = []
    if "inheritsFrom" in version_data:
        parent_id = version_data["inheritsFrom"]
        parent_json = download_version_json(VERSIONS_DIR, parent_id)
        parent_data = load_json(parent_json)
        libraries.extend(collect_libraries(parent_data))
    libraries.extend(version_data.get("libraries", []))
    return libraries


def download_libraries(libraries: list[dict], libraries_dir: Path) -> None:
    for library in libraries:
        rules = library.get("rules")
        if rules and not is_allowed(rules):
            continue
        downloads = library.get("downloads", {})
        artifact = downloads.get("artifact")
        if artifact and artifact.get("url") and artifact.get("path"):
            download(artifact["url"], libraries_dir / artifact["path"])
        natives = library.get("natives", {})
        classifier = natives.get(OS_NAME)
        if classifier:
            classifiers = downloads.get("classifiers", {})
            native_artifact = classifiers.get(classifier)
            if native_artifact and native_artifact.get("url") and native_artifact.get("path"):
                download(native_artifact["url"], libraries_dir / native_artifact["path"])


def extract_natives(libraries: list[dict], libraries_dir: Path, natives_dir: Path) -> None:
    natives_dir.mkdir(parents=True, exist_ok=True)
    marker = natives_dir / ".natives_extracted"
    if marker.exists():
        return
    for library in libraries:
        rules = library.get("rules")
        if rules and not is_allowed(rules):
            continue
        natives = library.get("natives", {})
        classifier = natives.get(OS_NAME)
        if not classifier:
            continue
        downloads = library.get("downloads", {})
        classifiers = downloads.get("classifiers", {})
        native_artifact = classifiers.get(classifier)
        if not native_artifact or not native_artifact.get("path"):
            continue
        jar_path = libraries_dir / native_artifact["path"]
        if not jar_path.exists():
            continue
        with zipfile.ZipFile(jar_path, "r") as archive:
            for member in archive.namelist():
                if member.startswith("META-INF/") or member.endswith("/"):
                    continue
                target = natives_dir / member
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(member) as source, target.open("wb") as dest:
                    shutil.copyfileobj(source, dest)
    marker.touch()


def ensure_version_jar(version_data: dict, versions_dir: Path) -> None:
    jar_id = version_data.get("jar", version_data.get("id"))
    jar_dir = versions_dir / jar_id
    jar_path = jar_dir / f"{jar_id}.jar"
    if jar_path.exists():
        return
    source = version_data.get("downloads", {}).get("client", {})
    url = source.get("url")
    if not url:
        raise RuntimeError(f"No client download URL for version {jar_id}")
    download(url, jar_path)


def ensure_logging_config(version_data: dict, assets_dir: Path) -> None:
    logging_cfg = version_data.get("logging", {}).get("client", {}).get("file", {})
    url = logging_cfg.get("url")
    log_id = logging_cfg.get("id")
    if not url or not log_id:
        return
    dest = assets_dir / "log_configs" / log_id
    download(url, dest)


def ensure_assets(version_data: dict, assets_dir: Path) -> None:
    asset_index = version_data.get("assetIndex", {})
    url = asset_index.get("url")
    index_id = asset_index.get("id")
    if not url or not index_id:
        return
    index_path = assets_dir / "indexes" / f"{index_id}.json"
    download(url, index_path)
    data = load_json(index_path)
    if os.environ.get("PREFETCH_ASSETS", "true").lower() in ("0", "false", "no"):
        return
    objects_dir = assets_dir / "objects"
    objects = list(data.get("objects", {}).values())
    total = len(objects)
    log_interval = int(os.environ.get("PREFETCH_LOG_INTERVAL", "500"))
    start = time.time()
    downloaded = 0
    failed = 0
    processed = 0
    print(f"Prefetching {total} asset objects into {objects_dir}")
    for obj in objects:
        sha1 = obj.get("hash")
        if not sha1:
            continue
        subdir = sha1[:2]
        dest = objects_dir / subdir / sha1
        if dest.exists():
            processed += 1
            if processed % log_interval == 0 or processed == total:
                elapsed = max(time.time() - start, 0.001)
                rate = processed / elapsed
                print(
                    f"Prefetch progress: {processed}/{total} "
                    f"(downloaded {downloaded}) @ {rate:.1f} obj/s"
                )
            continue
        obj_url = f"https://resources.download.minecraft.net/{subdir}/{sha1}"
        try:
            download(obj_url, dest)
        except Exception as exc:
            failed += 1
            print(f"Failed to download asset {sha1}: {exc}")
            processed += 1
            if processed % log_interval == 0 or processed == total:
                elapsed = max(time.time() - start, 0.001)
                rate = processed / elapsed
                print(
                    f"Prefetch progress: {processed}/{total} "
                    f"(downloaded {downloaded}, failed {failed}) @ {rate:.1f} obj/s"
                )
            continue
        downloaded += 1
        processed += 1
        if processed % log_interval == 0 or processed == total:
            elapsed = max(time.time() - start, 0.001)
            rate = processed / elapsed
            print(
                f"Prefetch progress: {processed}/{total} "
                f"(downloaded {downloaded}, failed {failed}) @ {rate:.1f} obj/s"
            )
    if failed:
        print(f"Prefetch completed with {failed} failed downloads. Re-run to retry.")


if __name__ == "__main__":
    BASE_HOME = Path(os.environ.get("MINECRAFT_HOME", "/opt/minecraft"))
    MINECRAFT_HOME = resolve_home(BASE_HOME)
    VERSIONS_DIR = MINECRAFT_HOME / "versions"
    assets_override = os.environ.get("MC_ASSETS_DIR")
    ASSETS_DIR = Path(assets_override) if assets_override else MINECRAFT_HOME / "assets"
    NEOFORGE_VERSION = os.environ.get("NEOFORGE_VERSION")
    VERSION_ID = os.environ.get("MC_VERSION_ID")

    version_json = find_version_json(VERSIONS_DIR, NEOFORGE_VERSION, VERSION_ID)
    version_data = load_json(version_json)

    if "inheritsFrom" in version_data:
        parent_id = version_data["inheritsFrom"]
        parent_json = download_version_json(VERSIONS_DIR, parent_id)
        parent_data = load_json(parent_json)
    else:
        parent_data = version_data

    ensure_version_jar(parent_data, VERSIONS_DIR)
    ensure_logging_config(parent_data, ASSETS_DIR)
    ensure_assets(parent_data, ASSETS_DIR)

    libraries = collect_libraries(version_data)
    download_libraries(libraries, MINECRAFT_HOME / "libraries")
    extract_natives(libraries, MINECRAFT_HOME / "libraries", MINECRAFT_HOME / "natives" / version_data["id"])

    print(f"Prepared client version {version_data['id']}")
