"""
deploy.py — Safe deploy to Alibaba Cloud server + GitHub desktop release.

Usage:
    python deploy.py               # build JAR locally then deploy to cloud
    python deploy.py --no-build    # skip Maven build, use existing JAR
    python deploy.py --release     # also publish a desktop client GitHub release
    python deploy.py --release-only  # ONLY publish the desktop release (no cloud)

Release version is taken from the latest commit note if it contains one
(e.g. "V6.2.0 ..."), otherwise the current version is reused. Publishing a
release with a higher version is what makes installed clients detect the
auto-update on next launch.

Refuses to restart the service if a market-data crawl is running.
"""

import paramiko, os, sys, subprocess, time, shlex, re, json

HOST      = "116.62.179.231"
USER      = "root"
LOCAL_JAR  = r"d:\Java Projects\investory\backend\target\investory.jar"
LOCAL_SCRIPT_DIR = r"d:\Java Projects\investory\script"
SCRIPT_SYNC_DIRS = ["agent_skills"]
# Resident StockSage engine files (the "ghost submodule"): only a couple of
# files change in normal work, so we sync just these rather than the whole tree.
LOCAL_ENGINE_DIR = r"d:\Java Projects\investory\backend\src\main\python\stocksage_alpha"
ENGINE_SYNC_FILES = ["server.py", "bridge.py", "src/research.py", "src/fetcher.py",
                     "src/factors/scoring.py"]
REMOTE_ENGINE_DIR = "/opt/investory/stocksage_alpha"
ENGINE_SERVICE = "stocksage"
REMOTE_JAR = "/opt/investory/investory.jar"
REMOTE_SCRIPT_DIR = "/opt/investory/script"
SERVICE    = "investory"
REMOTE_SSL_KEYSTORE = "/opt/investory/keystore.p12"
REMOTE_SSL_ALIAS = "investory"
REMOTE_SSL_PASSWORD = "investory"
SYSTEMD_DROPIN_DIR = f"/etc/systemd/system/{SERVICE}.service.d"
SYSTEMD_SSL_DROPIN = f"{SYSTEMD_DROPIN_DIR}/ssl.conf"

CRAWL_PROC_PATTERN = "fetch_stocks.py"   # any active crawl shows this

# ── Desktop release (GitHub) ─────────────────────────────────────────
REPO_ROOT    = os.path.dirname(os.path.abspath(__file__))
DESKTOP_DIR  = os.path.join(REPO_ROOT, "desktop")
DESKTOP_PKG  = os.path.join(DESKTOP_DIR, "package.json")
FRONTEND_PKG = os.path.join(REPO_ROOT, "frontend", "package.json")
BACKEND_POM  = os.path.join(REPO_ROOT, "backend", "pom.xml")
VERSION_RE   = re.compile(r"[vV]?(\d+\.\d+\.\d+)")


def ssh_connect():
    from pathlib import Path
    pw = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not pw:
        # Fall back to user-level CLAUDE.md (single shared server across all projects)
        mem = Path.home() / ".claude" / "CLAUDE.md"
        if mem.exists():
            for line in mem.read_text(encoding="utf-8").splitlines():
                if "Password:" in line:
                    # Format: "- Password: `xxx`" — strip backticks/whitespace
                    pw = line.split(":", 1)[1].strip().strip("`").strip()
                    break
    if not pw:
        print("ERROR: SSH password not found. Set DEPLOY_SSH_PASSWORD or add to ~/.claude/CLAUDE.md.")
        sys.exit(1)
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=pw, timeout=30, banner_timeout=60)
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


def check_crawl_running(client):
    """Return list of running crawl process lines, empty if none."""
    result = run(client, f"ps aux | grep '{CRAWL_PROC_PATTERN}' | grep -v grep")
    return [l for l in result.splitlines() if l.strip()]


def build_jar():
    print("Building JAR ...")
    env = os.environ.copy()
    env["JAVA_HOME"] = r"E:\Java\jdk-17"
    # `clean` is essential: Maven's process-resources copies the Vite output into
    # target/classes/static but never deletes stale chunks, so a plain `package`
    # lets every past build's hashed assets pile up in the JAR (it had grown to
    # ~80 MB / 480+ JS files). `clean package` rebuilds target from scratch, so
    # the JAR carries only the current build's assets.
    r = subprocess.run(
        [r"E:\Maven\apache-maven-3.9.16\bin\mvn.cmd",
         "-f", r"d:\Java Projects\investory\backend\pom.xml",
         "clean", "package", "-DskipTests"],
        env=env, capture_output=True, text=True
    )
    if r.returncode != 0:
        print("BUILD FAILED:\n", r.stdout[-2000:])
        sys.exit(1)
    print("Build OK.")


def upload(client):
    size = os.path.getsize(LOCAL_JAR)
    print(f"Uploading JAR ({size/1024/1024:.1f} MB) ...")
    sftp = client.open_sftp()
    sftp.put(LOCAL_JAR, REMOTE_JAR)
    sftp.close()
    print("JAR done.")
    print("Uploading Python scripts ...")
    sftp = client.open_sftp()
    for f in os.listdir(LOCAL_SCRIPT_DIR):
        if f == "config.ini" or f.startswith("__pycache__"):
            continue
        src = os.path.join(LOCAL_SCRIPT_DIR, f)
        dst = f"{REMOTE_SCRIPT_DIR}/{f}"
        if os.path.isfile(src):
            sftp.put(src, dst)
            print(f"  {f}")
    sftp.close()
    for dirname in SCRIPT_SYNC_DIRS:
        local_root = os.path.join(LOCAL_SCRIPT_DIR, dirname)
        if not os.path.isdir(local_root):
            continue
        print(f"Uploading script directory {dirname} ...")
        for root, dirs, files in os.walk(local_root):
            dirs[:] = [d for d in dirs if not d.startswith("__pycache__")]
            rel_root = os.path.relpath(root, LOCAL_SCRIPT_DIR)
            remote_root = f"{REMOTE_SCRIPT_DIR}/{rel_root.replace(os.sep, '/')}"
            run_checked(client, f"mkdir -p {shlex.quote(remote_root)}")
            sftp = client.open_sftp()
            try:
                for filename in files:
                    if filename.startswith("__pycache__"):
                        continue
                    src = os.path.join(root, filename)
                    dst = f"{remote_root}/{filename}"
                    sftp.put(src, dst)
                    print(f"  {rel_root.replace(os.sep, '/')}/{filename}")
            finally:
                sftp.close()
    # Sync StockSage engine files (resident service, Phase 2)
    print("Syncing StockSage engine ...")
    sftp = client.open_sftp()
    for f in ENGINE_SYNC_FILES:
        src = os.path.join(LOCAL_ENGINE_DIR, f)
        dst = f"{REMOTE_ENGINE_DIR}/{f}"
        if os.path.isfile(src):
            sftp.put(src, dst)
            print(f"  {f}")
    sftp.close()
    print("Upload done.")


def ensure_stable_ssl(client):
    print("Ensuring stable HTTPS certificate ...")
    keytool = run(client, "command -v keytool || true")
    if not keytool:
        print("ERROR: keytool not found on server; cannot create stable HTTPS keystore.")
        client.close()
        sys.exit(1)

    keystore = shlex.quote(REMOTE_SSL_KEYSTORE)
    password = shlex.quote(REMOTE_SSL_PASSWORD)
    alias = shlex.quote(REMOTE_SSL_ALIAS)
    exists = run(client, f"test -f {keystore} && echo yes || echo no")
    if exists != "yes":
        dname = shlex.quote(f"CN={HOST}, OU=Investory, O=Investory, L=Hangzhou, ST=Zhejiang, C=CN")
        san = shlex.quote(f"SAN=ip:{HOST},dns:localhost")
        run_checked(
            client,
            " ".join([
                "keytool -genkeypair",
                f"-alias {alias}",
                "-keyalg RSA",
                "-keysize 2048",
                "-validity 3650",
                "-storetype PKCS12",
                f"-keystore {keystore}",
                f"-storepass {password}",
                f"-keypass {password}",
                f"-dname {dname}",
                f"-ext {san}",
            ]),
        )
        print(f"Created {REMOTE_SSL_KEYSTORE}.")
    else:
        print(f"Using existing {REMOTE_SSL_KEYSTORE}.")

    dropin = f"""[Service]
Environment=SSL_KEY_STORE=file:{REMOTE_SSL_KEYSTORE}
Environment=SSL_KEY_PASSWORD={REMOTE_SSL_PASSWORD}
Environment=SERVER_SSL_KEY_STORE=file:{REMOTE_SSL_KEYSTORE}
Environment=SERVER_SSL_KEY_STORE_PASSWORD={REMOTE_SSL_PASSWORD}
"""
    run_checked(
        client,
        f"mkdir -p {shlex.quote(SYSTEMD_DROPIN_DIR)} && "
        f"cat > {shlex.quote(SYSTEMD_SSL_DROPIN)} <<'EOF'\n{dropin}EOF\n"
        "systemctl daemon-reload",
    )
    fingerprint = run(
        client,
        f"LANG=C keytool -list -v -keystore {keystore} -storetype PKCS12 "
        f"-storepass {password} -alias {alias} 2>/dev/null | grep 'SHA256:' | head -1",
    )
    if fingerprint:
        print(f"HTTPS certificate {fingerprint}")


def restart_service(client):
    print("Restarting service ...")
    run(client, f"systemctl restart {SERVICE}")
    time.sleep(4)
    status = run(client, f"systemctl is-active {SERVICE}")
    if status == "active":
        print(f"Service is active.")
    else:
        print(f"WARNING: service status = {status}")
        print(run(client, f"journalctl -u {SERVICE} -n 20 --no-pager"))

def restart_engine(client):
    """Restart the resident StockSage engine (Phase 2). Best-effort — if the
    service was never installed, log and continue."""
    exists = run(client, f"systemctl list-unit-files {ENGINE_SERVICE}.service 2>/dev/null | wc -l")
    if not exists or "0" in exists:
        print(f"Engine service {ENGINE_SERVICE} not installed, skipping.")
        return
    print("Restarting StockSage engine ...")
    run(client, f"systemctl restart {ENGINE_SERVICE}")
    time.sleep(2)
    status = run(client, f"systemctl is-active {ENGINE_SERVICE}")
    print(f"Engine: {status}")


# ── Desktop GitHub release ───────────────────────────────────────────

def latest_commit_subject():
    """Subject line of the most recent git commit, or '' on failure."""
    r = subprocess.run(["git", "log", "-1", "--pretty=%s"],
                       cwd=REPO_ROOT, capture_output=True, text=True, encoding="utf-8")
    return r.stdout.strip() if r.returncode == 0 else ""


def read_current_version():
    with open(DESKTOP_PKG, encoding="utf-8") as f:
        return json.load(f)["version"]


def resolve_release_version():
    """Version from the latest commit note if it contains one (e.g.
    'V6.2.0 ...'); otherwise the current desktop/package.json version."""
    subject = latest_commit_subject()
    m = VERSION_RE.search(subject)
    if m:
        version = m.group(1)
        print(f"Release version from commit note: {version}  ({subject!r})")
        return version
    current = read_current_version()
    print(f"No version in commit note; reusing current version: {current}")
    return current


def _replace_once(path, pattern, repl, label):
    with open(path, encoding="utf-8") as f:
        text = f.read()
    new_text, n = re.subn(pattern, repl, text, count=1)
    if n == 0:
        raise RuntimeError(f"Could not find version field in {label} ({path})")
    if new_text != text:
        with open(path, "w", encoding="utf-8") as f:
            f.write(new_text)
        print(f"  {label}: version set")


def set_version(version):
    """Unify the version across desktop, frontend, and backend manifests so
    the published client, web bundle, and JAR all report the same number."""
    print(f"Unifying version to {version} ...")
    _replace_once(DESKTOP_PKG, r'"version":\s*"[^"]+"',
                  f'"version": "{version}"', "desktop/package.json")
    _replace_once(FRONTEND_PKG, r'"version":\s*"[^"]+"',
                  f'"version": "{version}"', "frontend/package.json")
    _replace_once(BACKEND_POM,
                  r'(<artifactId>investory</artifactId>\s*<version>)[^<]+(</version>)',
                  rf'\g<1>{version}\g<2>', "backend/pom.xml")


def github_token():
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        r = subprocess.run("gh auth token", capture_output=True, text=True, shell=True)
        if r.returncode == 0:
            token = r.stdout.strip()
    return token


def publish_desktop_release(version):
    """Build the Electron frontend and publish a signed NSIS installer +
    latest.yml to GitHub Releases. Installed clients on an older version pick
    up the feed on next launch — that is the auto-update 'trigger'."""
    token = github_token()
    if not token:
        print("ERROR: no GitHub token. Set GH_TOKEN or run `gh auth login`.")
        sys.exit(1)
    env = os.environ.copy()
    env["GH_TOKEN"] = token

    print("Building desktop frontend (electron mode) ...")
    r = subprocess.run("npm run build:frontend", cwd=DESKTOP_DIR, env=env, shell=True)
    if r.returncode != 0:
        print("Desktop frontend build FAILED.")
        sys.exit(1)

    print(f"Publishing desktop release v{version} to GitHub ...")
    r = subprocess.run("npx electron-builder --win --publish always",
                       cwd=DESKTOP_DIR, env=env, shell=True)
    if r.returncode != 0:
        print("electron-builder publish FAILED.")
        sys.exit(1)
    print(f"Desktop release v{version} published. Older clients will detect "
          f"the update on next launch.")


def main():
    no_build = "--no-build" in sys.argv
    do_release = ("--release" in sys.argv) or ("--release-only" in sys.argv)
    release_only = "--release-only" in sys.argv

    # ── Resolve & unify version when releasing ──────────────────────────
    release_version = None
    if do_release:
        release_version = resolve_release_version()
        set_version(release_version)

    # ── Release-only: publish desktop client and stop (no cloud) ────────
    if release_only:
        publish_desktop_release(release_version)
        print("Release-only complete.")
        return

    client = ssh_connect()

    # ── Safety check: abort if a crawl is running ──────────────────────
    crawls = check_crawl_running(client)
    if crawls:
        print("DEPLOY ABORTED — crawl is currently running:")
        for line in crawls:
            print(" ", line)
        print("\nWait for the crawl to finish, then re-run deploy.py.")
        client.close()
        sys.exit(1)

    client.close()

    # ── Build ───────────────────────────────────────────────────────────
    if not no_build:
        build_jar()

    # ── Upload & restart ────────────────────────────────────────────────
    client = ssh_connect()
    ensure_stable_ssl(client)
    upload(client)
    restart_engine(client)
    restart_service(client)
    client.close()
    print("Deploy complete.")

    # ── Desktop GitHub release (after a successful cloud deploy) ─────────
    if do_release:
        publish_desktop_release(release_version)


if __name__ == "__main__":
    main()
