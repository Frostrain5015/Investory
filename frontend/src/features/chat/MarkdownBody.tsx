import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { BASE } from '@/services/api'
import { remarkQuoteTokens, parseQuoteToken } from '@/lib/quoteLinks'
import QuoteChip from '@/components/QuoteChip'

const MARKDOWN_BODY_CLASS =
  'prose prose-sm prose-slate max-w-none text-[13px] [&_table]:text-[11px] [&_th]:border [&_th]:border-slate-200 [&_th]:px-2 [&_th]:py-1 [&_td]:border [&_td]:border-slate-100 [&_td]:px-2 [&_td]:py-1 [&_table]:w-full [&_code]:bg-slate-100 [&_code]:px-1 [&_code]:rounded [&_pre]:bg-slate-100 [&_pre]:p-2 [&_pre]:rounded-lg [&_pre]:overflow-auto'

const MARKDOWN_COMPONENTS = {
  code: ({ children, className, ...props }: { children?: React.ReactNode; className?: string }) => {
    const txt = String(children).trim()
    if (!className) {
      const quote = parseQuoteToken(txt)
      if (quote) return <QuoteChip {...quote} />
    }
    if (/^\d{4,6}\.(SH|SZ|HK|US)$/i.test(txt) || /^[A-Z]{1,5}\.US$/i.test(txt))
      return (
        <a
          href={`${BASE}/stock?symbol=${txt}`}
          target="_blank"
          className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded bg-purple-50 text-purple-700 text-xs font-medium hover:bg-purple-100"
        >
          {txt}
        </a>
      )
    return (
      <code className={className} {...props}>
        {children}
      </code>
    )
  },
}

export default function MarkdownBody({ text, streaming }: { text: string; streaming?: boolean }) {
  return (
    <div className={`${MARKDOWN_BODY_CLASS}${streaming ? ' streaming-md' : ''}`}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm, remarkQuoteTokens]}
        components={MARKDOWN_COMPONENTS}
      >
        {text}
      </ReactMarkdown>
    </div>
  )
}
