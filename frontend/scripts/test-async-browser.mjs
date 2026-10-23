import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { writeFile, unlink, mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import os from 'node:os'
import { createServer } from 'vite'

const require = createRequire(import.meta.url)
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const root = fileURLToPath(new URL('../', import.meta.url))
const fixture = path.join(root, '.async-regression.tsx')
const output = process.env.TEST_ARTIFACT_DIR || path.join(os.tmpdir(), 'investory-async-regression')
await mkdir(output, { recursive: true })
await writeFile(fixture, `
import React from 'react'
import {createRoot} from 'react-dom/client'
import {MemoryRouter,Routes,Route,Link} from 'react-router-dom'
import * as echarts from 'echarts'
import {AuthContext} from './src/hooks/use-auth'
import {I18nContext} from './src/i18n/I18nContext'
import {SettingsProvider} from './src/providers/SettingsProvider'
import {ThemeProvider} from './src/providers/ThemeProvider'
import {ConfirmProvider} from './src/providers/ConfirmProvider'
import {ToastProvider} from './src/components/Toast'
import Market from './src/pages/Market'
import Dashboard from './src/pages/Dashboard'
import StockDetail from './src/pages/StockDetail'
import Holdings from './src/pages/Holdings'
import Transactions from './src/pages/Transactions'
import Dividends from './src/pages/Dividends'
import Portfolio from './src/pages/Portfolio'
import PnlCalendar from './src/pages/PnlCalendar'
import Admin from './src/pages/Admin'
import PageTransition from './src/components/PageTransition'
import AsyncState from './src/components/AsyncState'
import './src/index.css'
const parameters = new URLSearchParams(location.search)
const pages = {Market,Dashboard,StockDetail,Holdings,Transactions,Dividends,Portfolio,PnlCalendar,Admin}
const Page = pages[parameters.get('page') || 'Market']
const SlowPage = React.lazy(() => new Promise(resolve => { window.releasePage = () => resolve({default:()=><h1>延迟页面</h1>}) }))
function InteractivePage() {
  const [count,setCount] = React.useState(0)
  return <><h1>即时页面</h1><button onClick={()=>setCount(value=>value+1)}>更新 {count}</button><section><AsyncState loading /></section></>
}
function TransitionRoutes() {
  return <><nav><Link to="/stock">即时</Link><Link to="/slow">延迟</Link><Link to="/other">其他</Link></nav><Routes><Route element={<PageTransition />}><Route path="/stock" element={<InteractivePage />} /><Route path="/slow" element={<SlowPage />} /><Route path="/other" element={<h1>其他页面</h1>} /></Route></Routes></>
}
const Content = parameters.get('page')==='Transitions' ? TransitionRoutes : Page
const auth = {userId:7,username:'test',portfolioId:1,portfolioName:'测试组合',isAdmin:true,authenticated:true,loading:false,setPortfolioId:()=>{},setPortfolioName:()=>{},logout:()=>{},login:async()=>({success:true}),register:async()=>({success:true})}
window.mapState = () => {
  const element = document.querySelector('[role="img"][aria-label="全球市场"]')
  const chart = element && echarts.getInstanceByDom(element)
  return chart ? chart.getOption().geo[0].regions : []
}
createRoot(document.getElementById('root')!).render(<MemoryRouter initialEntries={['/stock?symbol=1.600519']}><AuthContext.Provider value={auth}><I18nContext.Provider value={{lang:'zh',setLang:()=>{},toggleLang:()=>{}}}><ThemeProvider><SettingsProvider><ToastProvider><ConfirmProvider><Content /></ConfirmProvider></ToastProvider></SettingsProvider></ThemeProvider></I18nContext.Provider></AuthContext.Provider></MemoryRouter>)
`, { flag: 'wx' })
let server, browser
const errors = []
const reports = []
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }
try {
  server = await createServer({ root, configFile: path.join(root, 'vite.config.ts'), server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'async-regression', configureServer(vite) {
    vite.middlewares.use('/__async-regression', async (req, res) => {
      res.setHeader('Content-Type', 'text/html')
      res.end(await vite.transformIndexHtml('/__async-regression', '<!doctype html><html><head><meta charset="utf-8"><style>html,body,#root{height:100%;margin:0}</style></head><body><div id="root"></div><script type="module" src="/.async-regression.tsx"></script></body></html>'))
    })
  } }] })
  await server.listen()
  const base = `http://127.0.0.1:${server.httpServer.address().port}/__async-regression`
  browser = await chromium.launch({ headless: true, ...(process.env.CHAT_TEST_BROWSER ? { executablePath: process.env.CHAT_TEST_BROWSER } : {}) })
  const page = await browser.newPage({ viewport: { width: 1280, height: 850 } })
  page.on('pageerror', error => errors.push(error.message))
  const state = { name: '', phase: 0, detailRequests: 0, chartRequests: 0, progressRequests: 0, retiredRequests: 0 }
  let gate = deferred()
  const point = { date:'2026-10-22',open:100,high:102,low:99,close:101,volume:1000 }
  const prices = [point,{...point,date:'2026-10-23',close:102}]
  const snapshot = { stockId:1,stockSymbol:'1.600519',stockName:'测试股票',market:'SH',currency:'CNY',totalShares:10,avgCost:90,dilutedCost:90,totalInvested:900,totalDividends:0,currentPrice:100,marketValue:1000,unrealizedPnl:100,unrealizedPnlPct:11.11,changeToday:10,changePctToday:1,nativePrice:100,nativeAvgCost:90,nativeInvested:900,nativeMarketValue:1000,nativeUnrealizedPnl:100,priceLive:true }
  const pendingQuote = (symbol,name,flag,lat,lng) => ({symbol,name,flag,lat,lng,price:null,change:null,changePct:null,source:null,state:'unavailable',quoteTime:null,timestampScope:'UNKNOWN',asOfDate:null,receivedAt:null,fallbackReason:'LOADING'})
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    let data = {}, status = 200
    if (url.pathname.includes('/api/admin/crawl/')) state.retiredRequests++
    if (url.pathname.endsWith('/api/admin/status')) data={markets:[{market:'A',stock_count:10}],totals:{stock_count:10},tables:[{total_mb:1}]}
    if (url.pathname.endsWith('/api/admin/users')) data=[]
    if (url.pathname.endsWith('/api/admin/crawl-history')) data=[{market:'A',started_at:'2026-10-22T15:30:00',ended_at:'2026-10-22T15:31:00',rows_written:100,stocks_failed:0,status:'ok'}]
    if (url.pathname === '/api/market/exchange-rates') data={rates:{CNY:1,HKD:1.08,USD:.138},base:'CNY',updatedAt:null}
    if (url.pathname.endsWith('/market/news')) data=[]
    if (url.pathname.endsWith('/indices/progress')) {
      state.progressRequests++
      if (state.phase === 0) await gate.promise
      const cn=pendingQuote('000001.SH','上证指数','CN',31.23,121.47)
      const us=pendingQuote('GSPC.US','标普500','US',40.71,-74.01)
      if(state.phase>=1) Object.assign(cn,{price:3000,change:30,changePct:1,source:'hithink_finance_api',state:'available',quoteTime:new Date().toISOString()})
      if(state.phase>=2) Object.assign(us,{price:5000,change:-50,changePct:-1,source:'yahoo',state:'available',quoteTime:new Date().toISOString()})
      data={indices:[cn,us],indicators:[],sources:[],updatedAt:new Date().toISOString(),hithinkStatus:'active',hithinkError:null,refreshing:state.phase<2}
    }
    if (url.pathname === '/api/dashboard') {
      if(state.name==='slow-summary') await gate.promise
      data={snapshots:[snapshot],allocation:[],totalMarketValue:1000,totalInvested:900,totalPnl:100,realizedPnl:0,cumulativePnl:100,totalReturnPct:11.11,cumulativeReturnPct:11.11,todayPnl:10,todayPnlPct:1,cashBalance:0,cashByCurrency:[]}
    }
    if (url.pathname === '/api/chart') {
      state.chartRequests++
      if(state.name==='slow-curve') { await gate.promise; status=503 }
      if(state.name==='detail' && url.searchParams.get('days')==='365') await gate.promise
      data=url.searchParams.get('type')==='cumulative_return' ? [{date:'2026-10-22',value:1000},{date:'2026-10-23',value:1010}] : prices
      if(url.searchParams.get('type')==='pnl_calendar') data=[]
    }
    if (url.pathname === '/api/stocks/1.600519') { state.detailRequests++; data={stock:{id:1,symbol:'1.600519',name:'测试股票',market:'SH',currency:'CNY'},holding:null,transactions:[],dividends:[],livePrice:100} }
    if (url.pathname.endsWith('/holdings-correlation')) data=[]
    if (url.pathname === '/api/holdings') {
      if(state.name==='holdings' || state.name==='holdings-empty') { await gate.promise; status=503 }
      data={snapshots:[]}
    }
    if (url.pathname === '/api/watchlist') data=state.name==='holdings' ? [{id:1,stock_id:2,symbol:'0.000001',name:'异步自选',market:'SZ',currency:'CNY',price:10,changeToday:1,changePctToday:1}] : []
    if (url.pathname === '/api/portfolios') { if(state.name==='Portfolio') await gate.promise; data=[] }
    if (url.pathname === '/api/transactions') { if(state.name==='Transactions') await gate.promise; data=[] }
    if (url.pathname === '/api/dividends') { if(state.name==='Dividends') await gate.promise; data={dividends:[]} }
    if (url.pathname === '/api/closed-positions') data=[]
    try { await route.fulfill({status,contentType:'application/json',body:JSON.stringify(data)}) } catch { /* A navigation may cancel a deferred route. */ }
  })
  state.name='market'
  await page.goto(`${base}?page=Market`)
  await page.getByRole('img',{name:'全球市场',exact:true}).locator('svg').waitFor()
  assert.equal(state.phase,0)
  await page.evaluate(() => { window.firstMapSvg=document.querySelector('[role="img"] svg') })
  state.phase=1; gate.resolve()
  await page.waitForFunction(() => window.mapState().some(region=>region.name==='China'))
  assert.equal(await page.evaluate(() => window.mapState().some(region=>region.name==='United States')),false)
  state.phase=2
  await page.waitForFunction(() => window.mapState().some(region=>region.name==='United States'))
  assert.equal(await page.evaluate(() => document.querySelector('[role="img"] svg')===window.firstMapSvg),true)
  await page.screenshot({path:path.join(output,'market-progressive.png')})
  reports.push('Map renders before first quote; China colors before USA; chart SVG is reused')

  state.name='slow-curve'; gate=deferred()
  await page.goto(`${base}?page=Dashboard`)
  await page.getByText('测试组合',{exact:true}).waitFor()
  await page.getByText('测试股票',{exact:true}).first().waitFor()
  gate.resolve()
  await page.getByRole('alert').filter({hasText:'此区域暂时无法加载'}).waitFor()
  assert.ok(await page.getByText('测试股票',{exact:true}).count())
  reports.push('Slow/failing cumulative chart does not hide dashboard summary or holdings')

  state.name='slow-summary'; gate=deferred()
  await page.goto(`${base}?page=Dashboard`)
  await page.locator('.recharts-area').first().waitFor()
  gate.resolve()
  await page.getByText('测试股票',{exact:true}).first().waitFor()
  reports.push('Performance curve can render before the asset summary')

  state.name='detail'; state.detailRequests=0; gate=deferred()
  await page.goto(`${base}?page=StockDetail`)
  await page.getByText('测试股票',{exact:true}).waitFor()
  await page.getByRole('button',{name:'1M',exact:true}).click()
  await page.locator('.recharts-area').first().waitFor()
  assert.equal(state.detailRequests,1)
  gate.resolve()
  reports.push('Stock detail survives delayed history; changing period cancels old history without refetching detail')

  state.name='holdings'; gate=deferred()
  await page.goto(`${base}?page=Holdings`)
  await page.getByText('异步自选',{exact:true}).first().waitFor()
  await page.locator('svg polyline').first().waitFor()
  gate.resolve()
  assert.ok(await page.getByText('异步自选',{exact:true}).count())
  await page.getByRole('alert').filter({hasText:'此区域暂时无法加载'}).waitFor()
  reports.push('Watchlist and first sparkline render while holdings are still pending')

  state.name='holdings-empty'; gate=deferred()
  await page.goto(`${base}?page=Holdings`)
  await page.getByRole('heading',{name:'持仓明细',exact:true}).waitFor()
  await page.getByRole('status').waitFor()
  assert.equal(await page.getByText('暂无持仓数据',{exact:true}).count(),0)
  gate.resolve()
  await page.getByRole('alert').waitFor()
  assert.equal(await page.getByText('暂无持仓数据',{exact:true}).count(),0)
  reports.push('A fast empty watchlist never masks pending or failed holdings as an empty portfolio')

  for (const [name,title] of [['Transactions','交易记录'],['Dividends','分红记录'],['Portfolio','我的组合'],['PnlCalendar','盈亏日历']]) {
    state.name=name; gate=deferred()
    await page.goto(`${base}?page=${name}`)
    await page.getByRole('heading',{name:title,exact:true}).waitFor()
    gate.resolve()
    reports.push(`${name}: page header and controls render before data`)
  }
  await page.goto(`${base}?page=Admin`)
  await page.getByRole('heading',{name:'管理后台',exact:true}).waitFor()
  await page.getByText('100',{exact:true}).first().waitFor()
  assert.equal(await page.locator('input[type="date"]').count(),0)
  assert.equal(await page.getByRole('button',{name:/抓取|暂停|继续抓取/}).count(),0)
  assert.equal(state.retiredRequests,0)
  await page.screenshot({path:path.join(output,'admin-retired.png')})
  reports.push('Admin retains past synchronization records while removed collector controls and endpoints are never used')

  await page.goto(`${base}?page=Transitions`)
  await page.getByRole('heading',{name:'即时页面'}).waitFor()
  await page.evaluate(() => { window.firstTransition=document.querySelector('.page-transition') })
  await page.getByRole('button',{name:'更新 0'}).click()
  assert.equal(await page.evaluate(() => document.querySelector('.page-transition')===window.firstTransition),true)
  assert.equal(await page.locator('.page-loading').count(),0)
  await page.getByRole('link',{name:'延迟',exact:true}).click()
  await page.locator('.page-loading-arc').waitFor()
  assert.equal(await page.getByRole('heading',{name:'即时页面'}).count(),0)
  assert.equal(await page.locator('.page-loading .animate-spin').count(),0)
  const waitStyle=await page.locator('.page-loading-arc').evaluate(element=>({animation:getComputedStyle(element).animationName,fill:element.getAttribute('fill')}))
  assert.equal(waitStyle.fill,'none')
  assert.equal(waitStyle.animation,'page-arc-orbit')
  await page.screenshot({path:path.join(output,'page-loading-unified.png')})
  await page.evaluate(()=>window.releasePage())
  await page.getByRole('heading',{name:'延迟页面'}).waitFor()
  const reveal=await page.locator('.page-reveal').evaluate(element=>({animation:getComputedStyle(element).animationName,clip:getComputedStyle(element).clipPath}))
  assert.equal(reveal.animation,'page-circle-reveal')
  assert.ok(reveal.clip.startsWith('circle('))
  await page.waitForFunction(()=>getComputedStyle(document.querySelector('.page-reveal')).clipPath==='none')
  await page.getByRole('link',{name:'其他',exact:true}).click()
  await page.getByRole('heading',{name:'其他页面'}).waitFor()
  await page.waitForFunction(()=>getComputedStyle(document.querySelector('.page-transition')).opacity==='1')
  assert.equal(await page.locator('.page-transition').evaluate(element=>getComputedStyle(element).clipPath),'none')
  reports.push('Circular reveal starts after deferred code resolves; thin orbit replaces the breathing orb; local updates never replace the page')

  await page.emulateMedia({reducedMotion:'reduce'})
  await page.goto(`${base}?page=Transitions`)
  await page.getByRole('link',{name:'延迟',exact:true}).click()
  await page.locator('.page-loading-arc').waitFor()
  assert.equal(await page.locator('.page-loading-arc').evaluate(element=>getComputedStyle(element).animationName),'none')
  await page.emulateMedia({colorScheme:'dark',reducedMotion:'reduce'})
  await page.waitForFunction(()=>document.documentElement.classList.contains('dark'))
  await page.screenshot({path:path.join(output,'page-loading-dark.png')})
  await page.getByRole('link',{name:'其他',exact:true}).click()
  await page.getByRole('heading',{name:'其他页面'}).waitFor()
  assert.equal(await page.locator('.page-reveal').evaluate(element=>getComputedStyle(element).animationName),'none')
  await page.evaluate(()=>window.releasePage())
  assert.equal(await page.getByRole('heading',{name:'延迟页面'}).count(),0)
  reports.push('Reduced motion disables the orbit and circular reveal; leaving a loading page ignores its late completion')
  assert.deepEqual(errors,[])
  await writeFile(path.join(output,'results.json'),JSON.stringify({reports,errors},null,2))
  console.log(reports.join('\n'))
  console.log(`${reports.length} progressive-loading browser scenarios passed`)
} finally {
  if(browser) await browser.close()
  if(server) await server.close()
  await unlink(fixture).catch(()=>{})
}
