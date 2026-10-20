"""Provider transport settings, independent of the agent and database connection."""

from ipaddress import ip_address
from urllib.parse import urlsplit

from db import read_config


def get_proxy() -> str:
    return read_config().get("proxy", "url", fallback="").strip()


def needs_proxy(api_base: str) -> bool:
    """Keep local/private endpoints and known domestic providers off the proxy."""
    if not api_base:
        return True
    hostname = (urlsplit(api_base).hostname or "").lower().rstrip(".")
    if hostname == "localhost" or hostname.endswith(".localhost"):
        return False
    try:
        address = ip_address(hostname)
        if address.is_private or address.is_loopback or address.is_link_local:
            return False
    except ValueError:
        pass  # DNS names are compared on domain boundaries below.
    domestic_domains = ("aliyuncs.com", "aliyun.com", "deepseek.com")
    return not any(hostname == domain or hostname.endswith("." + domain) for domain in domestic_domains)
