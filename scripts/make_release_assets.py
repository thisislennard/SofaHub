#!/usr/bin/env python3
"""Erzeugt die Release-Assets aus der gebauten, signierten APK.

Alle Metadaten (applicationId, versionCode, versionName, minSdk) werden aus der APK selbst gelesen, nicht doppelt gepflegt.
Ausgabe in <out>/:
  SofaHub-v<versionName>-universal.apk
  update.json
  SHA256SUMS

Aufruf:  python scripts/make_release_assets.py --apk app/build/outputs/apk/release/app-release.apk --out release [--tag v1.2.0]
Bricht ab, wenn die APK unsigniert/debug-signiert ist, der Tag nicht passt oder die applicationId falsch ist.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

APPLICATION_ID = "de.sofahub.app"


def build_tool(name: str) -> str:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        local = Path(__file__).resolve().parent.parent / "local.properties"
        if local.exists():
            m = re.search(r"sdk\.dir=(.*)", local.read_text(encoding="utf-8"))
            if m:
                sdk = m.group(1).strip().replace("\\:", ":").replace("\\\\", "\\")
    if not sdk:
        sys.exit("ANDROID_HOME nicht gesetzt")
    tools = sorted((Path(sdk) / "build-tools").iterdir(), key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", p.name)])
    for d in reversed(tools):
        for candidate in (d / name, d / f"{name}.exe", d / f"{name}.bat"):
            if candidate.exists():
                return str(candidate)
    sys.exit(f"{name} nicht gefunden")


def badging(apk: Path) -> dict:
    out = subprocess.run([build_tool("aapt2"), "dump", "badging", str(apk)], capture_output=True, text=True, check=True).stdout
    pkg = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", out)
    sdk = re.search(r"(?:minSdkVersion|sdkVersion):'(\d+)'", out)
    if not pkg or not sdk:
        sys.exit("APK-Metadaten nicht lesbar")
    return {"applicationId": pkg.group(1), "versionCode": int(pkg.group(2)), "versionName": pkg.group(3), "minSdk": int(sdk.group(1))}


def verify_signature(apk: Path) -> str:
    res = subprocess.run([build_tool("apksigner"), "verify", "--print-certs", "-v", str(apk)], capture_output=True, text=True)
    if res.returncode != 0:
        sys.exit("APK ist nicht gültig signiert:\n" + res.stderr)
    if "CN=Android Debug" in res.stdout:
        sys.exit("APK ist mit dem Debug-Schlüssel signiert – kein Release")
    m = re.search(r"certificate SHA-256 digest: ([0-9a-f]{64})", res.stdout)
    return m.group(1) if m else "?"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--tag", help="erwarteter Git-Tag, z. B. v1.2.0")
    args = ap.parse_args()

    meta = badging(args.apk)
    if meta["applicationId"] != APPLICATION_ID:
        sys.exit(f"Falsche applicationId {meta['applicationId']}")
    if not re.fullmatch(r"\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?", meta["versionName"]):
        sys.exit(f"versionName {meta['versionName']} ist kein SemVer")
    if args.tag and args.tag != f"v{meta['versionName']}":
        sys.exit(f"Tag {args.tag} passt nicht zu versionName {meta['versionName']}")
    cert = verify_signature(args.apk)

    args.out.mkdir(parents=True, exist_ok=True)
    name = f"SofaHub-v{meta['versionName']}-universal.apk"
    target = args.out / name
    shutil.copyfile(args.apk, target)
    digest = sha256(target)
    manifest = {
        "schemaVersion": 1,
        **meta,
        "apkAssetName": name,
        "apkSizeBytes": target.stat().st_size,
        "sha256": digest,
    }
    (args.out / "update.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    manifest_digest = sha256(args.out / "update.json")
    (args.out / "SHA256SUMS").write_text(f"{digest}  {name}\n{manifest_digest}  update.json\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2))
    print(f"Signaturzertifikat SHA-256: {cert}")


if __name__ == "__main__":
    main()
