import io
from contextlib import redirect_stdout
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import ai_agent as agent
from agent_activity import safe_status, tool_activity


class ActivityTest(unittest.TestCase):
    def test_contextual_fallback_and_model_generated_descriptions(self):
        self.assertIn('圣泉集团',tool_activity('get_fundamentals',{'symbol':'圣泉集团'})['message'])
        self.assertIn('招商银行',tool_activity('get_stock_price',{'symbol':'招商银行','days':90})['message'])
        self.assertEqual(tool_activity('web_search',{},'正在核实圣泉集团的公告')['origin'],'model')
        for text in ['内部推理内容','正在get_stock_price','正在分析，因为我认为这是低估','正在读取 sk-private','正在查询 · 正在分析','正在运行<tool>']:
            self.assertIsNone(safe_status(text))
        self.assertNotIn('sk-',tool_activity('web_search',{'query':'sk-private'})['message'])

    def test_progress_metadata_does_not_change_read_cache_identity(self):
        tools=[tool for tool in agent.TOOLS if tool['function']['name']=='get_stock_price']
        runner=agent._TurnToolRunner(tools,[],0,0)
        with patch.object(agent,'execute_tool',return_value='{"price":100}') as execute,redirect_stdout(io.StringIO()):
            runner.run('get_stock_price',{'symbol':'600519.SH','_status':'正在查看贵州茅台报价'},'a')
            runner.run('get_stock_price',{'symbol':'600519.SH','_status':'正在核对贵州茅台报价'},'b')
        execute.assert_called_once()

    def test_internal_metadata_never_enters_business_tool_arguments(self):
        with patch.object(agent,'_run_tool',return_value={'price':100}) as run,patch.object(agent,'_log_tool'),redirect_stdout(io.StringIO()) as output:
            agent.execute_tool('get_stock_price',{'symbol':'600519.SH','_status':'正在查看贵州茅台报价'},0,0,'a')
        self.assertEqual(run.call_args.args[1],{'symbol':'600519.SH'})
        frames=[json.loads(line[11:]) for line in output.getvalue().splitlines() if line.startswith('[ACTIVITY] ')]
        self.assertEqual(frames[0]['origin'],'model')


if __name__=='__main__':unittest.main()
