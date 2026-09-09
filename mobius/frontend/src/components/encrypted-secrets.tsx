/**
 * encrypted-secrets.tsx — <MOBIUS-ENC:v1:...> 密文占位符的前端渲染
 *
 * 后端 secret-guard 在消息落库前把密码/密钥/token 加密为占位符。前端把占位符
 * 渲染为 🔒 徽标 (非明文); 消息本人点击可调 /api/messages/:id/reveal 按需解密
 * 查看原文 (仅限自己的消息, 后端校验 user_id)。
 *
 * 展示原则: 默认只有徽标; reveal 失败/非本人一律保持徽标, 不阻塞渲染。
 */
import React, { useState } from 'react'

const ENC_PLACEHOLDER_RE = /<MOBIUS-ENC:v1:[A-Za-z0-9_-]+>/g

export function hasEncryptedPlaceholder(text: unknown): boolean {
  return typeof text === 'string' && text.includes('<MOBIUS-ENC:v1:')
}

type SecretChipProps = {
  messageId?: number | string
  onRevealed?: (plain: string) => void
}

function SecretChip({ messageId, onRevealed }: SecretChipProps) {
  const [revealed, setRevealed] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const reveal = async (e: React.MouseEvent) => {
    e.stopPropagation()
    if (revealed || loading) {
      // 已展开时再点 = 收起
      if (revealed && !loading) setRevealed(null)
      return
    }
    if (!messageId) { setError('未知消息'); return }
    setLoading(true)
    setError('')
    try {
      const token = window.localStorage.getItem('cc-token')
      const headers: Record<string, string> = { 'Content-Type': 'application/json' }
      if (token) headers.Authorization = `Bearer ${token}`
      const res = await fetch(`/api/messages/${messageId}/reveal`, { method: 'POST', headers })
      const data = await res.json().catch(() => ({}))
      if (!res.ok) throw new Error((data as { error?: string })?.error || `HTTP ${res.status}`)
      setRevealed(String((data as { content?: string }).content || ''))
      onRevealed?.(String((data as { content?: string }).content || ''))
    } catch (err) {
      setError((err as Error)?.message || '解密失败')
    } finally {
      setLoading(false)
    }
  }

  if (revealed != null) {
    return (
      <button
        type="button"
        onClick={reveal}
        title="点击收起"
        className="inline-flex items-center gap-1 px-1.5 py-0.5 mx-0.5 rounded font-mono text-[12px] align-baseline transition-colors"
        style={{
          background: 'rgba(16,185,129,0.12)',
          border: '1px solid rgba(16,185,129,0.35)',
          color: '#10b981',
        }}
      >
        🔓 <span className="break-all">{revealed}</span>
      </button>
    )
  }

  return (
    <button
      type="button"
      onClick={reveal}
      title={messageId ? '加密片段 · 点击查看原文（仅消息本人）' : '系统已加密的隐秘片段'}
      className="inline-flex items-center gap-1 px-1.5 py-0.5 mx-0.5 rounded text-[12px] align-baseline transition-colors"
      style={{
        background: 'rgba(245,158,11,0.12)',
        border: '1px solid rgba(245,158,11,0.35)',
        color: '#f59e0b',
        cursor: messageId ? 'pointer' : 'default',
      }}
    >
      🔒 {loading ? '解密中…' : error || '已加密'}
    </button>
  )
}

/**
 * 把含占位符的文本渲染为 React 节点数组: 普通段 + SecretChip。
 * 无占位符时返回原文本 (调用方按原路径渲染)。
 */
export function renderWithSecretChips(
  text: string,
  messageId?: number | string,
): React.ReactNode[] | null {
  if (!hasEncryptedPlaceholder(text)) return null
  const out: React.ReactNode[] = []
  let cursor = 0
  let key = 0
  for (const m of String(text).matchAll(ENC_PLACEHOLDER_RE)) {
    const at = (m as RegExpMatchArray).index ?? 0
    if (at > cursor) out.push(text.slice(cursor, at))
    out.push(<SecretChip key={`enc-${key++}`} messageId={messageId} />)
    cursor = at + m[0].length
  }
  if (cursor < text.length) out.push(text.slice(cursor))
  return out
}
