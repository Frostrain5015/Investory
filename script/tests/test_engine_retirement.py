"""Core analysis and persisted reports work without the retired engine installed."""
import ast
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'script'))
import ai_agent as agent

RETIRED = {'get_stock_report', 'get_portfolio_report', 'get_daily_picks_report',
           'get_market_regime', 'pick_stocks', 'get_factor_scores', 'get_portfolio_analysis', 'get_daily_picks'}


class EngineRetirementTest(unittest.TestCase):
    def test_removed_engine_is_not_installed_or_advertised(self):
        self.assertFalse((ROOT / 'backend/src/main/python/stocksage_alpha').exists())
        self.assertFalse(hasattr(agent, '_engine'))
        names = {t['function']['name'] for t in agent.TOOLS}
        self.assertFalse(names & RETIRED)
        self.assertIn('get_fundamentals', names)
        for path in [ROOT / 'script/tool_catalog.json', * (ROOT / 'script/agent_skills').rglob('*.md')]:
            for name in RETIRED:
                self.assertNotIn(name, path.read_text(encoding='utf-8'), str(path))

    def test_every_advertised_tool_has_a_dispatch_branch(self):
        tree = ast.parse((ROOT / 'script/ai_agent.py').read_text(encoding='utf-8'))
        dispatcher = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == '_run_tool')
        dispatched = {n.comparators[0].value for n in ast.walk(dispatcher)
                      if isinstance(n, ast.Compare) and isinstance(n.left, ast.Name) and n.left.id == 'name'
                      and len(n.comparators) == 1 and isinstance(n.comparators[0], ast.Constant)}
        self.assertFalse({t['function']['name'] for t in agent.TOOLS} - dispatched)

    def test_stock_diagnosis_uses_real_evidence_without_the_engine(self):
        messages = [{'role': 'user', 'content': '百诚医药为什么跌这么狠，还能拿吗'}]
        plan = agent._plan_workflow_dag(messages, 1, 7, True)
        names = {name for name, _ in plan['tasks']}
        self.assertTrue({'get_stock_price', 'get_fundamentals'} <= names)
        self.assertFalse(names & RETIRED)
        with patch.object(agent, 'tool_get_fundamentals', return_value={'pe_ttm': 12, 'updated_at': '2026-10-23'}) as read:
            self.assertEqual(agent._run_tool('get_fundamentals', {'symbol': '百诚医药'}, 1, 7)['pe_ttm'], 12)
            read.assert_called_once_with('百诚医药')

    def test_recent_trend_reads_history_only(self):
        messages = [{'role': 'user', 'content': '帮我分析一下圣泉集团这只股票最近的走势'}]
        plan = agent._plan_workflow_dag(messages, 1, 7, False)
        self.assertEqual([name for name, _ in plan['tasks']], ['get_stock_price'])
        with patch.object(agent, 'execute_tool', return_value=json.dumps({'points': [{'date': '2026-10-22', 'close': 10}]})) as execute:
            agent._run_workflow_dag(messages, 1, 7, False)
        self.assertEqual(execute.call_args.args[0], 'get_stock_price')

    def test_missing_history_volume_is_not_invented_during_trimming(self):
        points = [{'date': str(i), 'close': i+1, 'high': i+2, 'low': i, 'volume': None} for i in range(50)]
        result = agent._trim_result('get_stock_price', {'points': points})
        self.assertIsNone(result['summary']['avgVolume'])
        self.assertEqual(len(result['recentPoints']), 30)

    def test_independent_backtests_still_emit_readable_artifacts(self):
        output = io.StringIO()
        with redirect_stdout(output):
            agent._emit_report_artifact({'report_type': 'backtest_result', 'title': '回测结果',
                                         'markdown': '# 回测结果\n收益与回撤', 'summary': '完成'})
        prefix, payload = output.getvalue().strip().split(' ', 1)
        self.assertEqual(prefix, '[ARTIFACT]')
        record = json.loads(payload)
        self.assertEqual(record['type'], 'backtest_result')
        self.assertIn('收益与回撤', record['content_markdown'])
        self.assertIn('回测', record['title'])


if __name__ == '__main__':
    unittest.main()
