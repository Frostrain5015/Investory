import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { writeFile, unlink, mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import os from 'node:os'
import { createServer } from 'vite'

// Use the host's Playwright without adding it to production dependencies.
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const root = fileURLToPath(new URL('../', import.meta.url))
const fixture = path.join(root, '.chat-regression.tsx')
const output = process.env.TEST_ARTIFACT_DIR || path.join(os.tmpdir(), 'investory-chat-regression')
await mkdir(output, { recursive: true })
await writeFile(fixture, `
import React from 'react'
import { createRoot } from 'react-dom/client'
import { MemoryRouter } from 'react-router-dom'
import ChatPanel from './src/pages/ChatPanel'
import { I18nContext } from './src/i18n/I18nContext'
import { ModelsContext } from './src/hooks/use-models'
import { ThemeCtx } from './src/hooks/use-theme'
import { getChatMessages } from './src/features/chat/store'
import './src/index.css'
const dark = new URLSearchParams(location.search).has('dark')
document.documentElement.classList.toggle('dark', dark)
const settings = {models:[{id:1, provider:'openai', model:'测试模型', configured:true, baseUrl:'', hasKey:true, contextWindow:8192}],activeModelId:1,maxModels:3}
Object.assign(window, { chatSnapshot: getChatMessages })
createRoot(document.getElementById('root')!).render(<MemoryRouter><I18nContext.Provider value={{lang:'zh',setLang:()=>{},toggleLang:()=>{}}}><ThemeCtx.Provider value={{theme:dark?'dark':'light',pref:dark?'dark':'light',isDark:dark,setPref:()=>{},toggleTheme:()=>{}}}><ModelsContext.Provider value={{settings,loading:false,busy:false,error:'',save:async()=>settings,remove:async()=>settings,select:async()=>settings}}><ChatPanel defaultMode="expanded" /></ModelsContext.Provider></ThemeCtx.Provider></I18nContext.Provider></MemoryRouter>)
`, { flag: 'wx' })
let server
let browser
const errors = []
try {
  server = await createServer({ root, configFile: path.join(root, 'vite.config.ts'), server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'chat-regression-page', configureServer(vite) {
    vite.middlewares.use('/__chat-regression', async (req, res) => {
      res.setHeader('Content-Type', 'text/html')
      res.end(await vite.transformIndexHtml('/__chat-regression', '<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head><body><div id="root"></div><script type="module" src="/.chat-regression.tsx"></script></body></html>'))
    })
  } }] })
  await server.listen()
  const address = server.httpServer.address()
  const base = `http://127.0.0.1:${address.port}/__chat-regression`
  browser = await chromium.launch({ headless: true, ...(process.env.CHAT_TEST_BROWSER ? { executablePath: process.env.CHAT_TEST_BROWSER } : {}) })
  const page = await browser.newPage({ viewport: { width: 1060, height: 820 } })
  page.on('pageerror', error => errors.push(error.message))
  let history = []
  let requestFailure = false
  const sent = []
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    let data = {}
    if (url.pathname.endsWith('/history')) data = { messages: history, conversationId: 7 }
    if (url.pathname.endsWith('/status')) data = { active: false }
    if (url.pathname.endsWith('/suggestions')) data = { suggestions: [] }
    if (url.pathname.endsWith('/chat')) { sent.push(route.request().postDataJSON()); data = requestFailure ? { error: '上下文窗口不足' } : { conversationId: 7 } }
    if (url.pathname.endsWith('/cancel')) data = { cancelled: true }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(data) })
  })
  await page.addInitScript(() => {
    window.chatStreams = []
    class FakeSource extends EventTarget {
      constructor() { super(); this.closed = false; window.chatStreams.push(this) }
      close() { this.closed = true }
    }
    window.EventSource = FakeSource
  })
  const snapshot = () => page.evaluate(() => window.chatSnapshot())
  const emit = async (name, data) => page.evaluate(({name, data}) => {
    const stream = window.chatStreams.at(-1)
    stream.dispatchEvent(data === undefined ? new Event(name) : new MessageEvent(name, { data: JSON.stringify(data) }))
  }, { name, data })
  const send = async text => {
    const count = await page.evaluate(() => window.chatStreams.length)
    await page.locator('textarea').fill(text)
    await page.locator('textarea').press('Enter')
    await page.waitForFunction(count => window.chatStreams.length > count, count)
  }
  await page.goto(base)
  await send('请分析近期走势')
  await emit('tool', { name: 'get_stock_price', callId: 'price' })
  await emit('tool_end', { name: 'get_stock_price', callId: 'price', summary: '已取得90天行情' })
  await emit('token', { msg: '已取得行情，最近价格呈震荡走势。' })
  await emit('artifact', { type: 'stocksage_report', title: '已生成的研究报告' })
  await emit('tool', { name: 'get_stock_report', callId: 'report', category: 'analysis' })
  await emit('error')
  await emit('done', {})
  await emit('token', { msg: '不应追加的旧连接内容' })
  let messages = await snapshot()
  assert.equal(messages.length, 3)
  assert.equal(messages[1].timeline[0].summary, '已取得90天行情')
  assert.equal(messages[1].content, '已取得行情，最近价格呈震荡走势。')
  assert.equal(messages[1].artifacts.length, 1)
  assert.equal(messages[1].timeline.at(-1).done, true)
  assert.equal(messages[2].notice.kind, 'error')
  await page.getByRole('status').filter({ hasText: '连接中断' }).waitFor()
  const row = page.getByRole('status').filter({ hasText: '连接中断' })
  assert.equal(await row.evaluate(el => getComputedStyle(el).borderTopWidth), '0px')
  await page.screenshot({ path: path.join(output, 'chat-interrupted-light.png') })
  await send('继续分析')
  assert.ok(sent.at(-1).messages.every(message => ['user','assistant'].includes(message.role)))
  await emit('token', { msg: '停止前已输出的补充分析。' })
  await emit('artifact', { title: '补充报告', type: 'report' })
  await emit('tool', { name: 'get_stock_price', callId: 'stop-me' })
  await page.getByTitle('停止生成', { exact: true }).click()
  await emit('error', { msg: '旧事件' })
  messages = await snapshot()
  assert.equal(messages.length, 6)
  assert.equal(messages[4].artifacts[0].title, '补充报告')
  assert.equal(messages[4].content, '停止前已输出的补充分析。')
  assert.equal(messages[5].notice.kind, 'stopped')
  assert.equal(messages[1].artifacts.length, 1)
  history = messages.map(message => message.notice ? { role: 'notice', content: JSON.stringify({ ...message.notice, message: message.content }) } : { ...message, thinking: message.timeline ? JSON.stringify(message.timeline) : undefined })
  await page.goto(base + '?dark=1')
  await page.getByRole('status').filter({ hasText: '你已停止这次回复。' }).waitFor()
  assert.equal((await snapshot()).length, 6)
  await page.waitForTimeout(800) // The shell's existing entrance animation includes a delay.
  await page.screenshot({ path: path.join(output, 'chat-interrupted-dark.png') })
  await send('再次继续')
  await emit('token', { msg: '上下文错误之前的正文。' })
  await emit('error', { msg: '上下文窗口不足' })
  messages = await snapshot()
  assert.equal(messages.length, 9)
  assert.equal(messages[7].content, '上下文错误之前的正文。')
  assert.equal(messages[8].notice.kind, 'error')
  requestFailure = true
  await page.locator('textarea').fill('触发请求错误')
  await page.locator('textarea').press('Enter')
  await page.waitForFunction(() => window.chatSnapshot().length === 11)
  assert.equal((await snapshot())[10].content, '上下文窗口不足')
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: path.join(output, 'chat-interrupted-mobile.png') })
  history = []
  requestFailure = false
  await page.setViewportSize({ width: 1060, height: 820 })
  await page.goto(base)
  await send('连续工具折叠验证')
  await emit('tool', { name: 'get_stock_price', callId: 'g1' })
  await emit('tool', { name: 'get_stock_report', callId: 'g2', category: 'analysis' })
  const runningGroup = page.getByRole('button', { name: '正在运行工具', exact: true })
  await runningGroup.waitFor()
  assert.equal(await runningGroup.getAttribute('aria-expanded'), 'false')
  await runningGroup.click()
  assert.equal(await runningGroup.getAttribute('aria-expanded'), 'true')
  await emit('tool_end', { name: 'get_stock_price', callId: 'g1', summary: '行情已取得' })
  await emit('tool_fail', { name: 'get_stock_report', callId: 'g2', error: '引擎暂时不可用' })
  const completedGroup = page.getByRole('button', { name: /已运行 2 个工具/ })
  await completedGroup.waitFor()
  assert.match(await completedGroup.getAttribute('title'), /1 个工具中断或失败/)
  await page.waitForTimeout(250) // Inspect the expanded content after its height transition.
  const childRail = await page.locator('.timeline-rail').first().evaluate(el => ({ height: el.getBoundingClientRect().height, width: getComputedStyle(el).width, background: getComputedStyle(el).backgroundColor }))
  assert.ok(childRail.height > 10)
  assert.equal(childRail.width, '1px')
  await page.screenshot({ path: path.join(output, 'chat-tool-group-expanded.png') })
  await completedGroup.click()
  assert.equal(await completedGroup.getAttribute('aria-expanded'), 'false')
  await emit('token', { msg: '工具组之间的正文。' })
  await emit('tool', { name: 'get_stock_price', callId: 'g3' })
  await emit('tool', { name: 'web_search', callId: 'g4' })
  assert.equal(await page.getByRole('button', { name: /正在运行工具|已运行 2 个工具/ }).count(), 2)
  await emit('tool_end', { name: 'get_stock_price', callId: 'g3' })
  await emit('tool_end', { name: 'web_search', callId: 'g4', summary: '2 条资料' })
  await emit('done', {})
  await page.waitForTimeout(550)
  assert.equal(await page.locator('.timeline-rail').count(), 2)
  assert.ok((await page.locator('.timeline-rail').first().boundingBox()).height > 20)
  await page.screenshot({ path: path.join(output, 'chat-tool-groups-collapsed.png') })
  assert.deepEqual(errors, [])
  console.log(JSON.stringify({ passed: ['network interruption preserves output', 'old stream events ignored', 'user stop preserves reports', 'status excluded from model input', 'history restoration', 'context error preserves text', 'request error appends status', 'light/dark/mobile render', 'consecutive tools collapse and expand', 'group warnings reflect child errors', 'answer text separates independent groups'], screenshots: output }))
} finally {
  if (browser) await browser.close()
  if (server) await server.close()
  await unlink(fixture)
}
