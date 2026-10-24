"""Mainland index quotes use the index API rather than equity snapshots/history."""
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import ai_agent as agent
import hithink_finance as hf


class IndexQuoteTest(unittest.TestCase):
    def test_index_transport_preserves_aliases_and_missing_values(self):
        client=hf.Client(hf.Settings(True,'test-only'))
        with patch.object(client,'get',return_value={'timestamp':123,'item':[
            {'thscode':'000001.SH','last_price':100,'prev_price':99},
            {'thscode':'399001.SZ','last_price':90,'prev_price':100,'price_change_ratio_pct':-10},
            {'thscode':'399006.SZ','last_price':None} ]}) as get:
            result=client.index_snapshot(['1.000001','399001.SZ','399006.SZ'])
        self.assertEqual(get.call_args.args[0],'/api/a-share-index/prices/snapshot')
        self.assertEqual(result['1.000001']['price'],100)
        self.assertEqual(result['399001.SZ']['changePct'],-10)
        self.assertEqual(result['1.000001']['timestampScope'],'BATCH')
        self.assertNotIn('399006.SZ',result)
        self.assertNotIn('date',result['1.000001'])
        with patch.object(client,'get',return_value={'item':[{'thscode':'000001.SH'},{'thscode':'000001.SH'}]}),self.assertRaises(hf.HithinkError):
            client.index_snapshot(['000001.SH'])

    def test_three_main_indices_never_enter_equity_or_history_route(self):
        codes=['000001.SH','399001.SZ','399006.SZ']
        client=Mock()
        client.index_snapshot.return_value={code:{'price':100,'source':hf.SOURCE} for code in codes}
        for code in codes:self.assertFalse(agent._quote_uses_hithink(code))
        self.assertTrue(agent._quote_uses_hithink('300750.SZ'))
        with patch.object(hf,'get_client',return_value=client),patch('market_history.latest',side_effect=AssertionError('No history fallback')):
            result=agent._latest_quotes(codes)
        self.assertEqual(set(result),set(codes))
        client.index_snapshot.assert_called_once_with(codes)
        client.snapshot.assert_not_called()


if __name__=='__main__':unittest.main()
