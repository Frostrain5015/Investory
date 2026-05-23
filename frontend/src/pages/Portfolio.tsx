import { useEffect, useState } from 'react'
import { useAuth } from '@/hooks/use-auth'
import { useConfirm } from '@/hooks/use-confirm'
import { useT } from '@/i18n/I18nContext'
import { Card, CardContent } from '@/components/ui/card'
import { Plus, Trash2, Pencil } from 'lucide-react'

interface Portfolio { id: number; userId: number; name: string }

export default function Portfolio() {
  const confirm = useConfirm()
  const { portfolioId, setPortfolioId, setPortfolioName } = useAuth()
  const { t } = useT()
  const [portfolios, setPortfolios] = useState<Portfolio[]>([])
  const [newName, setNewName] = useState('')
  const [editingId, setEditingId] = useState<number | null>(null)
  const [editName, setEditName] = useState('')
  const [loading, setLoading] = useState(true)

  function load() {
    fetch('/investory/api/portfolios', { credentials: 'include' })
      .then(r => r.json()).then(setPortfolios)
      .finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [])

  async function handleCreate() {
    if (!newName.trim()) return
    const form = new URLSearchParams({ name: newName.trim() })
    const res = await fetch('/investory/api/portfolios', {
      method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: form.toString(),
    })
    const p = await res.json()
    setPortfolioId(p.id)
    setNewName('')
    load()
  }

  async function handleSelect(id: number) {
    await fetch(`/investory/api/portfolios/${id}`, { method: 'PUT', credentials: 'include' })
    setPortfolioId(id)
    const p = portfolios.find(p => p.id === id)
    if (p) setPortfolioName(p.name)
  }

  async function handleRename(id: number) {
    if (!editName.trim()) return
    await fetch(`/investory/api/portfolios/${id}`, {
      method: 'PUT', credentials: 'include',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ name: editName.trim() }),
    })
    setPortfolioName(editName.trim())
    load()
  }

  async function handleDelete(id: number) {
    if (!(await confirm(t.portfolio.confirmDelete))) return
    await fetch(`/investory/api/portfolios/${id}`, { method: 'DELETE', credentials: 'include' })
    load()
  }

  if (loading) {
    return (
      <div className="flex flex-col items-center justify-center gap-3 h-96">
        <div className="w-8 h-8 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" />
        <span className="text-sm text-slate-400">{t.portfolio.loading}</span>
      </div>
    )
  }

  return (
    <div className="p-6 max-w-lg mx-auto space-y-6">
      <h2 className="text-xl font-bold text-slate-900 tracking-tight">{t.portfolio.title}</h2>

      <Card>
        <CardContent className="pt-6">
          <div className="space-y-2">
            {portfolios.map(p => (
              <div key={p.id}
                className={`flex items-center justify-between px-4 py-3 rounded-xl transition-colors cursor-pointer ${
                  p.id === portfolioId ? 'bg-slate-900 text-white' : 'hover:bg-slate-50'
                }`}
                onClick={() => editingId !== p.id && handleSelect(p.id)}>
                {editingId === p.id ? (
                  <input value={editName} onChange={e => setEditName(e.target.value)}
                    onBlur={async () => { await handleRename(p.id); setEditingId(null) }}
                    onKeyDown={e => { if (e.key === 'Enter') { handleRename(p.id); setEditingId(null) } }}
                    onClick={e => e.stopPropagation()}
                    className="text-sm font-medium bg-white border border-slate-300 rounded-lg px-2 py-1 text-slate-900 outline-none focus:ring-2 focus:ring-slate-900/10"
                    autoFocus />
                ) : (
                  <span className="text-sm font-medium">{p.name}</span>
                )}
                <div className="flex items-center gap-2">
                  {p.id === portfolioId && (
                    <span className="text-xs bg-emerald-500 text-white px-2 py-0.5 rounded-lg">{t.portfolio.current}</span>
                  )}
                  <button onClick={(e) => { e.stopPropagation(); setEditingId(p.id); setEditName(p.name) }}
                    className={`p-1.5 rounded-lg transition-colors ${p.id === portfolioId ? 'hover:bg-slate-700' : 'hover:bg-slate-100'}`}>
                    <Pencil className="w-3.5 h-3.5" />
                  </button>
                  <button onClick={(e) => { e.stopPropagation(); handleDelete(p.id) }}
                    className={`p-1.5 rounded-lg transition-colors ${p.id === portfolioId ? 'hover:bg-slate-700' : 'hover:bg-slate-100'}`}>
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                </div>
              </div>
            ))}
            {portfolios.length === 0 && (
              <p className="text-center text-slate-400 text-sm py-4">{t.portfolio.noPortfolio}</p>
            )}
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="pt-6">
          <div className="flex gap-3">
            <input type="text" value={newName} onChange={(e) => setNewName(e.target.value)}
              placeholder={t.portfolio.newPortfolioPlaceholder}
              className="flex-1 h-10 rounded-xl border border-slate-200 px-3.5 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/10" />
            <button onClick={handleCreate} disabled={!newName.trim()}
              className="inline-flex items-center gap-1.5 h-10 px-4 rounded-xl bg-slate-900 text-white text-sm font-medium hover:bg-slate-800 transition-colors disabled:opacity-50">
              <Plus className="w-4 h-4" /> {t.portfolio.create}
            </button>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
