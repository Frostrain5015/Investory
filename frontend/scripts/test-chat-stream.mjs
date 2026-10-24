import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readFileSync } from 'node:fs'
import ts from 'typescript'

const source = readFileSync(new URL('../src/features/chat/streamFailure.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText
const { interruptedTurn, restoreMessage } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)
test('interruption preserves partial answer and generated artifacts', () => {
  const report = { type: 'report', title: '已生成报告' }
  const result = interruptedTurn('已经收到的回答', [report], '连接中断')
  assert.equal(result[0].content, '已经收到的回答')
  assert.deepEqual(result[0].artifacts, [report])
  assert.equal(result[1].notice.kind, 'error')
  assert.ok(!('thinking' in result[0]) && !('timeline' in result[0]))
})

test('an empty interrupted turn only contains a notice', () => {
  assert.deepEqual(interruptedTurn('', [], '连接中断'), [{ role: 'system', content: '连接中断', notice: { kind: 'error' } }])
})

test('user stop preserves strategy review cards and separates the notice', () => {
  const result = interruptedTurn('已经输出的结论', [], '你已停止这次回复。', 'stopped', { hasCode: true, strategyCode: 'saved-code' })
  assert.equal(result[0].strategyCode, 'saved-code')
  assert.equal(result[0].content, '已经输出的结论')
  assert.equal(result[1].notice.kind, 'stopped')
})

test('legacy trace fields are ignored rather than restored into client state', () => {
  const restored = restoreMessage({ role: 'assistant', content: '公开回答', thinking: '不应展示的推理', timeline: [{ kind: 'tool', name: 'secret' }] })
  assert.equal(restored.content, '公开回答')
  assert.ok(!('thinking' in restored) && !('timeline' in restored))
})

test('server-restored long answers and notices retain their complete content', () => {
  const text = '完整正文'.repeat(10000)
  assert.equal(restoreMessage({ role: 'assistant', content: text }).content, text)
  for (const kind of ['error', 'stopped']) {
    assert.deepEqual(restoreMessage({ role: 'notice', content: JSON.stringify({ kind, message: '提示' }) }), { role: 'system', content: '提示', notice: { kind } })
  }
})
