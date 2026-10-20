// 行情标注：把观澜正文里的"标的 现价(涨跌幅)"变成可点击、按涨跌上色的行情芯片。
// 两条来源汇聚到同一种 token（inlineCode: `名称|现价|±x%[|symbol]`）：
//   1) 模型按系统 prompt 直接产出该 token（规范、无歧义）；
//   2) remarkQuoteTokens 把散文（如"上证指数3716.28（+0.24%）""标普500-1.09%"）保守地转成该 token。
// 解析标的→symbol 走别名表 + searchStocks 精确匹配；解析不到就不上色/不加链接（避免误伤"同比-15%"）。

import { searchStocks } from '@/services/api'

export interface QuoteToken {
  name: string
  price?: string
  chg?: string   // 形如 "+0.24%" / "-1.09%"，可空
  symbol?: string // 模型若直接给了 CODE.MARKET 则优先用
  original?: string // 散文插件生成的原始文本；解析不到标的时原样回显，避免改写措辞
}

// 常见指数别名/简称 → 已在 stocks 表确认存在的 symbol。数字/简称名（标普500、纳指）
// 靠这里命中；个股交给 searchStocks 精确匹配。沪深300/中证500 未作为指数入库，故意不收录，
// 以免解析到同名 ETF 造成误导链接。
export const INDEX_ALIASES: Record<string, string> = {
  上证指数: '000001.SH', 上证: '000001.SH', 沪指: '000001.SH',
  深证成指: '399001.SZ', 深成指: '399001.SZ', 深指: '399001.SZ',
  创业板指: '399006.SZ', 创业板: '399006.SZ',
  恒生指数: 'HSI.HK', 恒指: 'HSI.HK',
  恒生科技: 'HSTECH.HK', 恒生科技指数: 'HSTECH.HK',
  标普500: 'GSPC.US', 标普: 'GSPC.US', 标普五百: 'GSPC.US',
  纳斯达克综合: 'IXIC.US', 纳斯达克: 'IXIC.US', 纳指: 'IXIC.US',
  道琼斯工业: 'DJI.US', 道琼斯: 'DJI.US', 道指: 'DJI.US',
}

export interface ResolvedQuote { symbol: string; name: string; price?: string }

const quoteCache = new Map<string, ResolvedQuote | null>()

/** 名称 → 标的：别名表优先（指数简称），否则 searchStocks；先精确同名，再退而求"全名包含查询词"
 *  的模糊匹配（让"中免"命中"中国中免"）。返回规范全名 + 现价（缺失现价时用于补全）。
 *  非标的词（如"同比"）searchStocks 无同名/包含结果 → null。 */
export async function resolveQuote(name: string): Promise<ResolvedQuote | null> {
  const key = name.trim()
  if (!key) return null
  if (quoteCache.has(key)) return quoteCache.get(key) ?? null
  let out: ResolvedQuote | null = null
  const alias = INDEX_ALIASES[key]
  if (alias) {
    out = { symbol: alias, name: key }
  } else {
    try {
      const items = await searchStocks(key)
      const hit = items.find(it => it.name === key)
        || items.find(it => it.name && it.name.includes(key))
      if (hit) {
        out = {
          symbol: hit.symbol,
          name: hit.name || key,
          price: hit.price && hit.price !== 0 ? String(hit.price) : undefined,
        }
      }
    } catch {
      out = null
    }
  }
  quoteCache.set(key, out)
  return out
}

/** 解析行内代码 token：`名称|现价|涨跌幅%`、`名称||涨跌幅%`，可选第 4 段为 symbol 或原始散文文本。
 *  非该形状返回 null（交回默认 code 渲染）。 */
export function parseQuoteToken(raw: string): QuoteToken | null {
  const t = raw.trim()
  if (!t.includes('|')) return null
  const parts = t.split('|')
  if (parts.length < 3) return null   // 至少 name|price|chg 三段
  const name = parts[0].trim()
  const price = (parts[1] || '').trim()
  const chg = (parts[2] || '').trim()
  const rest = parts.slice(3).join('|').trim()
  if (!name) return null
  if (!price && !chg) return null      // 现价和涨跌幅都为空则无标注价值
  const isSymbol = /^[A-Za-z0-9.]+\.(SH|SZ|HK|US)$/i.test(rest)
  return {
    name,
    price: price || undefined,
    chg: chg || undefined,
    symbol: isSymbol ? rest : undefined,
    original: rest && !isSymbol ? rest : undefined,
  }
}

// —— 散文 → token 的保守识别 ——
// 名称：别名表里的名字（含数字/简称，按长度倒序优先匹配），或 ≥2 个中日韩/拉丁字母。
// 数字名（标普500）只能靠别名命中，通用名不含数字，避免把价格数字吞进名称。
const ALIAS_NAMES = Object.keys(INDEX_ALIASES).sort((a, b) => b.length - a.length)
const esc = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
const NAME_ALT = `${ALIAS_NAMES.map(esc).join('|')}|[\\p{Script=Han}A-Za-z]{2,}`
// 名称 + 可选现价 + 涨跌幅（括号内带正负 或 直接紧跟带正负的百分号）。必须有涨跌幅才转，
// 这样"重回5.12%""上涨0.24%"（无正负号/无括号）不会被误伤。
const QUOTE_RE = new RegExp(
  `(?<name>${NAME_ALT})\\s*(?<price>\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)?\\s*`
  + `(?:[（(]\\s*(?<chg>[+-]?\\d+(?:\\.\\d+)?)\\s*%\\s*[）)]|(?<chgSign>[+-]\\d+(?:\\.\\d+)?%))`,
  'gu',
)

export interface Piece { code: boolean; value: string }

/** 把一段纯文本切成 [文本/ token] 序列；无命中返回单一文本 piece。 */
export function extractQuoteTokens(text: string): Piece[] {
  const out: Piece[] = []
  let last = 0
  QUOTE_RE.lastIndex = 0
  let m: RegExpExecArray | null
  while ((m = QUOTE_RE.exec(text))) {
    const g = m.groups ?? {}
    const raw = m[0]
    const name = g.name
    const price = g.price
    const chg = g.chg != null ? normChg(g.chg) : g.chgSign
    // 只有带涨跌幅、且名称不是纯数字误配时才转 token
    if (name && chg) {
      if (m.index > last) out.push({ code: false, value: text.slice(last, m.index) })
      // 第 4 段带上原始散文文本：解析到标的则渲染规范芯片，否则原样回显不改写措辞
      out.push({ code: true, value: `${name}|${price ?? ''}|${chg}|${raw}` })
      last = m.index + raw.length
    }
  }
  if (last === 0) return [{ code: false, value: text }]
  if (last < text.length) out.push({ code: false, value: text.slice(last) })
  return out
}

function normChg(chg: string): string {
  const c = chg.trim()
  if (!c) return c
  return /^[+-]/.test(c) ? c : `+${c}`
}

// —— remark 插件：仅遍历 text 节点，跳过 link/inlineCode/code，避免破坏既有链接与代码 ——
interface MdastNode {
  type: string
  value?: string
  children?: MdastNode[]
  url?: string
}

function makeText(value: string): MdastNode { return { type: 'text', value } }
function makeCode(value: string): MdastNode { return { type: 'inlineCode', value } }

function walk(node: MdastNode): void {
  if (!node.children) return
  const next: MdastNode[] = []
  for (const child of node.children) {
    if (child.type === 'text' && typeof child.value === 'string' && child.value.includes('%')) {
      const pieces = extractQuoteTokens(child.value)
      if (pieces.length > 1) {
        for (const p of pieces) next.push(p.code ? makeCode(p.value) : makeText(p.value))
        continue
      }
    }
    if (child.type !== 'link' && child.type !== 'inlineCode' && child.type !== 'code') walk(child)
    next.push(child)
  }
  node.children = next
}

export function remarkQuoteTokens() {
  return (tree: MdastNode) => { walk(tree) }
}
