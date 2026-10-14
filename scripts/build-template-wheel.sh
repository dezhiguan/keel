#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
"${KEEL_PYTHON_BIN:-python3}" - "$repo_root" <<'PY'
import base64
import csv
import hashlib
import io
import sys
import tomllib
import zipfile
from pathlib import Path

root = Path(sys.argv[1])
sdk = root / "sdk-python"
project = tomllib.loads((sdk / "pyproject.toml").read_text(encoding="utf-8"))["project"]
version = project["version"]
name = project["name"].replace("-", "_")
dist_info = f"{name}-{version}.dist-info"
wheel = sdk / "dist" / f"{name}-{version}-py3-none-any.whl"
wheel.parent.mkdir(parents=True, exist_ok=True)

files = {}
for source in (sdk / "keel").rglob("*"):
    if source.is_file() and "__pycache__" not in source.parts and source.suffix not in {".pyc", ".pyo"}:
        files[source.relative_to(sdk).as_posix()] = source.read_bytes()

config = tomllib.loads((sdk / "pyproject.toml").read_text(encoding="utf-8"))
included = config["tool"]["hatch"]["build"]["targets"]["wheel"]["force-include"]
for source, destination in included.items():
    files[destination] = (sdk / source).read_bytes()

metadata = [
    "Metadata-Version: 2.1",
    f"Name: {project['name']}",
    f"Version: {version}",
    f"Summary: {project['description']}",
    f"Requires-Python: {project['requires-python']}",
]
metadata.extend(f"Requires-Dist: {dependency}" for dependency in project["dependencies"])
files[f"{dist_info}/METADATA"] = ("\n".join(metadata) + "\n").encode()
files[f"{dist_info}/WHEEL"] = b"Wheel-Version: 1.0\nGenerator: keel-local-builder\nRoot-Is-Purelib: true\nTag: py3-none-any\n"
scripts = config["project"].get("scripts", {})
files[f"{dist_info}/entry_points.txt"] = ("[console_scripts]\n" + "".join(
    f"{command} = {target}\n" for command, target in scripts.items()
)).encode()

record = io.StringIO()
writer = csv.writer(record, lineterminator="\n")
for path, content in sorted(files.items()):
    digest = base64.urlsafe_b64encode(hashlib.sha256(content).digest()).rstrip(b"=").decode()
    writer.writerow([path, f"sha256={digest}", len(content)])
record_path = f"{dist_info}/RECORD"
writer.writerow([record_path, "", ""])
files[record_path] = record.getvalue().encode()

with zipfile.ZipFile(wheel, "w", compression=zipfile.ZIP_DEFLATED) as archive:
    for path, content in sorted(files.items()):
        archive.writestr(path, content)
print(wheel)
PY
