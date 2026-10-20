"""Stage, verify, back up and activate one cloud release without touching runtime data."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path, PurePosixPath
import shlex
import subprocess
import time
from datetime import datetime, timezone

APP_ROOT = "/opt/investory"
ENGINE_PREFIX = "backend/src/main/python/stocksage_alpha/"


def runtime_path(source: str) -> str | None:
    path = PurePosixPath(source)
    if path.is_absolute() or ".." in path.parts:
        raise ValueError("Runtime source must be repository-relative")
    if source.startswith(ENGINE_PREFIX):
        relative = source[len(ENGINE_PREFIX):]
        if path.suffix == ".py" or relative == "requirements.txt":
            return "stocksage_alpha/" + relative
    if source.startswith("script/"):
        relative = source[len("script/"):]
        if "/" not in relative and (path.suffix == ".py" or relative in {
                "requirements.txt", "ai_knowledge_base.json", "tool_catalog.json"}):
            return source
        if relative.startswith("agent_skills/") and path.suffix == ".md":
            return source
    return None


def git(root: Path, *args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=root, encoding="utf-8").strip()


def release_files(root: Path, previous_commit: str | None = None) -> tuple[dict[str, Path], list[str]]:
    files = {"investory.jar": root / "backend/target/investory.jar"}
    for source in git(root, "ls-files", "-z", "--", "script", ENGINE_PREFIX).split("\0"):
        target = runtime_path(source) if source else None
        if target is not None:
            local = root / source
            if not local.is_file():
                raise FileNotFoundError(local)
            files[target] = local
    # A release can include follow-up commits after its version bump. Compare the
    # whole release range rather than only the last commit.
    if previous_commit is None:
        version_commit = git(root, "log", "-1", "--format=%H", "--", "backend/pom.xml")
        previous_commit = version_commit + "^"
    removed = [target for source in git(root, "diff", previous_commit, "HEAD", "--diff-filter=D", "--name-only").splitlines()
               if (target := runtime_path(source)) is not None]
    return files, removed


def remote(client, command: str) -> str:
    _, stdout, stderr = client.exec_command(command, timeout=60)
    result = stdout.read().decode("utf-8", errors="replace").strip()
    error = stderr.read().decode("utf-8", errors="replace").strip()
    if stdout.channel.recv_exit_status() != 0:
        raise RuntimeError(f"Remote operation failed: {command}\n{error[:1000]}")
    return result


# These programs also run against a temporary filesystem in offline tests.
REMOTE_FILES = r'''
import hashlib, json, os, shutil, sys
from pathlib import Path
data = json.load(sys.stdin)
root = Path(data["root"]).resolve()
stage = Path(data["stage"]).resolve()
if not stage.is_relative_to(root / "releases"):
    raise ValueError("Stage must be under the application's releases directory")
def scoped(base, relative):
    name = Path(relative)
    if name.is_absolute() or ".." in name.parts:
        raise ValueError("Unsafe relative path")
    result = (base / name).resolve()
    if not result.is_relative_to(base.resolve()):
        raise ValueError("Path escapes the release boundary")
    return result
backup = stage / "backup"
operation = data["operation"]
if operation == "backup":
    # Validate every staged byte before stopping a service or changing live files.
    for relative, expected in data["hashes"].items():
        source = scoped(stage / "files", relative)
        if hashlib.sha256(source.read_bytes()).hexdigest() != expected:
            raise ValueError("Staged checksum mismatch: " + relative)
        if relative.endswith(".py"):
            compile(source.read_text(encoding="utf-8"), str(source), "exec")
    backup.mkdir(exist_ok=False)
    existed = []
    paths = sorted(set(data["hashes"]) | set(data["removed"]))
    for relative in paths:
        source = scoped(root, relative)
        if source.exists():
            if not source.is_file(): raise ValueError("Runtime path is not a file")
            target = scoped(backup, relative)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
            existed.append(relative)
    (backup / "state.json").write_text(json.dumps({"paths":paths,"existed":existed}), encoding="utf-8")
elif operation == "activate":
    for relative, expected in data["hashes"].items():
        source = scoped(stage / "files", relative)
        if hashlib.sha256(source.read_bytes()).hexdigest() != expected:
            raise ValueError("Staged checksum mismatch: " + relative)
        target = scoped(root, relative)
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_name(target.name + ".deploying")
        shutil.copy2(source, temporary)
        os.replace(temporary, target)
    for relative in data["removed"]:
        scoped(root, relative).unlink(missing_ok=True)
elif operation == "restore":
    state = json.loads((backup / "state.json").read_text(encoding="utf-8"))
    for relative in state["paths"]:
        target = scoped(root, relative)
        if relative in state["existed"]:
            target.parent.mkdir(parents=True, exist_ok=True)
            temporary = target.with_name(target.name + ".restoring")
            shutil.copy2(scoped(backup, relative), temporary)
            os.replace(temporary, target)
        else:
            target.unlink(missing_ok=True)
else:
    raise ValueError("Unknown release operation")
print(json.dumps({"operation":operation,"ok":True}))
'''


def remote_files(client, payload: dict, operation: str) -> None:
    stdin, stdout, stderr = client.exec_command("python3 -c " + shlex.quote(REMOTE_FILES), timeout=60)
    stdin.write(json.dumps({**payload, "operation": operation}))
    stdin.channel.shutdown_write()
    result = stdout.read().decode("utf-8", errors="replace")
    error = stderr.read().decode("utf-8", errors="replace")
    if stdout.channel.recv_exit_status() != 0:
        raise RuntimeError(f"Release {operation} failed: {error[:1500]}")
    if not json.loads(result).get("ok"):
        raise RuntimeError(f"Release {operation} did not confirm success")


def active_jobs(client) -> list[str]:
    code = r'''
from pathlib import Path
import json
names = ("fetch_stocks.py", "fetch_fundamentals.py", "fetch_news.py", "backtest_engine.py", "ai_agent.py", "analyze_quant.py")
jobs=[]
for process in Path("/proc").iterdir():
    if not process.name.isdigit(): continue
    try: arguments=(process/"cmdline").read_bytes().decode(errors="replace").split("\0")
    except (OSError, PermissionError): continue
    for argument in arguments:
        if Path(argument).name in names:
            jobs.append(process.name+":"+Path(argument).name)
            break
print(json.dumps(jobs))
'''
    return json.loads(remote(client, "python3 -c " + shlex.quote(code)))


def wait_health(client, health_url: str, engine: bool) -> None:
    for attempt in range(45):
        app_ok = remote(client, "curl -fsS --max-time 3 " + shlex.quote(health_url) + " 2>/dev/null || true")
        engine_ok = remote(client, "curl -fsS --max-time 3 http://127.0.0.1:8200/health 2>/dev/null || true") if engine else '{"status":"ok"}'
        try:
            if "authenticated" in json.loads(app_ok) and json.loads(engine_ok).get("status") == "ok":
                return
        except (ValueError, TypeError):
            pass
        if attempt % 10 == 0:
            print(f"Waiting for application readiness ({attempt + 1}/45) ...", flush=True)
        time.sleep(1)
    raise RuntimeError("Cloud services did not become ready")


def deploy_cloud(client, root: Path, version: str, health_url: str) -> dict:
    if git(root, "status", "--porcelain"):
        raise RuntimeError("Commit the complete release before deploying")
    commit = git(root, "rev-parse", "HEAD")
    if commit != git(root, "rev-parse", "origin/master"):
        raise RuntimeError("Push this release to origin/master before deploying")
    previous = remote(client, "test ! -f " + APP_ROOT + "/release.json || cat " + APP_ROOT + "/release.json")
    previous_commit = json.loads(previous).get("commit") if previous else None
    if previous_commit is not None:
        git(root, "cat-file", "-e", previous_commit + "^{commit}")
    files, removed = release_files(root, previous_commit)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    stage = f"{APP_ROOT}/releases/{version}-{commit[:12]}-{stamp}"
    manifest = {"version": version, "commit": commit, "createdAt": stamp,
                "files": {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in files.items()},
                "removed": removed}
    payload = {"root": APP_ROOT, "stage": stage, "hashes": dict(manifest["files"]), "removed": removed}
    jobs = active_jobs(client)
    if jobs:
        raise RuntimeError("Active jobs prevent deployment: " + ", ".join(jobs))
    print(f"Staging v{version} ({len(files)} files) at {stage}", flush=True)
    sftp = client.open_sftp()
    try:
        created = set()
        for relative, source in files.items():
            destination = stage + "/files/" + relative
            parent = str(PurePosixPath(destination).parent)
            if parent not in created:
                remote(client, "mkdir -p " + shlex.quote(parent))
                created.add(parent)
            sftp.put(str(source), destination)
        manifest_bytes = json.dumps(manifest, indent=2).encode("utf-8")
        with sftp.open(stage + "/files/release.json", "wb") as stream:
            stream.write(manifest_bytes)
        payload["hashes"]["release.json"] = hashlib.sha256(manifest_bytes).hexdigest()
    finally:
        sftp.close()
    remote_files(client, payload, "backup")
    jobs = active_jobs(client)
    if jobs:
        raise RuntimeError("New jobs started during staging; live files unchanged: " + ", ".join(jobs))
    engine = remote(client, "systemctl is-active stocksage || true") == "active"
    if remote(client, "systemctl is-active investory || true") != "active":
        raise RuntimeError("Application was not active before deployment")
    print("Backup verified; activating cloud release ...", flush=True)
    try:
        remote(client, "systemctl stop investory")
        if engine:
            remote(client, "systemctl stop stocksage")
        remote_files(client, payload, "activate")
        if engine:
            remote(client, "systemctl start stocksage")
        remote(client, "systemctl start investory")
        wait_health(client, health_url, engine)
        verify = remote(client, "sha256sum " + shlex.quote(APP_ROOT + "/investory.jar")).split()[0]
        if verify != manifest["files"]["investory.jar"]:
            raise RuntimeError("Live JAR checksum does not match the release")
    except Exception:
        print("Activation failed; restoring previous code ...", flush=True)
        remote(client, "systemctl stop investory")
        if engine:
            remote(client, "systemctl stop stocksage")
        remote_files(client, payload, "restore")
        if engine:
            remote(client, "systemctl start stocksage")
        remote(client, "systemctl start investory")
        wait_health(client, health_url, engine)
        raise
    receipt = {**manifest, "stage": stage, "status": "healthy"}
    (root / "backend/target/cloud-deployment.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
    print(f"Cloud v{version} is healthy; rollback files retained at {stage}/backup", flush=True)
    return receipt
