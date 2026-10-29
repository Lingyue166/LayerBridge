#!/usr/bin/env python3
"""LayerBridge 打包工具（PC 端）：目录 / deb -> LBPA，并签名。

用法：
  scripts/lb-pack.py from-deb pkg.deb -o pkg.lbpa [--no-sign]
  scripts/lb-pack.py from-dir <目录> --name N --version V [--arch aarch64] \
                     [--description D] -o pkg.lbpa [--no-sign]

from-dir 的输入是前缀布局（bin/... etc/...），工具自动包成 files/。
签名使用 certs/lb-root.json 与 ~/.config/layerbridge/root-key.pem。
"""
import argparse
import base64
import hashlib
import io
import json
import lzma
import os
import re
import struct
import sys
import tarfile
from datetime import datetime, timezone
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

ROOT = Path(__file__).resolve().parent.parent
CERT = ROOT / "certs" / "lb-root.json"
KEY = Path.home() / ".config" / "layerbridge" / "root-key.pem"
MAGIC = b"LBPA\r\n\x1a\n"
FORMAT = "LAYERBRIDGEPACKONANDROID"


def now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_control(text: str) -> dict:
    fields, key = {}, None
    for line in text.replace("\r\n", "\n").split("\n"):
        if line[:1] in (" ", "\t") and key:
            fields[key] += "\n" + line.strip()
        elif ":" in line:
            key, value = line.split(":", 1)
            key = key.strip()
            fields[key] = value.strip()
    return fields


def split_relations(value: str | None) -> list[str] | None:
    if not value:
        return None
    items, depth, cur = [], 0, ""
    for ch in value:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth = max(0, depth - 1)
        if ch == "," and depth == 0:
            if cur.strip():
                items.append(cur.strip())
            cur = ""
        else:
            cur += ch
    if cur.strip():
        items.append(cur.strip())
    return items or None


def read_ar(path: Path) -> dict[str, bytes]:
    data = path.read_bytes()
    if not data.startswith(b"!<arch>\n"):
        sys.exit(f"不是 ar 归档：{path}")
    entries, off = {}, 8
    while off + 60 <= len(data):
        hdr = data[off:off + 60]
        name = hdr[:16].decode("utf-8", "replace").strip().rstrip("/")
        size = int(hdr[48:58].decode().strip())
        body = data[off + 60:off + 60 + size]
        entries[name] = body
        off += 60 + size + (size % 2)
    return entries


def open_tar(name: str, body: bytes) -> tarfile.TarFile:
    if name.endswith(".gz"):
        return tarfile.open(fileobj=io.BytesIO(body), mode="r:gz")
    if name.endswith(".xz"):
        return tarfile.open(fileobj=io.BytesIO(body), mode="r:xz")
    if name == "data.tar" or name.endswith(".tar"):
        return tarfile.open(fileobj=io.BytesIO(body), mode="r:")
    sys.exit(f"不支持的压缩：{name}")


def add_entry(tar: tarfile.TarFile, name: str, info: tarfile.TarInfo | None = None,
              data: bytes | None = None) -> None:
    is_dir = name.endswith("/")
    ti = tarfile.TarInfo(name.rstrip("/") if is_dir else name)
    ti.mtime = int(datetime.now().timestamp())
    if info is not None:
        ti.type = info.type
        ti.mode = info.mode & 0o777
        ti.uid = ti.gid = 0
        ti.uname = ti.gname = "root"
        if info.issym():
            ti.linkname = info.linkname
        if info.islnk():
            ti.linkname = info.linkname
    else:
        ti.type = tarfile.DIRTYPE if is_dir else tarfile.REGTYPE
        ti.mode = 0o755
    if data is not None:
        ti.size = len(data)
        tar.addfile(ti, io.BytesIO(data))
    else:
        tar.addfile(ti)


def build_payload_from_deb(deb: Path) -> tuple[bytes, dict]:
    entries = read_ar(deb)
    ctrl_name = next(n for n in entries if n.startswith("control.tar"))
    data_name = next(n for n in entries if n.startswith("data.tar"))

    control, scripts, files = {}, {}, []
    with open_tar(ctrl_name, entries[ctrl_name]) as tar:
        for m in tar.getmembers():
            base = os.path.basename(m.name)
            if not m.isfile():
                continue
            body = tar.extractfile(m).read()
            if base == "control":
                control = parse_control(body.decode("utf-8", "replace"))
            elif base in ("preinst", "postinst", "prerm", "postrm"):
                scripts[base] = body
    with open_tar(data_name, entries[data_name]) as tar:
        for m in tar.getmembers():
            if m.name in ("./", "."):
                continue
            files.append((m, tar.extractfile(m).read() if m.isfile() else None))

    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w", format=tarfile.PAX_FORMAT) as out:
        for base, body in scripts.items():
            add_entry(out, f"control/{base}", data=body)
        for m, body in files:
            is_dir_entry = m.isdir() or m.name.endswith("/")
            rel = m.name
            while rel.startswith("./"):
                rel = rel[2:]
            rel = rel.rstrip("/")
            if not rel:
                continue
            if is_dir_entry:
                add_entry(out, f"files/{rel}/")
            elif m.issym():
                ti = tarfile.TarInfo(f"files/{rel}")
                ti.type = tarfile.SYMTYPE
                ti.linkname = m.linkname
                ti.mode = m.mode & 0o777
                out.addfile(ti)
            elif m.islnk():
                ti = tarfile.TarInfo(f"files/{rel}")
                ti.type = tarfile.LNKTYPE
                ti.linkname = m.linkname.removeprefix("./")
                ti.mode = m.mode & 0o777
                out.addfile(ti)
            elif m.isfile():
                add_entry(out, f"files/{rel}", info=m, data=body)

    conffiles = control.get("Conffiles", "").split()
    keep = [c.removeprefix("/") for c in conffiles if c.startswith("/")]
    meta = {
        "name": control.get("Package", ""),
        "version": control.get("Version", ""),
        "architecture": control.get("Architecture", "aarch64"),
        "description": control.get("Description", "").split("\n")[0],
        "relations": {
            "depends": split_relations(control.get("Depends")),
            "preDepends": split_relations(control.get("Pre-Depends")),
            "provides": split_relations(control.get("Provides")),
            "conflicts": split_relations(control.get("Conflicts")),
            "breaks": split_relations(control.get("Breaks")),
            "replaces": split_relations(control.get("Replaces")),
        },
        "scripts": {k: f"control/{k}" for k in scripts},
        "upgrade": {"keep": keep} if keep else None,
    }
    return buf.getvalue(), meta


def build_payload_from_dir(root: Path) -> tuple[bytes, dict]:
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w", format=tarfile.PAX_FORMAT) as out:
        for path in sorted(root.rglob("*")):
            rel = path.relative_to(root).as_posix()
            if path.is_dir():
                add_entry(out, f"files/{rel}/")
            elif path.is_symlink():
                ti = tarfile.TarInfo(f"files/{rel}")
                ti.type = tarfile.SYMTYPE
                ti.linkname = os.readlink(path)
                out.addfile(ti)
            else:
                add_entry(out, f"files/{rel}", data=path.read_bytes())
    return buf.getvalue(), {}


def sign_block(manifest_bytes: bytes, payload_sha: str) -> dict:
    if not (CERT.is_file() and KEY.is_file()):
        sys.exit("缺少根证书或私钥，无法签名；可用 --no-sign")
    cert = json.loads(CERT.read_text())
    key = serialization.load_pem_private_key(KEY.read_bytes(), password=None)
    data = f"LBPA-PKG-V1\n{hashlib.sha256(manifest_bytes).hexdigest()}\n{payload_sha}\n".encode()
    sig = key.sign(data, ec.ECDSA(hashes.SHA256()))
    return {
        "version": 1,
        "algorithm": "ecdsa-p256-sha256",
        "keyId": cert["keyId"],
        "certChain": [],
        "signature": base64.b64encode(sig).decode(),
        "signedAt": now(),
    }


def write_lbpa(out: Path, payload_tar: bytes, meta: dict, sign: bool) -> None:
    payload = lzma.compress(payload_tar, preset=6)
    manifest = {
        "format": FORMAT,
        "formatVersion": 1,
        "createdBy": "lb-pack.py/0.1.0",
        "createdAt": now(),
        "package": {
            "name": meta["name"],
            "version": meta["version"],
            "architecture": meta["architecture"],
            "description": meta.get("description", ""),
        },
        "relations": {k: v for k, v in (meta.get("relations") or {}).items() if v},
        "payload": {
            "compression": "xz",
            "size": len(payload),
            "sha256": hashlib.sha256(payload).hexdigest(),
            "uncompressedSize": len(payload_tar),
            "sha256Uncompressed": hashlib.sha256(payload_tar).hexdigest(),
        },
        "scripts": meta.get("scripts") or None,
        "upgrade": meta.get("upgrade") or None,
    }
    manifest = {k: v for k, v in manifest.items() if v not in (None, {}, [])}
    manifest_bytes = json.dumps(manifest, ensure_ascii=False, separators=(",", ":")).encode()

    flags = 0x0001 if sign else 0
    head = MAGIC + struct.pack(">HHI", 1, flags, len(manifest_bytes))
    out.write_bytes(head + manifest_bytes + payload)
    if sign:
        block = sign_block(manifest_bytes, manifest["payload"]["sha256"])
        blob = json.dumps(block, ensure_ascii=False, separators=(",", ":")).encode()
        with out.open("ab") as f:
            f.write(struct.pack(">I", len(blob)) + blob)


def main() -> None:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)

    d = sub.add_parser("from-deb")
    d.add_argument("deb")
    d.add_argument("-o", "--out", required=True)
    d.add_argument("--no-sign", action="store_true")

    r = sub.add_parser("from-dir")
    r.add_argument("dir")
    r.add_argument("--name", required=True)
    r.add_argument("--version", required=True)
    r.add_argument("--arch", default="aarch64")
    r.add_argument("--description", default="")
    r.add_argument("-o", "--out", required=True)
    r.add_argument("--no-sign", action="store_true")

    args = ap.parse_args()
    if args.cmd == "from-deb":
        payload, meta = build_payload_from_deb(Path(args.deb))
    else:
        payload, meta = build_payload_from_dir(Path(args.dir))
        meta.update(name=args.name, version=args.version,
                    architecture=args.arch, description=args.description)
    if not meta.get("name") or not meta.get("version"):
        sys.exit("缺少包名或版本")
    write_lbpa(Path(args.out), payload, meta, not args.no_sign)
    print(f"packed {args.out}")
    print(f"  package {meta['name']} {meta['version']} {meta['architecture']}")
    print(f"  payload {len(payload)} B (tar {len(payload)} B)")


if __name__ == "__main__":
    main()
