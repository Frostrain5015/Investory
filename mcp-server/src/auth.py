"""
Investory MCP Server — authentication middleware.

Validates API keys against the config.yaml whitelist.
Each key maps to an Investory user_id for data isolation.
"""

from __future__ import annotations

from .db import cfg as _cfg

_auth_cfg = _cfg.get("auth", {})
_API_KEYS: dict[str, dict] = {}

for entry in _auth_cfg.get("api_keys", []):
    _API_KEYS[entry["key"]] = {
        "user_id": entry.get("user_id", 1),
        "description": entry.get("description", ""),
    }


def verify_api_key(authorization: str) -> dict | None:
    """Validate a Bearer token and return the associated user info.

    Args:
        authorization: The Authorization header value (e.g. 'Bearer sk-...')

    Returns:
        dict with user_id and description if valid, None otherwise.
    """
    if not authorization:
        return None
    token = authorization.removeprefix("Bearer ").strip()
    return _API_KEYS.get(token)


def get_user_id(authorization: str) -> int:
    """Extract user_id from auth header, defaulting to 1."""
    info = verify_api_key(authorization)
    return info["user_id"] if info else 1
