#!/usr/bin/env python3
"""LayerBridge 签名工具（PC 端，私钥不出本机）。

用法：
  scripts/lb-sign.py init                 生成根证书 -> certs/lb-root.json
                                          私钥 -> ~/.config/layerbridge/root-key.pem (0600)
  scripts/lb-sign.py sign-deb <pkg.deb>   生成 <pkg.deb>.sig
  scripts/lb-sign.py show                 显示当前根证书信息

格式与 app 内 PackageSigner/Certificates 对齐：
  canonical = "LBPA-CERT-V1\n<serial>\n<subject>\n<issuerKeyId>\n<keyId>\n"
              "<publicKey>\n<notBefore>\n<notAfter>\n<isCA>\n<pathLen>\n"
              "<usages>\n<packagePrefixes>\n"
  签名数据 deb = "LB-DEB-V1\n<sha256 hex>\n"
"""
import base64
import hashlib
import json
import os
import secrets
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

ROOT_DIR = Path(__file__).resolve().parent.parent
CERT_PATH = ROOT_DIR / "certs" / "lb-root.json"
KEY_PATH = Path.home() / ".config" / "layerbridge" / "root-key.pem"


def now_utc() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def key_id(public_der: bytes) -> str:
    return hashlib.sha256(public_der).hexdigest()


def canonical(cert: dict) -> bytes:
    usages = ",".join(sorted(cert["usages"]))
    prefixes = ",".join(sorted(cert["packagePrefixes"])) or "-"
    path_len = "-" if cert.get("pathLen") is None else str(cert["pathLen"])
    lines = [
        "LBPA-CERT-V1",
        cert["serial"],
        cert["subject"],
        cert["issuerKeyId"],
        cert["keyId"],
        cert["publicKey"],
        cert["notBefore"],
        cert["notAfter"],
        "true" if cert["isCA"] else "false",
        path_len,
        usages,
        prefixes,
    ]
    return ("\n".join(lines) + "\n").encode("utf-8")


def ecdsa_sign(private_key, data: bytes) -> bytes:
    return private_key.sign(data, ec.ECDSA(hashes.SHA256()))


def ecdsa_verify(public_key, data: bytes, signature: bytes) -> bool:
    from cryptography.exceptions import InvalidSignature

    try:
        public_key.verify(signature, data, ec.ECDSA(hashes.SHA256()))
        return True
    except InvalidSignature:
        return False


def load_key() -> ec.EllipticCurvePrivateKey:
    if not KEY_PATH.is_file():
        sys.exit(f"缺少私钥：{KEY_PATH}（先运行 init）")
    return serialization.load_pem_private_key(KEY_PATH.read_bytes(), password=None)


def load_cert() -> dict:
    if not CERT_PATH.is_file():
        sys.exit(f"缺少根证书：{CERT_PATH}（先运行 init）")
    return json.loads(CERT_PATH.read_text())


def cmd_init(args: list[str]) -> None:
    subject = args[0] if args else "LayerBridge Root CA"
    if CERT_PATH.is_file() and KEY_PATH.is_file() and "--force" not in args:
        sys.exit(f"已存在根证书/私钥；如需重签加 --force（会覆盖 {CERT_PATH}）")

    private_key = ec.generate_private_key(ec.SECP256R1())
    public_der = private_key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    kid = key_id(public_der)
    start = datetime.now(timezone.utc) - timedelta(days=1)
    end = datetime.now(timezone.utc) + timedelta(days=3650)
    cert = {
        "version": 1,
        "serial": secrets.token_hex(8),
        "subject": subject,
        "issuerKeyId": kid,
        "keyId": kid,
        "publicKey": b64(public_der),
        "notBefore": start.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "notAfter": end.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "isCA": True,
        "pathLen": 1,
        "usages": ["cert-signing", "package-signing"],
        "packagePrefixes": [],
    }
    signature = ecdsa_sign(private_key, canonical(cert))
    cert["signature"] = b64(signature)

    if not ecdsa_verify(private_key.public_key(), canonical(cert), signature):
        sys.exit("自签名校验失败")

    CERT_PATH.parent.mkdir(parents=True, exist_ok=True)
    KEY_PATH.parent.mkdir(parents=True, exist_ok=True)
    CERT_PATH.write_text(json.dumps(cert, ensure_ascii=False, indent=2) + "\n")
    KEY_PATH.write_bytes(
        private_key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    os.chmod(KEY_PATH, 0o600)
    print(f"root  : {CERT_PATH}")
    print(f"key   : {KEY_PATH} (0600)")
    print(f"subject: {cert['subject']}")
    print(f"keyId : {kid}")
    print(f"valid : {cert['notBefore']} .. {cert['notAfter']}")


def cmd_sign_deb(args: list[str]) -> None:
    if len(args) != 1:
        sys.exit("用法：lb-sign.py sign-deb <pkg.deb>")
    deb = Path(args[0])
    if not deb.is_file():
        sys.exit(f"文件不存在：{deb}")
    cert = load_cert()
    private_key = load_key()
    deb_hash = hashlib.sha256(deb.read_bytes()).hexdigest()
    data = f"LB-DEB-V1\n{deb_hash}\n".encode("utf-8")
    signature = ecdsa_sign(private_key, data)
    block = {
        "version": 1,
        "algorithm": "ecdsa-p256-sha256",
        "keyId": cert["keyId"],
        "certChain": [],
        "signature": b64(signature),
        "signedAt": now_utc(),
    }
    out = Path(str(deb) + ".sig")
    out.write_text(json.dumps(block, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(f"signed: {deb.name}")
    print(f"sha256: {deb_hash}")
    print(f"sig   : {out}")


def cmd_show(_: list[str]) -> None:
    cert = load_cert()
    ok = ecdsa_verify(
        ec.EllipticCurvePublicKey.from_encoded_point(
            ec.SECP256R1(),
            base64.b64decode(cert["publicKey"])[-65:],
        ),
        canonical(cert),
        base64.b64decode(cert["signature"]),
    )
    print(f"subject : {cert['subject']}")
    print(f"keyId   : {cert['keyId']}")
    print(f"valid   : {cert['notBefore']} .. {cert['notAfter']}")
    print(f"self-sig: {'ok' if ok else 'BAD'}")


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cmd, rest = sys.argv[1], sys.argv[2:]
    if cmd == "init":
        cmd_init(rest)
    elif cmd == "sign-deb":
        cmd_sign_deb(rest)
    elif cmd == "show":
        cmd_show(rest)
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
