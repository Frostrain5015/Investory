"""
Investory MCP Server — database layer.

Connects to the Investory MySQL database using pymysql.
Connection parameters are read from config.yaml (with env var overrides).
"""

from __future__ import annotations

import os
import re
from contextlib import contextmanager

import pymysql
import yaml


def _resolve_env(value: str) -> str:
    """Resolve ${ENV_VAR:default} placeholders in config values."""
    pattern = re.compile(r"\$\{(\w+)(?::([^}]*))?\}")
    def replacer(m):
        return os.environ.get(m.group(1), m.group(2) or "")
    return pattern.sub(replacer, str(value))


def load_config(path: str = "config.yaml") -> dict:
    """Load and resolve configuration from YAML file."""
    if not os.path.exists(path):
        path = os.path.join(os.path.dirname(__file__), "..", "config.yaml")
    with open(path, "r", encoding="utf-8") as f:
        raw = f.read()
    raw = _resolve_env(raw)
    return yaml.safe_load(raw)


# Global config, loaded once at startup
cfg = load_config()
db_cfg = cfg.get("database", {})

# Connection pool (simple list-based, sufficient for MCP usage)
_pool: list[pymysql.Connection] = []


def get_conn() -> pymysql.Connection:
    """Get a database connection from the pool or create a new one."""
    if _pool:
        conn = _pool.pop()
        try:
            conn.ping(reconnect=True)
            return conn
        except Exception:
            pass  # stale connection, create new one
    return _create_conn()


def _create_conn() -> pymysql.Connection:
    return pymysql.connect(
        host=db_cfg.get("host", "localhost"),
        port=int(db_cfg.get("port", 3306)),
        database=db_cfg.get("name", "investory"),
        user=db_cfg.get("user", "root"),
        password=db_cfg.get("password", ""),
        charset="utf8mb4",
        autocommit=True,
        cursorclass=pymysql.cursors.DictCursor,
    )


def release_conn(conn: pymysql.Connection) -> None:
    """Return a connection to the pool."""
    if len(_pool) < 5:
        _pool.append(conn)
    else:
        conn.close()


@contextmanager
def get_db():
    """Context manager for database connections."""
    conn = get_conn()
    try:
        yield conn
    finally:
        release_conn(conn)


def query(sql: str, params: tuple = ()) -> list[dict]:
    """Execute a read-only query and return all rows."""
    with get_db() as conn:
        with conn.cursor() as cur:
            cur.execute(sql, params)
            return cur.fetchall()


def query_one(sql: str, params: tuple = ()) -> dict | None:
    """Execute a read-only query and return the first row."""
    with get_db() as conn:
        with conn.cursor() as cur:
            cur.execute(sql, params)
            return cur.fetchone()
