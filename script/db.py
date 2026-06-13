"""Investory shared database connection and configuration loader.

Usage:
    from db import get_conn, load_config
    cfg = load_config()
    conn = get_conn(cfg)
"""

import configparser
import os
from pathlib import Path

import pymysql

SCRIPT_DIR = Path(__file__).parent
CONFIG_FILE = SCRIPT_DIR / "config.ini"


def load_config() -> dict:
    """Load DB config from env vars first, then config.ini, then defaults."""
    cfg = configparser.ConfigParser()
    if CONFIG_FILE.exists():
        cfg.read(CONFIG_FILE, encoding="utf-8")

    def get(section, key, default=""):
        try: return cfg.get(section, key).strip()
        except (configparser.NoSectionError, configparser.NoOptionError): return default

    return {
        "db_host":     os.getenv("DB_HOST",     get("database", "host",     "localhost")),
        "db_port":     int(os.getenv("DB_PORT", get("database", "port",     "3306"))),
        "db_name":     os.getenv("DB_NAME",     get("database", "name",     "investory")),
        "db_user":     os.getenv("DB_USER",     get("database", "user",     "root")),
        "db_password": os.getenv("DB_PASSWORD", get("database", "password", "")),
        "proxy_url":   os.getenv("PROXY_URL",   get("proxy",    "url",      "")),
    }


def get_conn(cfg: dict):
    return pymysql.connect(
        host=cfg["db_host"], port=cfg["db_port"],
        database=cfg["db_name"], user=cfg["db_user"],
        password=cfg["db_password"], charset="utf8mb4", autocommit=False,
    )
