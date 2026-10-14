/**
 * encrypted-secrets.tsx — <MOBIUS-ENC:v1:...> 密文占位符的前端渲染
 *
 * ⚠️ 加密层已关闭 (2026-10-14, 用户授权 #108). 本模块的所有对外 API 改为 pass-through:
 *   - renderWithSecretChips(text, messageId) → 始终返回 null (调用方走原文本渲染路径)
 *   - hasEncryptedPlaceholder(text)          → 始终 false
 *   - SecretChip                              → 不再实例化, 仅占位
 *   - maskEncryptedPlaceholders               → re-export 自 text-redaction (也是 no-op)
 *
 * 历史: 后端 secret-guard 原本会把密码/密钥/token 加密成 <MOBIUS-ENC:v1:...> 占位符.
 * 前端把占位符渲染为 🔒 徽标 (非明文); 消息本人点击可调 /api/messages/:id/reveal 按需解密
 * 查看原文 (仅限自己的消息, 后端校验 user_id).
 *
 * 当前: 占位符原样展示. 用户体验从 "🔒 + reveal" 变 "原文直显".
 *
 * 回滚: /data/backup/mobius-encryption-rollback-<TS>.tar.gz (chmod 600).
 */
import React from 'react'

export { maskEncryptedPlaceholders } from '../services/text-redaction'
export { maskEncryptedPlaceholders as default } from '../services/text-redaction'

// 兼容老调用方 (chat.tsx 用); 占位符形态不再生成, 故无 chip 可渲染 → 返回 null 让上层走原文本.
const ENC_PLACEHOLDER_RE = /<MOBIUS-ENC:v1:[A-Za-z0-9_-]+>/g

export function hasEncryptedPlaceholder(_text: unknown): boolean {
  return false
}

/**
 * 把含占位符的文本渲染为 React 节点数组 (普通段 + SecretChip).
 * 加密层已关闭, 永远返回 null: 调用方走原文本渲染路径, 占位符以原文展示.
 */
export function renderWithSecretChips(
  _text: string,
  _messageId?: number | string,
): React.ReactNode[] | null {
  return null
}

// SecretChip 组件保留 import 以兼容可能的第三方 type 引用; 实际不渲染任何 UI.
export function SecretChip(_props: { messageId?: number | string; onRevealed?: (plain: string) => void }) {
  return null
}
