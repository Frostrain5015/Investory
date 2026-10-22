import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readFileSync } from 'node:fs'
import ts from 'typescript'

const source = readFileSync(new URL('../src/features/chat/streamFailure.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText
const { interruptedTurn, restoreMessage } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)
const groupSource = readFileSync(new URL('../src/features/chat/toolGroups.ts', import.meta.url), 'utf8')
const groupCompiled = ts.transpileModule(groupSource, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText
const { groupTimeline, toolGroupState } = await import(`data:text/javascript;base64,${Buffer.from(groupCompiled).toString('base64')}`)

test('only consecutive tools fold together; text and thinking stay in emission order', () => {
  const tool = name => ({ kind: 'tool', name, done: true })
  const steps = [tool('a'), tool('b'), { kind: 'text', text: '回答' }, tool('c'), { kind: 'thinking', text: '思考' }, tool('d'), { kind: 'text', text: ' ' }, tool('e')]
  const groups = groupTimeline(steps)
  assert.deepEqual(groups.map(group => group.kind), ['tools', 'step', 'tools', 'step', 'tools'])
  assert.deepEqual(groups.filter(group => group.kind === 'tools').map(group => group.tools.map(step => step.name)), [['a','b'], ['c'], ['d','e']])
  assert.equal(steps.length, 8)
})

test('group status reflects running, success, mixed failure and interrupted children', () => {
  const ok = { kind: 'tool', name: 'price', done: true }
  const live = { kind: 'tool', name: 'report', done: false }
  const failed = { ...ok, error: '请求失败' }
  assert.deepEqual(toolGroupState([ok, live]), { running: true, errors: 0, state: 'running' })
  assert.deepEqual(toolGroupState([ok, ok]), { running: false, errors: 0, state: 'complete' })
  assert.deepEqual(toolGroupState([failed, live]), { running: true, errors: 1, state: 'warning' })
  assert.deepEqual(toolGroupState([failed, ok]), { running: false, errors: 1, state: 'warning' })
})

test('a tool-only interruption keeps its timeline and stops the spinner', () => {
  const input = [{ kind: 'tool', name: 'get_stock_report', done: false, callId: 'c1' }]
  const result = interruptedTurn('', input, [], '连接中断')
  assert.equal(result.length, 2)
  assert.equal(result[0].timeline[0].done, true)
  assert.equal(result[0].timeline[0].error, '连接中断')
  assert.equal(result[0].timeline[0].callId, 'c1')
  assert.equal(input[0].done, false)
  assert.equal(result[0].content, '')
})

test('partial answer, completed tools and artifacts survive interruption', () => {
  const finished = { kind: 'tool', name: 'get_stock_price', done: true, summary: '10元' }
  const thinking = { kind: 'thinking', text: '已取得价格', _ts: 100 }
  const report = { type: 'stock_report', title: '已生成的报告' }
  const result = interruptedTurn('已收到的回答', [finished, thinking], [report], '引擎超时', 250)
  assert.equal(result[0].content, '已收到的回答')
  assert.equal(result[0].timeline[0].error, undefined)
  assert.equal(result[0].timeline[1]._elapsed, 150)
  assert.deepEqual(result[0].artifacts, [report])
  assert.equal(thinking._elapsed, undefined)
})

test('an empty stream produces only the actual error, never a completed report', () => {
  const result = interruptedTurn('', [], [], '连接中断')
  assert.equal(result.length, 1)
  assert.equal(result[0].role, 'system')
  assert.equal(result[0].content, '连接中断')
  assert.deepEqual(result[0].notice, { kind: 'error' })
})

test('user stop keeps text, completed steps, reports and strategy cards with a separate notice', () => {
  const text = '已经输出的结论'
  const result = interruptedTurn(text, [{ kind: 'text', text }, { kind: 'tool', name: 'get_stock_report', done: false }], [{ title: '报告', type: 'report' }], '你已停止这次回复。', 200, 'stopped', { hasCode: true, strategyCode: 'saved-code' })
  assert.equal(result[0].content, text)
  assert.equal(result[0].timeline[0].text, text)
  assert.equal(result[0].artifacts.length, 1)
  assert.equal(result[0].strategyCode, 'saved-code')
  assert.deepEqual(result[1].notice, { kind: 'stopped' })
  assert.equal(result[1].role, 'system')
})

test('context error preserves prior tool summaries and partial text', () => {
  const step = { kind: 'tool', name: 'get_stock_price', done: true, summary: '已获取90天行情' }
  const result = interruptedTurn('近期走势', [step], [], '上下文窗口不足')
  assert.deepEqual(result[0].timeline, [step])
  assert.equal(result[0].content, '近期走势')
  assert.deepEqual(result[1], { role: 'system', content: '上下文窗口不足', notice: { kind: 'error' } })
})

test('reopened history restores notices and the full long answer from the timeline', () => {
  for (const kind of ['error', 'stopped']) {
    assert.deepEqual(restoreMessage({ role: 'notice', content: JSON.stringify({ kind, message: '提示' }) }), { role: 'system', content: '提示', notice: { kind } })
  }
  const text = '长回答'.repeat(10000)
  const restored = restoreMessage({ role: 'assistant', content: text.slice(0, 8000), thinking: JSON.stringify([{ kind: 'text', text }]) })
  assert.equal(restored.content, text)
  assert.equal(restoreMessage({ role: 'notice', content: '旧状态' }).content, '旧状态')
})
