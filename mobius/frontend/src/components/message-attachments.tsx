/**
 * message-attachments.tsx — 用户消息内 "[附件]" 块的芯片化渲染
 *
 * 发送侧把附件拼进消息文本 (chat.tsx / assistant-chat.tsx):
 *   [附件]
 *   - [图片] /abs/path.png
 *   - [文件] /abs/path.txt
 *
 * 此前气泡里这段是裸文本 (机器格式直接可见), 与图片卡的渲染风格割裂,
 * 用户难以分辨"我发的是文件还是图片"。本组件把该块解析为统一的附件芯片:
 *   [图片] → 缩略图 (经 /api/download 代理加载, 点击放大)
 *   [文件] → 扩展名徽章 + 文件名 (点击下载)
 * 其余正文文本原样保留。
 *
 * 解析失败 / 无附件块 → 返回 null, 调用方走原纯文本路径 (fail-open)。
 */
import React, { useState } from 'react'

export type ParsedMessageAttachment = {
  kind: 'image' | 'file'
  path: string
  name: string
}

const ATTACHMENT_LINE_RE = /^\s*[-*]\s*\[(图片|文件)\]\s+(.+?)\s*$/

/** 从消息文本头部解析 "[附件] 块", 返回 { attachments, body } 或 null (无块) */
export function parseAttachmentBlock(content: string): { attachments: ParsedMessageAttachment[]; body: string } | null {
  const text = String(content || '')
  if (!text.includes('[附件]')) return null

  const lines = text.split(/\r?\n/)
  let i = 0
  // 跳过 [附件] 标题行之前的空行/引用行 (引用块 > 开头时附件块在其后)
  let attachHeader = -1
  for (; i < lines.length; i += 1) {
    if (lines[i].trim() === '[附件]') { attachHeader = i; break }
    // 标题行之前只允许空行与引用行
    if (lines[i].trim() && !lines[i].trimStart().startsWith('>')) return null
  }
  if (attachHeader < 0) return null

  const attachments: ParsedMessageAttachment[] = []
  let j = attachHeader + 1
  for (; j < lines.length; j += 1) {
    const m = lines[j].match(ATTACHMENT_LINE_RE)
    if (!m) break
    const rawPath = m[2].replace(/^["'`<]+/, '').replace(/[>"'`]+$/, '')
    if (!rawPath) break
    const name = rawPath.split('/').pop() || rawPath
    attachments.push({ kind: m[1] === '图片' ? 'image' : 'file', path: rawPath, name })
  }
  if (attachments.length === 0) return null

  const body = [...lines.slice(0, attachHeader), ...lines.slice(j)]
    .join('\n')
    .replace(/^\s*\n+/, '')
    .replace(/\n+\s*$/, '')
  return { attachments, body }
}

function fileDownloadProxy(path: string): string {
  return `/api/download?path=${encodeURIComponent(path)}`
}

function AttachmentImageThumb({ att }: { att: ParsedMessageAttachment }) {
  const [failed, setFailed] = useState(false)
  const [zoom, setZoom] = useState(false)
  if (failed) {
    return <span className="msg-att-chip msg-att-chip--file" title={att.path}>🖼 {att.name}</span>
  }
  return (
    <>
      <button
        type="button"
        className="msg-att-chip msg-att-chip--image"
        title={`${att.name} · 点击放大`}
        aria-label={`图片附件 ${att.name}`}
        onClick={(e) => { e.stopPropagation(); setZoom(true) }}
      >
        <img src={fileDownloadProxy(att.path)} alt={att.name} loading="lazy" onError={() => setFailed(true)} />
      </button>
      {zoom && (
        <div className="msg-att-zoom" role="dialog" aria-label={`放大 ${att.name}`} onClick={() => setZoom(false)}>
          <img src={fileDownloadProxy(att.path)} alt={att.name} onClick={(e) => e.stopPropagation()} />
          <button type="button" className="msg-att-zoom__close" aria-label="关闭" onClick={() => setZoom(false)}>✕</button>
        </div>
      )}
    </>
  )
}

function AttachmentFileChip({ att }: { att: ParsedMessageAttachment }) {
  const ext = (att.name.split('.').pop() || 'FILE').slice(0, 5).toUpperCase()
  return (
    <a
      className="msg-att-chip msg-att-chip--file"
      href={fileDownloadProxy(att.path)}
      download={att.name}
      title={`${att.name} · 点击下载`}
      onClick={(e) => e.stopPropagation()}
    >
      <span className="msg-att-chip__badge">{ext}</span>
      <span className="msg-att-chip__name">{att.name}</span>
    </a>
  )
}

/**
 * 渲染用户消息正文: 有 [附件] 块 → 芯片列表 + 正文; 无块 → 原文本。
 */
export function MessageContentWithAttachments({ content }: { content: string }): React.ReactNode {
  const parsed = parseAttachmentBlock(content)
  if (!parsed) return <>{content}</>
  return (
    <>
      {parsed.attachments.map((att) => (
        att.kind === 'image'
          ? <AttachmentImageThumb key={att.path} att={att} />
          : <AttachmentFileChip key={att.path} att={att} />
      ))}
      {parsed.body && <span className="msg-att-body">{parsed.body}</span>}
    </>
  )
}
