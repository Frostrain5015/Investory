"""
deploy.py — Safe deploy to Alibaba Cloud server.

Usage:
    python deploy.py          # build JAR locally then deploy
    python deploy.py --no-build   # skip Maven build, use existing JAR

Refuses to restart the service if a market-data crawl is running.
"""

import paramiko, os, sys, subprocess, time

HOST      = "116.62.179.231"
USER      = "root"
LOCAL_JAR  = r"d:\Java Projects\investory\backend\target\investory.jar"
LOCAL_SCRIPT_DIR = r"d:\Java Projects\investory\script"
REMOTE_JAR = "/opt/investory/investory.jar"
REMOTE_SCRIPT_DIR = "/opt/investory/script"
SERVICE    = "investory"

CRAWL_PROC_PATTERN = "fetch_stocks.py"   # any active crawl shows this


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


def check_crawl_running(client):
    """Return list of running crawl process lines, empty if none."""
    result = run(client, f"ps aux | grep '{CRAWL_PROC_PATTERN}' | grep -v grep")
    return [l for l in result.splitlines() if l.strip()]


def build_jar():
    print("Building JAR ...")
    env = os.environ.copy()
    env["JAVA_HOME"] = r"E:\Java\jdk-17"
    r = subprocess.run(
        [r"E:\Maven\apache-maven-3.9.16\bin\mvn.cmd",
         "-f", r"d:\Java Projects\investory\backend\pom.xml",
         "package", "-DskipTests"],
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
    print("Upload done.")


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


def main():
    no_build = "--no-build" in sys.argv

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
    upload(client)
    restart_service(client)
    client.close()
    print("Deploy complete.")


if __name__ == "__main__":
    main()
