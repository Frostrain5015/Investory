"""
deploy.py — Safe deploy to Alibaba Cloud server + GitHub desktop release.

Usage:
    python deploy.py               # build JAR locally then deploy to cloud
    python deploy.py --no-build    # skip Maven build, use existing JAR
    python deploy.py --no-build --allow-unpushed  # explicit offline cloud release; record pending Git synchronization
    python deploy.py --release     # also publish a desktop client GitHub release
    python deploy.py --release-only  # ONLY publish the desktop release (no cloud)

The product version is read from backend/pom.xml. Node manifests are synchronized
before building or deploying; commit messages never override the release version.

Refuses to restart the service if a market-data crawl is running.
"""

import paramiko, os, sys, subprocess, time, shlex, re, json
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET
import zipfile
from tools.cloud_deploy import active_jobs, deploy_cloud

HOST      = "116.62.179.231"
USER      = "root"
# ── Desktop release (GitHub) ─────────────────────────────────────────
REPO_ROOT    = os.path.dirname(os.path.abspath(__file__))
DESKTOP_DIR  = os.path.join(REPO_ROOT, "desktop")
BACKEND_POM  = os.path.join(REPO_ROOT, "backend", "pom.xml")


def ssh_connect():
    config = paramiko.SSHConfig()
    config_file = Path.home() / ".ssh" / "config"
    if config_file.exists():
        with config_file.open(encoding="utf-8") as stream:
            config.parse(stream)
    target = config.lookup("alias")
    identity = os.environ.get("DEPLOY_SSH_KEY") or next(iter(target.get("identityfile", [])), "D:/Frostrain.pem")
    identity = Path(identity).expanduser()
    if not identity.is_file():
        raise FileNotFoundError("SSH identity not found; configure alias IdentityFile or DEPLOY_SSH_KEY")
    client = paramiko.SSHClient()
    client.load_system_host_keys()
    client.set_missing_host_key_policy(paramiko.RejectPolicy())
    client.connect(HOST, port=int(target.get("port", 22)), username=USER,
                   key_filename=str(identity), look_for_keys=False, allow_agent=False,
                   timeout=30, banner_timeout=60, auth_timeout=30)
    return client


def run(client, cmd):
    _, out, err = client.exec_command(cmd)
    return out.read().decode(errors="replace").strip()


def run_checked(client, cmd):
    _, out, err = client.exec_command(cmd)
    stdout = out.read().decode(errors="replace").strip()
    stderr = err.read().decode(errors="replace").strip()
    status = out.channel.recv_exit_status()
    if status != 0:
        details = "\n".join(part for part in (stdout, stderr) if part)
        raise RuntimeError(f"Remote command failed ({status}): {cmd}\n{details}")
    return stdout


def build_jar():
    print("Building JAR ...")
    env = os.environ.copy()
    env["JAVA_HOME"] = r"E:\Java\jdk-17"
    # `clean` is essential: Maven's process-resources copies the Vite output into
    # target/classes/static but never deletes stale chunks, so a plain `package`
    # lets every past build's hashed assets pile up in the JAR (it had grown to
    # ~80 MB / 480+ JS files). `clean package` rebuilds target from scratch, so
    # the JAR carries only the current build's assets.
    maven = os.environ.get("MAVEN_CMD") or shutil.which("mvn") or shutil.which("mvn.cmd")
    if not maven:
        candidates = sorted((Path.home() / ".m2/wrapper/dists").glob("apache-maven-*-bin/*/apache-maven-*/bin/mvn.cmd"))
        maven = str(candidates[-1]) if candidates else None
    if not maven:
        raise FileNotFoundError("Maven not found; set MAVEN_CMD")
    r = subprocess.run(
        [maven, "-f", BACKEND_POM, "clean", "package"],
        env=env, capture_output=True, text=True
    )
    if r.returncode != 0:
        print("BUILD FAILED:\n", r.stdout[-4000:], r.stderr[-1000:])
        sys.exit(1)
    print("Build OK.")


# ── Desktop GitHub release ───────────────────────────────────────────

def read_current_version():
    """The Maven product version is the only manually maintained version."""
    element = ET.parse(BACKEND_POM).getroot().find("{http://maven.apache.org/POM/4.0.0}version")
    version = element.text.strip() if element is not None and element.text else ""
    if not re.fullmatch(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)", version):
        raise ValueError("Invalid Investory version in backend/pom.xml")
    return version


def sync_versions():
    subprocess.run(["node", str(Path(REPO_ROOT) / "tools/version.mjs")], cwd=REPO_ROOT, check=True)
    return read_current_version()


def verify_jar_version(version):
    """Refuse --no-build when the existing artifact belongs to another release."""
    with zipfile.ZipFile(Path(REPO_ROOT) / "backend/target/investory.jar") as jar:
        properties = jar.read("META-INF/maven/com.investory/investory/pom.properties").decode("utf-8")
    values = dict(line.split("=", 1) for line in properties.splitlines() if "=" in line and not line.startswith("#"))
    if values.get("version") != version:
        raise RuntimeError("JAR version differs from backend/pom.xml; rebuild before deploying")


def github_token():
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        r = subprocess.run("gh auth token", capture_output=True, text=True, shell=True)
        if r.returncode == 0:
            token = r.stdout.strip()
    return token


UNPACKED_EXE = r"C:\tmp\investory-build\win-unpacked\Investory.exe"
RCEDIT_EXE   = os.path.join(DESKTOP_DIR, "build-tools", "rcedit-x64.exe")
APP_ICO      = os.path.join(DESKTOP_DIR, "assets", "icon.ico")


def _run(cmd, env, label):
    kw = {"cwd": DESKTOP_DIR, "env": env}
    if isinstance(cmd, str):
        kw["shell"] = True
    r = subprocess.run(cmd, **kw)
    if r.returncode != 0:
        print(f"{label} FAILED.")
        sys.exit(1)


def publish_desktop_release(version):
    """Build the Electron app and publish an (unsigned) NSIS installer +
    latest.yml to GitHub Releases. Installed clients on an older version pick up
    the feed on next launch — that is the auto-update 'trigger'.

    Three-phase, because electron-builder can't embed the app-exe icon in this
    environment: the rcedit it bundles lives inside winCodeSign, whose archive
    has macOS symlinks that won't extract on Windows without admin/Developer Mode
    ("客户端没有所需的特权"). So win.signAndEditExecutable is false (no winCodeSign
    download), and we embed the icon ourselves with a vendored standalone rcedit
    between packing and installer assembly:
        1. --dir            pack the app (default Electron exe icon)
        2. rcedit           set the favicon icon on the packed exe
        3. --prepackaged    build the NSIS installer from the edited dir + publish
    """
    token = github_token()
    if not token:
        print("ERROR: no GitHub token. Set GH_TOKEN or run `gh auth login`.")
        sys.exit(1)
    env = os.environ.copy()
    env["GH_TOKEN"] = token
    env["CSC_IDENTITY_AUTO_DISCOVERY"] = "false"  # never code-sign → no winCodeSign

    print("Building desktop frontend (electron mode) ...")
    _run("npm run build:frontend", env, "Desktop frontend build")

    print("Phase 1/3 — packing app (electron-builder --dir) ...")
    _run("npx electron-builder --win --dir", env, "electron-builder --dir")

    print("Phase 2/3 — embedding app icon via rcedit ...")
    _run([RCEDIT_EXE, UNPACKED_EXE, "--set-icon", APP_ICO], env, "rcedit set-icon")

    print(f"Phase 3/3 — building installer + publishing v{version} ...")
    unpacked_dir = os.path.dirname(UNPACKED_EXE)
    _run(f'npx electron-builder --win --prepackaged "{unpacked_dir}" --publish always',
         env, "electron-builder publish")

    print(f"Desktop release v{version} published. Older clients will detect "
          f"the update on next launch.")


def main():
    no_build = "--no-build" in sys.argv
    do_release = ("--release" in sys.argv) or ("--release-only" in sys.argv)
    release_only = "--release-only" in sys.argv

    release_version = sync_versions()

    # ── Release-only: publish desktop client and stop (no cloud) ────────
    if release_only:
        publish_desktop_release(release_version)
        print("Release-only complete.")
        return

    client = ssh_connect()
    try:
        jobs = active_jobs(client)
        if jobs:
            raise RuntimeError("Wait for active jobs to finish: " + ", ".join(jobs))
    finally:
        client.close()

    if not no_build:
        build_jar()

    verify_jar_version(release_version)
    client = ssh_connect()
    try:
        deploy_cloud(client, Path(REPO_ROOT), release_version,
                     os.environ.get("DEPLOY_HEALTH_URL", "http://127.0.0.1:8443/api/session"),
                     allow_unpushed="--allow-unpushed" in sys.argv)
    finally:
        client.close()
    print("Deploy complete.")

    # ── Desktop GitHub release (after a successful cloud deploy) ─────────
    if do_release:
        publish_desktop_release(release_version)


if __name__ == "__main__":
    main()
