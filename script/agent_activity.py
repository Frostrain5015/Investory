"""Public activity descriptions. Never derive them from private reasoning text."""
import json
import re


def safe_status(value):
    if not isinstance(value, str):
        return None
    value = value.strip()
    if not 4 <= len(value) <= 48 or not value.startswith(("正在", "准备")):
        return None
    if re.search(r"[\n\r\t`<>{}\[\]·]|sk-|api[_ -]?key|get_\w+|web_search|因为|所以|我认为|SQL|下单|扣款|汇款|转账", value, re.I):
        return None
    return value


def subject(args):
    value = args.get("symbol") or args.get("name") or ""
    if not value and isinstance(args.get("query"), str):
        value = re.split(r"\s|最新|公告|新闻|业绩|下跌|股价", args["query"], maxsplit=1)[0]
    if re.search(r"sk-|api[_ -]?key|token|secret|密码|密钥|^site:|^https?:", str(value), re.I):
        value = ""
    return re.sub(r"[^\w\u4e00-\u9fff.\-]", "", str(value))[:18]


def tool_activity(name, args, generated=None):
    supplied = safe_status(generated)
    if supplied and name.startswith("confirm_") and "确认" not in supplied:
        supplied = None
    if supplied and name == "generate_strategy" and any(word in supplied for word in ("保存", "回测", "运行")):
        supplied = None
    if supplied:
        return {"message": supplied, "origin": "model"}
    target = subject(args)
    messages = {
        "ask_user": "等待你的选择",
        "get_stock_price": f"正在查看{target or '市场'}的{'近期走势' if isinstance(args.get('days'), int) and args['days'] > 1 else '报价'}",
        "get_fundamentals": f"正在读取{target or '相关公司'}的财报",
        "web_search": f"正在核实{target or '相关'}的公开资料",
        "get_world_market": "正在查询全球市场行情",
        "get_portfolio": "正在读取你的投资组合",
        "get_watchlist": "正在查看你的自选",
        "search_stocks": "正在查找相关标的",
        "consult_kb": f"正在查阅{str(args.get('topic') or '分析方法')[:16]}",
        "compute_correlation": "正在计算持仓之间的相关性",
        "compute_sector_breakdown": "正在分析持仓分布",
        "optimize_portfolio": "正在比较组合配置方案",
        "generate_strategy": f"正在编写{target or '交易策略'}",
        "run_backtest": "正在运行策略回测",
        "analyze_backtest": "正在分析回测结果",
        "confirm_create_transaction": "正在整理交易确认信息",
        "confirm_watchlist": "正在准备自选确认信息",
    }
    return {"message": messages.get(name, "正在处理本次请求"), "origin": "runtime"}


def emit_activity(message):
    print("[ACTIVITY] " + json.dumps(message, ensure_ascii=False), flush=True)
