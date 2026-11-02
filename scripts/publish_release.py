#!/usr/bin/env python3
"""Baut, prüft und veröffentlicht ein Release lokal (signiert mit dem lokalen Release-Schlüssel, ohne Secrets in GitHub).

Ablauf:
  1. Arbeitsverzeichnis muss sauber sein, HEAD muss auf origin gepusht sein.
  2. Tests + Lint + signierte Release-APK (Gradle Wrapper).
  3. Assets erzeugen (scripts/make_release_assets.py: Signatur, Paket, Version, Hash).
  4. Tag v<versionName> setzen und pushen, Release mit allen Assets als Entwurf anlegen, dann veröffentlichen.

Aufruf:  python scripts/publish_release.py            (prüft und fragt vor dem Veröffentlichen nach)
         python scripts/publish_release.py --yes      (ohne Rückfrage)
Release Notes: docs/release-notes/v<versionName>.md (Pflicht).
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def run(*cmd: str, capture: bool = False) -> str:
    print("+", " ".join(cmd))
    res = subprocess.run(cmd, cwd=ROOT, text=True, capture_output=capture)
    if res.returncode != 0:
        if capture:
            print(res.stdout, res.stderr)
        sys.exit(f"Fehlgeschlagen: {' '.join(cmd)}")
    return res.stdout if capture else ""


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--yes", action="store_true")
    args = ap.parse_args()

    props = (ROOT / "gradle.properties").read_text(encoding="utf-8")
    version = re.search(r"^sofahub\.versionName=(.+)$", props, re.M).group(1).strip()
    tag = f"v{version}"
    notes = ROOT / "docs" / "release-notes" / f"{tag}.md"
    if not notes.exists():
        sys.exit(f"Release Notes fehlen: {notes}")
    if run("git", "status", "--porcelain", capture=True).strip():
        sys.exit("Arbeitsverzeichnis nicht sauber – erst committen.")
    run("git", "fetch", "origin")
    if run("git", "rev-parse", "HEAD", capture=True) != run("git", "rev-parse", "@{u}", capture=True):
        sys.exit("HEAD ist nicht gepusht.")
    if run("git", "tag", "-l", tag, capture=True).strip():
        sys.exit(f"Tag {tag} existiert bereits.")

    gradlew = str(ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew"))
    run(gradlew, "-p", str(ROOT), "--console=plain", "testDebugUnitTest", "lintRelease", "assembleRelease")
    dist = ROOT / "release" / tag
    run(sys.executable, "scripts/make_release_assets.py", "--apk", "app/build/outputs/apk/release/app-release.apk", "--out", str(dist), "--tag", tag)

    if not args.yes and input(f"{tag} jetzt veröffentlichen? [j/N] ").strip().lower() != "j":
        sys.exit("Abgebrochen – nichts veröffentlicht.")
    run("git", "tag", "-a", tag, "-m", f"Sofa Hub {version}")
    run("git", "push", "origin", tag)
    assets = [str(p) for p in sorted(dist.iterdir())]
    run("gh", "release", "create", tag, *assets, "--draft", "--title", f"Sofa Hub {version}", "--notes-file", str(notes), "--verify-tag")
    run("gh", "release", "edit", tag, "--draft=false", "--latest")
    print(f"Veröffentlicht: {tag}")


if __name__ == "__main__":
    main()
