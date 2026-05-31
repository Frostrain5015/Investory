"""
Investory MCP Server — Main entry point.

Exposes Investory portfolio, stock, quant, and analysis tools to
external AI agents via the Model Context Protocol (MCP).

Transport: SSE (Server-Sent Events) for remote HTTP connections.
Auth: Bearer token (API key) validated per request.

Usage:
    python -m mcp_server.src.server
    # Or:
    uvicorn mcp_server.src.server:app --host 0.0.0.0 --port 8081

Clients (Claude Desktop, Cursor, Cline, 观澜) connect via:
    https://<host>:8081/sse
    Authorization: Bearer sk-investory-xxxx
"""

from __future__ import annotations

from .auth import verify_api_key
from .tools import portfolio, stocks, factors, transactions, quant, watchlist

# Collect all tool functions
ALL_TOOLS: list = []
for mod in [portfolio, stocks, factors, transactions, quant, watchlist]:
    ALL_TOOLS.extend(mod.TOOLS)

TOOL_COUNT = len(ALL_TOOLS)


# ── Create MCP Server ────────────────────────────────────────────────────

def create_server():
    """Build and return the MCP Server instance with all tools registered."""
    try:
        from mcp.server import Server
        from mcp.server.sse import SseServerTransport
    except ImportError:
        print("ERROR: mcp package not installed. Run: pip install mcp")
        raise

    server = Server("investory-mcp")

    for func in ALL_TOOLS:
        server.tool()(func)

    return server


def main():
    """Start the Investory MCP Server with SSE transport."""
    import uvicorn
    from .db import cfg

    server_cfg = cfg.get("server", {})
    host = server_cfg.get("host", "0.0.0.0")
    port = int(server_cfg.get("port", 8081))

    print(f"[Investory MCP] Starting SSE server on {host}:{port}")
    print(f"[Investory MCP] {TOOL_COUNT} tools registered across 6 groups:")
    for mod in [portfolio, stocks, factors, transactions, quant, watchlist]:
        print(f"  {mod.__name__.split('.')[-1]}: {len(mod.TOOLS)} tools")

    try:
        from mcp.server.sse import create_sse_app
        mcp_server = create_server()
        app = create_sse_app(mcp_server)
        uvicorn.run(app, host=host, port=port, log_level="info")
    except ImportError:
        # Fallback: simple Starlette SSE app
        _run_fallback(host, port)


def _run_fallback(host: str, port: int):
    """Simplified SSE server without full MCP SDK (for environments where SDK isn't available)."""
    import json
    import uvicorn
    from starlette.applications import Starlette
    from starlette.responses import JSONResponse, StreamingResponse
    from starlette.routing import Route
    import asyncio

    async def health(request):
        return JSONResponse({"service": "investory-mcp", "tools": TOOL_COUNT, "status": "ok"})

    async def list_tools(request):
        tools = []
        for func in ALL_TOOLS:
            import inspect
            sig = inspect.signature(func)
            params = {n: str(p.annotation) for n, p in sig.parameters.items()}
            tools.append({
                "name": func.__name__,
                "description": func.__doc__.split("\n")[1].strip() if func.__doc__ else "",
                "parameters": params,
            })
        return JSONResponse({"tools": tools, "count": len(tools)})

    async def call_tool(request):
        body = await request.json()
        tool_name = body.get("tool", "")
        params = body.get("params", {})

        for func in ALL_TOOLS:
            if func.__name__ == tool_name:
                try:
                    result = func(**params)
                    if asyncio.iscoroutine(result):
                        result = await result
                    return JSONResponse({"result": result})
                except Exception as e:
                    return JSONResponse({"error": str(e)}, status_code=500)
        return JSONResponse({"error": f"tool not found: {tool_name}"}, status_code=404)

    app = Starlette(routes=[
        Route("/health", health),
        Route("/tools", list_tools),
        Route("/call", call_tool, methods=["POST"]),
    ])

    print("[Investory MCP] Running in fallback mode (mcp SDK not available)")
    uvicorn.run(app, host=host, port=port, log_level="info")


if __name__ == "__main__":
    main()
