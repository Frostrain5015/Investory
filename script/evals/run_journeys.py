"""Real-model agent journeys. Public market tools are live; personal writes use fixtures.

Each case has its own subprocess and trace, so interactive or failed runs cannot
hang the suite. No credentials are passed on command lines or written to reports.
"""
import argparse
import ast
from contextlib import redirect_stdout, redirect_stderr
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from run_evals import parse_live_trace
import ai_agent as agent


class TraceBuffer(io.StringIO):
    def reconfigure(self, **kwargs):
        pass


def credentials(server):
    if server:
        # Match the service's search/runtime configuration without exporting or
        # printing secrets. SSH shells do not inherit systemd Environment values.
        pid = subprocess.check_output(["systemctl", "show", "investory", "--property=MainPID", "--value"], text=True).strip()
        service_env = dict(item.split("=", 1) for item in Path(f"/proc/{pid}/environ").read_bytes().decode().split("\0") if "=" in item)
        for name in ("DEEPSEEK_SEARCH_KEY", "AI_DEFAULT_KEY", "PROXY_URL", "HITHINK_API_KEY", "HITHINK_ENABLED"):
            if name in service_env:
                os.environ[name] = service_env[name]
        import db
        conn = db.get_conn(db.load_config())
        try:
            with conn.cursor() as cursor:
                cursor.execute("SELECT model,base_url,api_key,context_window FROM ai_model_profiles WHERE provider='openai_compat' AND api_key IS NOT NULL AND api_key<>'' AND context_window IS NOT NULL ORDER BY slot_id LIMIT 1")
                row = cursor.fetchone()
            if not row:
                raise RuntimeError("No configured server model")
            return row
        finally:
            conn.close()
    key = os.environ.get("EVAL_API_KEY") or os.environ.get("AI_API_KEY")
    if not key:
        raise RuntimeError("Real-model credentials required; no offline fallback")
    return os.environ["EVAL_MODEL"], os.environ["EVAL_API_BASE"], key, int(os.environ.get("EVAL_CONTEXT_WINDOW","65536"))


def worker(case, report_dir, server):
    model, base, key, context_window = credentials(server)
    operations, traces, history = [], [], []
    original = agent._run_tool
    fixture = case.get("fixture")

    def dispatch(name, args, portfolio_id, user_id):
        operation={"name": name, "args": args}
        operations.append(operation)
        if name == "web_search" and fixture == "search_failure":
            return {"error": "测试注入：网络搜索暂不可用，未取得公告证据"}
        if name == "get_portfolio":
            return {"holdings": [{"symbol":"600519.SH","name":"贵州茅台","mv":80000,"weightPct":80},
                                  {"symbol":"600036.SH","name":"招商银行","mv":20000,"weightPct":20}],
                    "totalValue":100000,"currency":"CNY","fixture":True}
        if name == "compute_sector_breakdown":
            return {"sectors":[{"name":"白酒","weightPct":80},{"name":"银行","weightPct":20}],"fixture":True}
        if name == "compute_correlation" and fixture == "portfolio":
            # Fixture only supplies the account universe. Price history and the
            # correlation implementation remain real and do not read user holdings.
            return original(name,{**args,"symbols":["600519.SH","600036.SH"]},portfolio_id,user_id)
        if name == "get_watchlist":
            return {"items":[],"fixture":True}
        if name == "get_strategies" and fixture == "saved_backtest":
            return {"strategies":[{"id":990001,"name":"评测双均线策略","type":"advanced"}],"fixture":True}
        if name == "run_backtest" and fixture == "saved_backtest":
            if args.get("strategy_id") != 990001:
                return {"error":"回测ID不来自已查询策略"}
            result={"backtest_id":990002,"metrics":{"totalReturnPct":8.5,"maxDrawdownPct":12.3,"sharpe":0.7,"totalTrades":8},"fixture":True}
            agent._emit_report_artifact({"report_type":"backtest_result","title":"隔离回测样例","markdown":"隔离测试数据：收益8.5%，最大回撤12.3%。","summary":"回测样例"})
            return result
        if name == "analyze_backtest" and fixture == "saved_backtest":
            if args.get("id") != 990002:
                return {"error":"回测结果ID不来自本次运行"}
            return {"backtest_id":990002,"metrics":{"totalReturnPct":8.5,"maxDrawdownPct":12.3,"sharpe":0.7,"totalTrades":8},"fixture":True}
        if name == "search_stocks" and fixture == "ambiguity":
            return {"results":[{"id":90001,"symbol":"116.09868","name":"小鹏汽车","market":"HK"},
                               {"id":90002,"symbol":"105.XPEV","name":"小鹏汽车","market":"US"}],"needs_disambiguation":True,"fixture":True}
        if name == "ask_user":
            with patch.object(agent,"_read_answer_with_timeout",return_value="90001" if fixture=="ambiguity" else ""):
                return original(name,args,portfolio_id,user_id)
        if name == "get_world_market":
            result=original(name,args,portfolio_id,user_id)
            operation["indices"]={row['symbol']:row.get('available',False) for row in result.get('indices',[])}
            return result
        if name == "confirm_create_transaction" or name == "confirm_watchlist" and args.get("action","add")=="add":
            # Build the actual production confirmation payload; never apply its endpoint.
            return original(name,args,portfolio_id,0)
        # Public data and generated draft artifacts use actual production code.
        if name in {"get_stock_price","get_fundamentals","search_stocks","consult_kb","web_search","generate_strategy","get_world_market"}:
            return original(name,args,portfolio_id,user_id)
        return {"error":"测试隔离：此场景不访问真实用户台账或写入数据库"}

    for turn, prompt in enumerate(case["prompts"]):
        history.append({"role":"user","content":prompt})
        buffer, errors = TraceBuffer(), TraceBuffer()
        started = time.monotonic()
        with tempfile.NamedTemporaryFile("w",suffix=".json",encoding="utf-8",delete=False) as stream:
            json.dump({"messages":history},stream,ensure_ascii=False)
            input_path = stream.name
        argv = ["ai_agent.py","--mode","chat","--provider","openai","--model",model,"--api-base",base,
                "--context-window",str(context_window),"--portfolio-id","900000001","--user-id","0","--input",input_path]
        exit_code = 0
        try:
            with patch.object(sys,"argv",argv), patch.dict(os.environ,{"AI_API_KEY":key}), \
                    patch.object(agent,"_run_tool",side_effect=dispatch), redirect_stdout(buffer), redirect_stderr(errors):
                try:
                    agent.main()
                except SystemExit as error:
                    exit_code = error.code if isinstance(error.code, int) else 1
                except Exception as error:
                    exit_code = 1
                    print('[ERROR] '+type(error).__name__+': '+str(error).replace(key,'[redacted]'),flush=True)
        finally:
            Path(input_path).unlink(missing_ok=True)
        raw = buffer.getvalue().replace(key,"[redacted]")
        trace = parse_live_trace(raw, exit_code)
        trace["elapsed_seconds"] = round(time.monotonic()-started,2)
        trace["prompt"] = prompt
        traces.append(trace)
        (report_dir/f'{case["id"]}-turn{turn+1}.trace.txt').write_text(raw,encoding="utf-8")
        history.append({"role":"assistant","content":trace["output"]})
        if trace["contexts"]:
            history = [message for message in history if message["role"] != "system"]
            history.insert(0,{"role":"system","content":"本对话上一轮工具数据，非当前实时保证："+json.dumps(trace["contexts"][-1],ensure_ascii=False)})
    called = [name for trace in traces for name in trace["called_tools"]]
    failures=[]
    for trace in traces:
        if trace["runtime_error"]:
            failures.append(trace["runtime_error"])
        for failure in trace["tool_failures"]:
            if len(failure)>1 and failure[1] not in case.get("allowed_failures",[]):
                failures.append("Unexpected tool failure: "+" ".join(failure))
    failures += ["Missing tool: "+name for name in case.get("must_tools",[]) if name not in called]
    failures += ["Forbidden tool: "+name for name in case.get("forbid_tools",[]) if name in called]
    operations_blob=json.dumps(operations,ensure_ascii=False)
    failures += ["Missing symbol evidence: "+symbol for symbol in case.get("must_symbols",[]) if symbol not in operations_blob]
    for symbol in case.get("available_indices",[]):
        if not any(operation.get("indices",{}).get(symbol) for operation in operations):
            failures.append("Missing main index quote: "+symbol)
    if case.get("artifact") and not any(line.startswith('['+case["artifact"]+']') for trace in traces for line in trace["artifacts"]):
        failures.append("Missing UI handoff artifact")
    if case.get("followup_reuse") and traces[-1]["called_tools"]:
        failures.append("Follow-up unnecessarily refetched data")
    if case.get("needs_clarification") and "ask_user" not in called:
        answer="".join(trace["output"] for trace in traces)
        if not any(word in answer for word in ("数量","股数","多少股")) or not any(word in answer for word in ("成交价","价格","多少钱")):
            failures.append("Did not request missing quantity and trade price")
    if case.get("cross_strategy"):
        drafts=[operation["args"].get("code", "") for operation in operations if operation["name"]=="generate_strategy"]
        for code in drafts:
            tree=ast.parse(code)
            has_previous=any(isinstance(node,ast.Subscript) and (
                isinstance(node.slice,ast.UnaryOp) and isinstance(node.slice.op,ast.USub) and isinstance(node.slice.operand,ast.Constant) and node.slice.operand.value==2
                or isinstance(node.slice,ast.Slice) and isinstance(node.slice.upper,ast.UnaryOp) and isinstance(node.slice.upper.op,ast.USub) and isinstance(node.slice.upper.operand,ast.Constant) and node.slice.upper.operand.value==1
            ) for node in ast.walk(tree))
            if not has_previous:
                failures.append("Crossover draft does not compare previous and current bars")
    if case.get("valuation_grounding"):
        import re
        answer=re.sub(r"\s+","","".join(t["output"] for t in traces))
        if "历史偏低区间" in answer or "全年EPS大概率" in answer:
            failures.append("Valuation snapshot used for unsupported historical/forward claim")
    reasoning="\n".join(trace["reasoning"] for trace in traces)
    loop_markers=[word for word in ("工具列表里没有","当前能调用的工具只有","所以我无法调用","预取结果中已经执行") if reasoning.count(word)>1]
    if loop_markers:
        failures.append("Repeated tool-availability deliberation")
    result={"id":case["id"],"passed":not failures,"failures":failures,"model":model,"context_window":context_window,"data_mode":"live public data/card builders; isolated personal/backtest fixtures",
            "operations":operations,"traces":traces,"reasoning_loop_markers":loop_markers}
    (report_dir/f'{case["id"]}.json').write_text(json.dumps(result,ensure_ascii=False,indent=2).replace(key,"[redacted]"),encoding="utf-8")
    print(json.dumps({"id":case["id"],"passed":not failures,"failures":failures,"tools":called,"seconds":sum(t["elapsed_seconds"] for t in traces)},ensure_ascii=False),flush=True)
    return 0 if not failures else 1


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--report-dir",required=True,type=Path)
    parser.add_argument("--server-credentials",action="store_true")
    parser.add_argument("--case")
    args=parser.parse_args()
    args.report_dir.mkdir(parents=True,exist_ok=True)
    cases=json.loads((HERE/"journeys.json").read_text(encoding="utf-8"))
    if args.case:
        return worker(next(c for c in cases if c["id"]==args.case),args.report_dir,args.server_credentials)
    # Two independent processes reduce elapsed time without changing agent scheduling.
    from concurrent.futures import ThreadPoolExecutor, as_completed
    def run(case):
        command=[sys.executable,str(Path(__file__).resolve()),"--report-dir",str(args.report_dir),"--case",case["id"]]
        if args.server_credentials: command.append("--server-credentials")
        print('START '+case["id"],flush=True)
        try:
            process=subprocess.run(command,capture_output=True,text=True,encoding="utf-8",timeout=240,env={**os.environ,"PYTHONIOENCODING":"utf-8"})
            print(process.stdout.strip() or json.dumps({"id":case["id"],"passed":False,"reason":"worker failed","exit":process.returncode}),flush=True)
            return {"id":case["id"],"passed":process.returncode==0}
        except subprocess.TimeoutExpired:
            print('TIMEOUT '+case["id"],flush=True)
            return {"id":case["id"],"passed":False,"reason":"timeout"}
    results=[]
    with ThreadPoolExecutor(max_workers=2) as pool:
        for future in as_completed([pool.submit(run,case) for case in cases]):
            results.append(future.result())
    (args.report_dir/"summary.json").write_text(json.dumps(results,indent=2),encoding="utf-8")
    return 0 if all(case["passed"] for case in results) else 1


if __name__=="__main__":
    raise SystemExit(main())
