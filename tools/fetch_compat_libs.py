"""Fetch hash-pinned compile-only compatibility JARs resolved by Modrinth."""

from __future__ import annotations

import hashlib
import json
import shutil
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path
from pathlib import PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "_build" / "compat-libs-required.json"


def digest(path: Path, algorithm: str) -> str:
    result = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def destination_path(relative: str) -> Path:
    posix = PurePosixPath(relative)
    if (
        posix.is_absolute()
        or ".." in posix.parts
        or len(posix.parts) != 3
        or posix.parts[0] not in {"common", "fabric", "forge", "neoforge"}
        or not posix.parts[1].startswith("compat-libs-")
        or not posix.name.endswith(".jar")
    ):
        raise RuntimeError(f"Unsafe compatibility destination: {relative}")
    destination = ROOT.joinpath(*posix.parts).resolve()
    if not destination.is_relative_to(ROOT.resolve()):
        raise RuntimeError(f"Compatibility destination escapes source root: {relative}")
    return destination


def matches(path: Path, blob: dict) -> bool:
    return (
        path.is_file()
        and digest(path, "sha1") == blob["sha1"]
        and digest(path, "sha256") == blob["sha256"]
    )


def main() -> int:
    document = json.loads(MANIFEST.read_text(encoding="utf-8"))
    unresolved = []
    fetched = 0
    reused = 0
    for blob in document["blobs"]:
        destinations = [destination_path(item) for item in blob["destinations"]]
        existing = next((path for path in destinations if matches(path, blob)), None)
        if existing is not None:
            for destination in destinations:
                destination.parent.mkdir(parents=True, exist_ok=True)
                if destination != existing:
                    shutil.copyfile(existing, destination)
            reused += 1
            continue
        if blob["status"] not in {
            "modrinth_resolved",
            "curseforge_resolved",
            "nested_from_modrinth_resolved",
        }:
            unresolved.append(blob)
            continue
        with tempfile.NamedTemporaryFile(delete=False) as temporary:
            temp_path = Path(temporary.name)
        try:
            source_url = blob.get("download_url", blob.get("parent_download_url"))
            request = urllib.request.Request(
                source_url,
                headers={"User-Agent": "KURONAMI333-MDM-source-build/3.0.0"},
            )
            with urllib.request.urlopen(request, timeout=120) as response:
                with temp_path.open("wb") as output:
                    shutil.copyfileobj(response, output)
            if blob["status"] == "nested_from_modrinth_resolved":
                parent_path = temp_path
                with tempfile.NamedTemporaryFile(delete=False) as nested_temp:
                    temp_path = Path(nested_temp.name)
                    with zipfile.ZipFile(parent_path) as archive:
                        nested_temp.write(archive.read(blob["nested_entry"]))
                parent_path.unlink(missing_ok=True)
            if digest(temp_path, "sha1") != blob["sha1"]:
                raise RuntimeError(f"SHA-1 mismatch: {blob['destinations'][0]}")
            if digest(temp_path, "sha256") != blob["sha256"]:
                raise RuntimeError(f"SHA-256 mismatch: {blob['destinations'][0]}")
            for destination in destinations:
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(temp_path, destination)
            fetched += 1
        finally:
            temp_path.unlink(missing_ok=True)

    print(
        f"Verified {reused} existing and fetched {fetched} unique compatibility JARs."
    )
    if unresolved:
        print(
            "The following hash-pinned build inputs still need a verified source:",
            file=sys.stderr,
        )
        for blob in unresolved:
            print(
                f"- {blob['sha256']}: {', '.join(blob['destinations'])}",
                file=sys.stderr,
            )
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
