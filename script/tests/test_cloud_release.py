"""Exercise deployment filesystem transactions in a disposable local directory."""

import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from tools.cloud_deploy import REMOTE_FILES, runtime_path


class CloudReleaseTest(unittest.TestCase):
    def test_runtime_mapping_excludes_secrets_caches_and_evaluations(self):
        self.assertEqual(runtime_path("script/model_transport.py"), "script/model_transport.py")
        self.assertEqual(runtime_path("backend/src/main/python/stocksage_alpha/src/factors/technical/__init__.py"),
                         "stocksage_alpha/src/factors/technical/__init__.py")
        for path in ["script/config.ini", "script/config.json", "script/evals/cases/test.json", "script/tests/test_runtime.py",
                     "backend/src/main/python/stocksage_alpha/.env", "frontend/.env.electron"]:
            self.assertIsNone(runtime_path(path), path)
        with self.assertRaises(ValueError):
            runtime_path("script/../config.ini")

    def run_program(self, payload, operation):
        return subprocess.run([sys.executable, "-c", REMOTE_FILES],
                              input=json.dumps({**payload, "operation": operation}),
                              capture_output=True, text=True)

    def fixture(self, directory):
        root = Path(directory)
        stage = root / "releases/test-release"
        (root / "script").mkdir()
        (root / "script/keep.py").write_text("old")
        (root / "script/dead.py").write_text("dead")
        (root / "script/config.ini").write_text("runtime settings must stay private")
        (stage / "files/script").mkdir(parents=True)
        (stage / "files/script/keep.py").write_text("new")
        (stage / "files/script/added.py").write_text("added")
        return {"root": str(root), "stage": str(stage), "removed": ["script/dead.py"],
                "hashes": {"script/keep.py": hashlib.sha256(b"new").hexdigest(),
                           "script/added.py": hashlib.sha256(b"added").hexdigest()}}

    def test_activation_and_rollback_preserve_runtime_config_and_restore_deletions(self):
        with tempfile.TemporaryDirectory() as directory:
            payload = self.fixture(directory)
            for operation in ["backup", "activate"]:
                result = self.run_program(payload, operation)
                self.assertEqual(result.returncode, 0, result.stderr)
            root = Path(directory)
            self.assertEqual((root / "script/keep.py").read_text(), "new")
            self.assertFalse((root / "script/dead.py").exists())
            self.assertTrue((root / "script/added.py").exists())
            result = self.run_program(payload, "restore")
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((root / "script/keep.py").read_text(), "old")
            self.assertEqual((root / "script/dead.py").read_text(), "dead")
            self.assertFalse((root / "script/added.py").exists())
            self.assertEqual((root / "script/config.ini").read_text(), "runtime settings must stay private")

    def test_corrupt_staging_never_changes_live_files(self):
        with tempfile.TemporaryDirectory() as directory:
            payload = self.fixture(directory)
            payload["hashes"]["script/keep.py"] = "incorrect"
            self.assertNotEqual(self.run_program(payload, "backup").returncode, 0)
            self.assertEqual((Path(directory) / "script/keep.py").read_text(), "old")

    def test_traversal_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            payload = self.fixture(directory)
            payload["removed"] = ["../outside.txt"]
            self.assertNotEqual(self.run_program(payload, "backup").returncode, 0)


if __name__ == "__main__":
    unittest.main()
