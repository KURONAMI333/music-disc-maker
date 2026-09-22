"""Fetch hash-pinned compile-only compatibility JARs resolved by Modrinth."""

from __future__ import annotations

import fnmatch
import hashlib
import io
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
USER_AGENT = "KURONAMI333-MDM-source-build/3.0.0"

# 生成 stub のための固定値。jar のコンテナ（並び順・タイムスタンプ・圧縮）は生成ツールの
# 実装で変わるため、同じ入力から同じバイト列は保証できない。生成 stub は
# content_sha256（全エントリ名 + 各エントリの SHA-256）で検証し、ファイル自体の
# SHA-1/SHA-256 は「実際に同梱していたバイト列」の記録としてだけ残す (2026-09-19)。
STUB_TIME = (1980, 1, 1, 0, 0, 0)
STUB_COMPRESSION = zipfile.ZIP_DEFLATED
STUB_COMPRESS_LEVEL = 9


def digest(path: Path, algorithm: str) -> str:
    result = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def content_digest(payload: bytes) -> str:
    """jar のコンテナ差を無視して、中身（エントリ名と各エントリの SHA-256）だけを固定する。

    上流 MOD から機械生成する stub 用。7MB 程度の読み込みなので、そのままメモリに載せる。
    """
    result = hashlib.sha256()
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        entries = sorted(
            (name, hashlib.sha256(archive.read(name)).hexdigest())
            for name in archive.namelist()
            if not name.endswith("/")
        )
    for name, entry_digest in entries:
        result.update(name.encode("utf-8"))
        result.update(b"\0")
        result.update(entry_digest.encode("ascii"))
        result.update(b"\n")
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
    if not path.is_file():
        return False
    if "content_sha256" in blob:
        return content_digest(path.read_bytes()) == blob["content_sha256"]
    return (
        digest(path, "sha1") == blob["sha1"]
        and digest(path, "sha256") == blob["sha256"]
    )


def download(blob: dict, url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=600) as response:
        payload = response.read()
    expected = blob.get("upstream", blob)
    if hashlib.sha1(payload).hexdigest() != expected["sha1"]:
        raise RuntimeError(f"SHA-1 mismatch: {url}")
    if hashlib.sha256(payload).hexdigest() != expected["sha256"]:
        raise RuntimeError(f"SHA-256 mismatch: {url}")
    return payload


def build_stub(upstream: bytes, extract: dict) -> bytes:
    """上流の MOD jar から API 部分だけを取り出し、バイト再現可能な stub を作る。

    Create / Valkyrien Skies の stub は「手で抜き出した」と記録されていたが、実際には
    `extract.prefix` の前方一致で機械的に再現できる (2026-09-19 に全エントリの内容を
    上流 jar と突合して確認)。`nested` は VS のように API クラスを入れ子 jar へ
    分けている MOD のための指定。
    """
    prefix = extract["prefix"]
    entries: dict[str, bytes] = {}
    with zipfile.ZipFile(io.BytesIO(upstream)) as outer:
        for name in outer.namelist():
            if name.startswith(prefix) and not name.endswith("/"):
                entries[name] = outer.read(name)
        for pattern in extract.get("nested", []):
            for name in outer.namelist():
                if name.endswith("/") or not fnmatch.fnmatchcase(name, pattern):
                    continue
                with zipfile.ZipFile(io.BytesIO(outer.read(name))) as inner:
                    for inner_name in inner.namelist():
                        if inner_name.startswith(prefix) and not inner_name.endswith(
                            "/"
                        ):
                            entries.setdefault(inner_name, inner.read(inner_name))
    buffer = io.BytesIO()
    with zipfile.ZipFile(
        buffer,
        "w",
        compression=STUB_COMPRESSION,
        compresslevel=STUB_COMPRESS_LEVEL,
    ) as jar:
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, date_time=STUB_TIME)
            info.compress_type = STUB_COMPRESSION
            info.external_attr = 0o644 << 16
            jar.writestr(info, entries[name])
    return buffer.getvalue()


def main() -> int:
    document = json.loads(MANIFEST.read_text(encoding="utf-8"))
    unresolved = []
    fetched = 0
    generated = 0
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
        if blob["status"] == "generated_from_upstream":
            payload = build_stub(
                download(blob, blob["upstream"]["url"]), blob["extract"]
            )
            if content_digest(payload) != blob["content_sha256"]:
                raise RuntimeError(
                    "generated stub does not match the pinned content: "
                    f"{blob['destinations'][0]}"
                )
            for destination in destinations:
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(payload)
            generated += 1
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
        f"Verified {reused} existing, fetched {fetched} and generated {generated} "
        "compatibility JARs."
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
