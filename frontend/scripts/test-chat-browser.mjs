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
  await send('请分析圣泉集团近期走势')
  await emit('activity', { message: '正在查询圣泉集团行情' })
  await page.getByRole('status').filter({ hasText: '正在查询圣泉集团行情' }).waitFor()
  await emit('reasoning', { msg: '不应展示的内部推理SECRET_TRACE' })
  await emit('tool', { name: 'get_stock_price', detail: 'SECRET_TOOL_ARGUMENTS', callId: 'price' })
  await emit('tool_end', { name: 'get_stock_price', summary: 'SECRET_TOOL_RESULTS', callId: 'price' })
  await emit('activity', { message: '正在分析圣泉集团财报' })
  await page.getByRole('status').filter({ hasText: '正在分析圣泉集团财报' }).waitFor()
  assert.equal(await page.getByText('正在查询圣泉集团行情', { exact: true }).count(), 0)
  assert.equal(await page.locator('.timeline-rail, .timeline-step').count(), 0)
  assert.equal(await page.getByRole('button', { name: /思考|正在运行工具|已运行/ }).count(), 0)
  assert.equal(await page.getByText(/SECRET_TRACE|SECRET_TOOL_ARGUMENTS|SECRET_TOOL_RESULTS/).count(), 0)
  await page.waitForTimeout(600)
  const userRow = page.locator('[data-message-role="user"]').first()
  assert.equal(await userRow.locator('> div').evaluate(el=>getComputedStyle(el).borderTopWidth),'0px')
  assert.equal(await userRow.locator('> div').evaluate(el=>getComputedStyle(el).backgroundImage),'none')
  assert.equal(await page.locator('.guanlan-thread').evaluate(el=>getComputedStyle(el).scrollbarWidth),'none')
  assert.equal(await page.locator('textarea').evaluate(el=>getComputedStyle(el).scrollbarWidth),'none')
  await page.screenshot({ path: path.join(output, 'activity-light.png') })
  await emit('token', { msg: '已取得行情，最近价格呈震荡走势。' })
  await emit('artifact', { type: 'report', title: '已生成的研究报告' })
  await emit('error')
  await emit('done', {})
  await emit('token', { msg: '不应追加的旧连接内容' })
  let messages = await snapshot()
  assert.equal(messages.length, 3)
  assert.equal(messages[1].content, '已取得行情，最近价格呈震荡走势。')
  assert.equal(messages[1].artifacts.length, 1)
  assert.ok(!('timeline' in messages[1]) && !('thinking' in messages[1]))
  await page.getByRole('status').filter({ hasText: '连接中断' }).waitFor()
  assert.equal(await page.getByText('正在分析圣泉集团财报', { exact: true }).count(), 0)
  await send('继续分析')
  assert.ok(sent.at(-1).messages.every(message => ['user','assistant'].includes(message.role)))
  await emit('token', { msg: '停止前已输出的补充分析。' })
  await emit('artifact', { title: '补充报告', type: 'report' })
  await page.getByTitle('停止生成', { exact: true }).click()
  messages = await snapshot()
  assert.equal(messages.at(-2).content, '停止前已输出的补充分析。')
  assert.equal(messages.at(-1).notice.kind, 'stopped')
  await send('完成测试')
  await emit('activity', { message: '正在整理圣泉集团的分析结论' })
  await emit('token', { msg: '最终回答正文。' })
  await emit('done', {})
  await page.getByText('最终回答正文。', { exact: true }).waitFor()
  assert.equal(await page.getByText('正在整理圣泉集团的分析结论', { exact: true }).count(), 0)
  await page.waitForFunction(()=>{const el=document.querySelector('.guanlan-thread');return el && el.scrollHeight-el.scrollTop-el.clientHeight<64})
  await page.waitForTimeout(350)
  await page.screenshot({ path: path.join(output, 'answer-only-light.png') })
  history = [{ role: 'assistant', content: '服务端恢复的完整正文', thinking: 'SECRET_HISTORY' }]
  await page.reload()
  await page.getByText('服务端恢复的完整正文', { exact: true }).waitFor()
  assert.equal(await page.getByText('SECRET_HISTORY').count(), 0)
  await send('长回答滚动测试')
  await emit('token', {msg: Array.from({length:60},(_,index)=>`第${index+1}段完整分析，滚动阅读时不应该被进度更新拉回底部。`).join('\n\n')})
  await page.waitForFunction(()=>{const el=document.querySelector('.guanlan-thread');return el && el.scrollHeight>el.clientHeight+500 && el.scrollTop>100})
  await page.locator('.guanlan-thread').hover()
  await page.mouse.wheel(0,-100)
  await page.locator('.guanlan-thread').evaluate(el=>{el.scrollTop=0})
  await page.waitForTimeout(100)
  await emit('activity',{message:'正在核对后续公开资料'})
  await emit('token',{msg:'\n\n补充分析内容。'})
  await page.waitForTimeout(220)
  assert.ok(await page.locator('.guanlan-thread').evaluate(el=>el.scrollTop)<30)
  await emit('done',{})
  await send('确认操作')
  await emit('confirm', { data: JSON.stringify({id:'c1',title:'添加贵州茅台到自选',items:[{action:'add_watchlist',label:'添加贵州茅台',endpoint:'/api/watchlist',method:'POST',body:{stockId:1}}]}) })
  await emit('done', {})
  await page.getByText('添加贵州茅台到自选', { exact: true }).waitFor()
  await send('选择标的')
  await emit('ask', { question:'选择市场',options:[{value:'HK',label:'港股'},{value:'US',label:'美股'}],multiSelect:false })
  await page.getByRole('button', { name:'港股', exact:true }).waitFor()
  await page.getByTitle('停止生成', { exact:true }).click()
  await page.setViewportSize({width:390,height:844})
  await page.emulateMedia({reducedMotion:'reduce'})
  await page.goto(base+'?dark')
  await page.getByText('服务端恢复的完整正文', {exact:true}).waitFor()
  await send('请分析招商银行')
  await emit('activity', { message:'正在分析招商银行的财务表现' })
  await page.getByRole('status').filter({hasText:'正在分析招商银行的财务表现'}).waitFor()
  assert.equal(await page.locator('.chat-activity-text').evaluate(el=>getComputedStyle(el).animationName),'none')
  assert.equal(await page.getByRole('status').filter({hasText:'正在分析招商银行的财务表现'}).locator('button').count(),0)
  await page.waitForFunction(() => {
    let element = document.querySelector('.chat-activity-text')
    if (!element) return false
    while (element && element !== document.body) {
      if (Number(getComputedStyle(element).opacity) < 0.8) return false
      element = element.parentElement
    }
    return true
  })
  await page.screenshot({path:path.join(output,'activity-dark-mobile.png')})
  await emit('token',{msg:'招商银行的分析正文。'})
  await emit('error',{msg:'上下文窗口不足',kind:'error'})
  await page.getByText('招商银行的分析正文。',{exact:true}).waitFor()
  await page.getByRole('status').filter({hasText:'上下文窗口不足'}).waitFor()
  assert.deepEqual(errors, [])
  console.log(JSON.stringify({passed:['borderless message layout','modern composer and concealed scrollbars','manual reading position preserved during streaming','contextual activity replaces prior stage','reasoning and tool payloads never render','no trace nodes or disclosure controls','interruption preserves answer and artifacts','old stream events ignored','user stop preserves output','status excluded from model input','done leaves answer only','legacy history hides private fields','confirmation card retained','choice card retained','dark mobile reduced motion','context errors preserve answer'],screenshots:output}))
} finally {
  if (browser) await browser.close()
  if (server) await server.close()
  await unlink(fixture)
}
