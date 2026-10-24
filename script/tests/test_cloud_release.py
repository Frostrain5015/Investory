"""Exercise deployment filesystem transactions in a disposable local directory."""

import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch, MagicMock

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from tools.cloud_deploy import REMOTE_FILES, runtime_path, retired_runtime_path, deploy_cloud


class CloudReleaseTest(unittest.TestCase):
    def test_offline_release_requires_explicit_opt_in_and_a_clean_commit(self):
        with patch("tools.cloud_deploy.git", side_effect=["", "head", "remote-head"]), \
                patch("tools.cloud_deploy.remote") as remote:
            with self.assertRaisesRegex(RuntimeError, "Push this release"):
                deploy_cloud(None, Path("."), "6.3.6", "http://localhost")
            remote.assert_not_called()
        with patch("tools.cloud_deploy.git", side_effect=["dirty"]), \
                patch("tools.cloud_deploy.remote") as remote:
            with self.assertRaisesRegex(RuntimeError, "Commit the complete release"):
                deploy_cloud(None, Path("."), "6.3.6", "http://localhost", allow_unpushed=True)
            remote.assert_not_called()
        with patch("tools.cloud_deploy.git", side_effect=["", "head", "remote-head"]), \
                patch("tools.cloud_deploy.remote", side_effect=RuntimeError("remote-read-boundary")) as remote:
            with self.assertRaisesRegex(RuntimeError, "remote-read-boundary"):
                deploy_cloud(None, Path("."), "6.3.6", "http://localhost", allow_unpushed=True)
            self.assertEqual(remote.call_count, 1)

    def test_runtime_mapping_excludes_secrets_caches_and_evaluations(self):
        self.assertEqual(runtime_path("script/model_transport.py"), "script/model_transport.py")
        self.assertIsNone(runtime_path("backend/src/main/python/stocksage_alpha/src/factors/technical/__init__.py"))
        self.assertEqual(retired_runtime_path("backend/src/main/python/stocksage_alpha/src/factors/technical/__init__.py"),
                         "stocksage_alpha/src/factors/technical/__init__.py")
        for path in ["script/config.ini", "script/config.json", "script/evals/cases/test.json", "script/tests/test_runtime.py",
                     "backend/src/main/python/stocksage_alpha/.env", "frontend/.env.electron"]:
            self.assertIsNone(runtime_path(path), path)
        with self.assertRaises(ValueError):
            runtime_path("script/../config.ini")
        with self.assertRaises(ValueError):
            retired_runtime_path("backend/src/main/python/stocksage_alpha/../config.ini")

    def test_success_retires_service_and_failure_restores_its_previous_state(self):
        for fail in [False, True]:
            with self.subTest(fail=fail), tempfile.TemporaryDirectory() as directory:
                root=Path(directory); (root/'backend/target').mkdir(parents=True)
                jar=root/'backend/target/investory.jar'; jar.write_bytes(b'release')
                checksum=hashlib.sha256(jar.read_bytes()).hexdigest()
                commands=[]
                def read_remote(client, command):
                    commands.append(command)
                    if 'release.json' in command: return json.dumps({'commit':'previous'})
                    if 'is-active' in command:return 'active'
                    if 'is-enabled' in command:return 'enabled'
                    if 'sha256sum' in command:return checksum+'  investory.jar'
                    return ''
                client=MagicMock()
                with patch('tools.cloud_deploy.git', side_effect=lambda root,*args: '' if args[0] in {'status','cat-file'} else 'head'), \
                     patch('tools.cloud_deploy.remote', side_effect=read_remote), \
                     patch('tools.cloud_deploy.release_files', return_value=({'investory.jar':jar},['stocksage_alpha/bridge.py'])), \
                     patch('tools.cloud_deploy.active_jobs', return_value=[]), \
                     patch('tools.cloud_deploy.remote_files') as files, \
                     patch('tools.cloud_deploy.wait_health', side_effect=[RuntimeError('not-ready'),None] if fail else None):
                    if fail:
                        with self.assertRaisesRegex(RuntimeError,'not-ready'):deploy_cloud(client,root,'test','http://localhost')
                        self.assertIn('systemctl enable stocksage',commands)
                        self.assertIn('systemctl start stocksage',commands)
                        self.assertEqual(files.call_args.args[2],'restore')
                    else:
                        deploy_cloud(client,root,'test','http://localhost')
                        self.assertNotIn('systemctl start stocksage',commands)
                        self.assertIn('systemctl disable stocksage',commands)
                    self.assertFalse(any('8200/health' in c for c in commands))

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
