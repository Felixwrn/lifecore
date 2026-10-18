#!/usr/bin/env python3
"""Create the verified-update manifest from the exact built plugin JAR."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile


def create_manifest(jar: Path, repository: str, tag: str) -> dict:
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid GitHub repository")
    version = tag.removeprefix("v")
    if not re.fullmatch(r"(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)", version):
        raise ValueError("A stable semantic-version release tag is required")
    with zipfile.ZipFile(jar) as archive:
        raw = archive.read("plugin.yml").decode("utf-8")
        fields = {}
        for line in raw.splitlines():
            if line and not line[0].isspace() and ":" in line:
                key, value = line.split(":", 1)
                fields[key] = value.strip().strip("'\"")
        if fields.get("name") != "LifeCore" or fields.get("version") != version or fields.get("api-version") != "26.3":
            raise ValueError("Release tag and LifeCore JAR metadata do not match")
        if fields.get("main") != "de.felix.lifeplugin.Main" or "de/felix/lifeplugin/Main.class" not in archive.namelist():
            raise ValueError("Unexpected plugin main class")
    return {"schemaVersion": 1, "version": version, "minecraft": "26.3",
            "jarUrl": f"https://github.com/{repository}/releases/download/{tag}/{jar.name}",
            "sha256": hashlib.sha256(jar.read_bytes()).hexdigest()}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.write_text(json.dumps(create_manifest(args.jar, args.repository, args.tag), indent=2) + "\n")
