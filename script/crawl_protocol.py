"""Small, dependency-free boundaries shared by market collectors and tests."""

from datetime import date, timedelta
import re


def yahoo_exclusive_end(inclusive_end: str) -> str:
    return (date.fromisoformat(inclusive_end) + timedelta(days=1)).isoformat()


def hk_stock_code(symbol: str) -> str | None:
    match = re.fullmatch(r"(?:116\.)?(\d{1,5})(?:\.HK)?", symbol.strip().upper())
    if match and 0 < int(match.group(1)) <= 99999:
        return f"{int(match.group(1)):05d}"
    return None  # HSI/HSCE/HSTECH belong to the index collector, not equity codes.


def _provider_rows(result) -> list[list[str]]:
    if result.error_code != "0":
        raise RuntimeError(f"BaoStock query failed: {result.error_msg}")
    rows = []
    while result.next():
        rows.append(result.get_row_data())
    return rows


def a_share_readiness(provider, end: str) -> tuple[str, str | None]:
    """Probe publication before an automated full-universe run; never write data."""
    days = _provider_rows(provider.query_trade_dates(start_date=end, end_date=end))
    if not days:
        raise RuntimeError("BaoStock did not return a trading calendar entry")
    if days[0][1] != "1":
        return "no_change", None
    start = (date.fromisoformat(end) - timedelta(days=7)).isoformat()
    prices = _provider_rows(provider.query_history_k_data_plus(
        "sh.000001", "date,close", start_date=start, end_date=end,
        frequency="d", adjustflag="2"))
    latest = max((row[0] for row in prices), default=None)
    return ("ready" if latest is not None and latest >= end else "pending"), latest
