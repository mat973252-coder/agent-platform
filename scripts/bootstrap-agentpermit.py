"""Build the locked, public AgentPermit source into this project's isolated Maven repository."""

import hashlib
import json
import os
import subprocess
import tempfile
import urllib.request
import zipfile
from pathlib import Path


def main():
    root = Path(__file__).resolve().parents[1]
    lock = json.loads((root / ".mvn/agentpermit.lock.json").read_text(encoding="utf-8"))
    revision = lock["revision"]
    workspace = root / "var"
    workspace.mkdir(exist_ok=True)
    archive = workspace / f"agentpermit-{revision}.zip"
    cached = archive.is_file()
    if cached:
        contents = archive.read_bytes()
    else:
        url = f"https://codeload.github.com/mat973252-coder/agent-permit4j/zip/{revision}"
        with urllib.request.urlopen(url, timeout=60) as response:
            contents = response.read()
    if hashlib.sha256(contents).hexdigest() != lock["archiveSha256"]:
        raise RuntimeError(f"AgentPermit archive checksum mismatch: {archive}")
    if not cached:
        download = archive.with_suffix(".download")
        download.write_bytes(contents)
        download.replace(archive)
    with tempfile.TemporaryDirectory(prefix="ap-", dir=workspace) as temporary:
        directory = Path(temporary).resolve()
        if not directory.is_relative_to(workspace.resolve()):
            raise RuntimeError("Dependency build directory is outside the workspace")
        with zipfile.ZipFile(archive) as bundle:
            for member in bundle.infolist():
                relative = Path(member.filename).relative_to(f"agent-permit4j-{revision}")
                if not relative.parts:
                    continue
                if not (directory / relative).resolve().is_relative_to(directory):
                    raise RuntimeError("Dependency archive contains an unsafe path")
                target = directory / relative
                if member.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(bundle.read(member))
        source = directory
        wrapper = root / ("mvnw.cmd" if os.name == "nt" else "mvnw")
        print(f"Building AgentPermit {lock['version']} from {revision}", flush=True)
        subprocess.run([
            str(wrapper), "-B", "-ntp", "-f", str(source / "pom.xml"),
            f"-Dmaven.repo.local={workspace / 'maven-repository'}",
            "-pl", "agent-permit-execution", "-am", "install",
        ], cwd=root, check=True)


if __name__ == "__main__":
    main()
