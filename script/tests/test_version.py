"""A single product version reaches manifests and rejects stale release artifacts."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
import deploy


class VersionTest(unittest.TestCase):
    def fixture(self, root, version="7.8.9"):
        (root / "backend").mkdir()
        pom = root / "backend/pom.xml"
        pom.write_text(f'''<project xmlns="http://maven.apache.org/POM/4.0.0">
        <parent><version>3.3.5</version></parent><artifactId>investory</artifactId><version>{version}</version>
        <dependencies><dependency><version>1.2.3</version></dependency></dependencies></project>''', encoding="utf-8")
        for directory in ["frontend", "desktop"]:
            (root / directory).mkdir()
            (root / directory / "package.json").write_text(json.dumps({"version": "1.0.0", "dependencies": {"example": "2.0.0"}}))
            (root / directory / "package-lock.json").write_text(json.dumps({"version": "1.0.0", "packages": {
                "": {"version": "1.0.0"}, "node_modules/example": {"version": "2.0.0"}}}))
        return pom

    def synchronize(self, root, check=False):
        code = 'import {syncVersions} from ' + json.dumps((ROOT / "tools/version.mjs").as_uri()) + '; syncVersions(process.argv[1], process.argv[2] === "check");'
        return subprocess.run(["node", "--input-type=module", "-e", code, str(root), "check" if check else "sync"],
                              capture_output=True, text=True, encoding="utf-8")

    def test_change_only_pom_synchronizes_all_manifests_without_dependency_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pom = self.fixture(root)
            self.assertEqual(self.synchronize(root).returncode, 0)
            for version in ["7.8.9", "7.8.10"]:
                pom.write_text(pom.read_text().replace("7.8.9", version))
                self.assertEqual(self.synchronize(root).returncode, 0)
                with patch.object(deploy, "BACKEND_POM", str(pom)):
                    self.assertEqual(deploy.read_current_version(), version)
                for part in ["frontend", "desktop"]:
                    package = json.loads((root / part / "package.json").read_text())
                    lock = json.loads((root / part / "package-lock.json").read_text())
                    self.assertEqual(package["version"], version)
                    self.assertEqual(lock["version"], version)
                    self.assertEqual(lock["packages"][""]["version"], version)
                    self.assertEqual(package["dependencies"]["example"], "2.0.0")
                    self.assertEqual(lock["packages"]["node_modules/example"]["version"], "2.0.0")
            before = (root / "frontend/package-lock.json").read_bytes()
            self.assertEqual(self.synchronize(root, check=True).returncode, 0)
            self.assertEqual(before, (root / "frontend/package-lock.json").read_bytes())

    def test_check_rejects_drift_without_modifying_files_and_invalid_source_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pom = self.fixture(root)
            before = (root / "frontend/package.json").read_bytes()
            self.assertNotEqual(self.synchronize(root, check=True).returncode, 0)
            self.assertEqual(before, (root / "frontend/package.json").read_bytes())
            pom.write_text(pom.read_text().replace("7.8.9", "07.8.9"))
            self.assertNotEqual(self.synchronize(root).returncode, 0)
            with patch.object(deploy, "BACKEND_POM", str(pom)), self.assertRaises(ValueError):
                deploy.read_current_version()

    def test_deployment_rejects_jar_from_previous_release(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "backend/target").mkdir(parents=True)
            with zipfile.ZipFile(root / "backend/target/investory.jar", "w") as jar:
                jar.writestr("META-INF/maven/com.investory/investory/pom.properties", "version=6.4.0\n")
            with patch.object(deploy, "REPO_ROOT", str(root)):
                deploy.verify_jar_version("6.4.0")
                with self.assertRaisesRegex(RuntimeError, "JAR version differs"):
                    deploy.verify_jar_version("6.4.1")


if __name__ == "__main__":
    unittest.main()
