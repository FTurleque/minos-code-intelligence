from pathlib import Path
import json
import shutil
import subprocess
import tempfile

PACKAGES = [
    (
        "@sourcegraph/scip-python",
        "0.6.6",
        "sha512-qoKL1Rggg0o5newAFbCFAKlS0AjWxG5MA+mC28BtgxOv0DhO4zdL8u7151FxEppDpXMVvm7+yXSjXotoVH9cMQ==",
        "scip-python-package-lock.json",
    ),
    (
        "@sourcegraph/scip-typescript",
        "0.4.0",
        "sha512-k+AtsrqmS41Sd5qjkZlHcmvoSQIvBOonRj4jpgp0KNFM6aqvMGpdSuPUqrUcg8ENTKjUbfaUVszgQwq3bCOvwA==",
        "scip-typescript-package-lock.json",
    ),
]

RESOURCE_DIR = Path("minos-provider-scip/src/main/resources/com/minos/adapter/scip/runtime")
RESOURCE_DIR.mkdir(parents=True, exist_ok=True)

for package, version, integrity, filename in PACKAGES:
    with tempfile.TemporaryDirectory(prefix="minos-npm-lock-") as temp_value:
        temp = Path(temp_value)
        package_json = {
            "private": True,
            "dependencies": {package: version},
        }
        (temp / "package.json").write_text(
            json.dumps(package_json, indent=2) + "\n", encoding="utf-8", newline="\n"
        )
        subprocess.run(
            [
                "npm",
                "install",
                "--package-lock-only",
                "--ignore-scripts",
                "--no-audit",
                "--no-fund",
                "--package-lock-only",
            ],
            cwd=temp,
            check=True,
        )
        lock_path = temp / "package-lock.json"
        lock = json.loads(lock_path.read_text(encoding="utf-8"))
        if lock.get("lockfileVersion") != 3:
            raise SystemExit(f"{package}: expected npm lockfileVersion 3")
        entry = lock.get("packages", {}).get(f"node_modules/{package}")
        if not isinstance(entry, dict):
            raise SystemExit(f"{package}: root package is missing from generated lock")
        if entry.get("version") != version:
            raise SystemExit(f"{package}: generated root version mismatch: {entry.get('version')}")
        if entry.get("integrity") != integrity:
            raise SystemExit(
                f"{package}: registry integrity drift: expected {integrity}, got {entry.get('integrity')}"
            )
        # Ensure the install graph itself never opts into lifecycle execution from this generator.
        shutil.copyfile(lock_path, RESOURCE_DIR / filename)
        print(f"generated {RESOURCE_DIR / filename} with {len(lock.get('packages', {}))} locked entries")
