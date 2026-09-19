#!/usr/bin/env python3
"""Validate and stage one unified Mobius desktop/mobile GitHub Release."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def find_local(filename: str, source_dirs: list[Path]) -> Path | None:
    for source_dir in source_dirs:
        candidate = source_dir / filename
        if candidate.is_file():
            return candidate
    return None


def stage_build(build: dict, output: Path, source_dirs: list[Path]) -> dict:
    filename = build["file"]
    destination = output / filename
    local = find_local(filename, source_dirs)
    if local:
        shutil.copy2(local, destination)
    else:
        request = urllib.request.Request(build["url"], headers={"User-Agent": "Mobius-Apps-Release/1.0"})
        with urllib.request.urlopen(request, timeout=300) as response, destination.open("wb") as handle:
            shutil.copyfileobj(response, handle)

    actual_size = destination.stat().st_size
    actual_sha256 = sha256_of(destination)
    expected_size = int(build["size"])
    expected_sha256 = build["sha256"].lower()
    if actual_size != expected_size:
        raise RuntimeError(f"{filename}: size {actual_size} != expected {expected_size}")
    if actual_sha256 != expected_sha256:
        raise RuntimeError(f"{filename}: sha256 {actual_sha256} != expected {expected_sha256}")
    return {
        key: build[key]
        for key in ("platform", "arch", "format", "file", "size", "sha256")
    }


def write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def build_notes(config: dict) -> str:
    release_tag = config["releaseTag"]
    release_base = f"https://github.com/mobius-system/mobius/releases/download/{release_tag}"
    desktop = config["desktop"]
    mobile = config["mobile"]
    ios = config["ios"]
    desktop_lines = "\n".join(
        f"- [{item['file']}]({release_base}/{item['file']})" for item in desktop["builds"]
    )
    mobile_lines = "\n".join(
        f"- [{item['file']}]({release_base}/{item['file']})" for item in mobile["builds"]
    )
    return f"""Mobius desktop and mobile clients are published together in this release.

## Desktop v{desktop['version']}

{desktop_lines}

## Android v{mobile['version']}

{mobile_lines}

## iOS

- TestFlight: {ios['url']}

Every downloadable asset is covered by `SHA256SUMS.txt`. The machine-readable release metadata is available in `apps-manifest.json`, `desktop-manifest.json`, and `mobile-manifest.json`.

---

本 Release 同时发布 Mobius 桌面端与移动端客户端。

## 桌面端 v{desktop['version']}

{desktop_lines}

## Android v{mobile['version']}

{mobile_lines}

## iOS

- TestFlight：{ios['url']}

所有可下载文件均可通过 `SHA256SUMS.txt` 校验。机器可读的发布信息见 `apps-manifest.json`、`desktop-manifest.json` 和 `mobile-manifest.json`。
"""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--source-dir", action="append", default=[], type=Path)
    args = parser.parse_args()

    config = json.loads(args.config.read_text(encoding="utf-8"))
    if not str(config.get("releaseTag", "")).startswith("apps-v"):
        raise RuntimeError("releaseTag must start with apps-v")
    args.output.mkdir(parents=True, exist_ok=True)

    desktop_builds = [stage_build(item, args.output, args.source_dir) for item in config["desktop"]["builds"]]
    mobile_builds = [stage_build(item, args.output, args.source_dir) for item in config["mobile"]["builds"]]
    generated_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")

    desktop_manifest = {"version": config["desktop"]["version"], "generatedAt": generated_at, "builds": desktop_builds}
    mobile_manifest = {"version": config["mobile"]["version"], "generatedAt": generated_at, "builds": mobile_builds}
    apps_manifest = {
        "releaseTag": config["releaseTag"],
        "generatedAt": generated_at,
        "desktop": desktop_manifest,
        "mobile": mobile_manifest,
        "ios": config["ios"],
    }
    write_json(args.output / "desktop-manifest.json", desktop_manifest)
    write_json(args.output / "mobile-manifest.json", mobile_manifest)
    write_json(args.output / "apps-manifest.json", apps_manifest)

    checksum_files = [args.output / item["file"] for item in desktop_builds + mobile_builds]
    checksum_files += [args.output / "desktop-manifest.json", args.output / "mobile-manifest.json", args.output / "apps-manifest.json"]
    checksums = "".join(f"{sha256_of(path)}  {path.name}\n" for path in checksum_files)
    (args.output / "SHA256SUMS.txt").write_text(checksums, encoding="utf-8")
    (args.output / "release-notes.md").write_text(build_notes(config), encoding="utf-8")
    print(json.dumps({"releaseTag": config["releaseTag"], "assets": len(checksum_files) + 1}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
