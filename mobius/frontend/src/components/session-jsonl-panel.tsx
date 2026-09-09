import { lazy, memo, Suspense, useEffect, useMemo, useRef, useState, type RefObject } from 'react'
import { JsonlLiveTailCard, JsonlView } from './jsonl-view'
import { VSCodeOpenProvider } from './jsonl-vscode-link'
import type { SessionHistoryStore } from '../services/agent-history-store'
import { useHistorySnapshotOf } from '../services/agent-history-store'

const EasyJsonlView = lazy(() => import('./easy-jsonl/EasyJsonlView'))

// ── 最新可解析时间戳 (LIVE 卡锚点 / 诊断用). 从尾部向前找, 跳过无时间戳的元数据条目. ──
// 从 chat.tsx 迁入 (Chat 不再订阅快照, 摊平条目的派生消费集中到本面板).
function parseDebugTimestamp(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null
  const ms = new Date(value as string | number).getTime()
  return Number.isFinite(ms) ? ms : null
}

export function findLatestEntryTimestamp(entries: any[]): {
  value: string | null
  index: number | null
  source: string | null
} {
  const candidates: Array<{ source: string; get: (entry: any) => unknown }> = [
    { source: 'timestamp', get: (entry) => entry?.timestamp },
    { source: 'created_at', get: (entry) => entry?.created_at },
    { source: 'payload.timestamp', get: (entry) => entry?.payload?.timestamp },
    { source: 'message.created_at', get: (entry) => entry?.message?.created_at },
  ]
  for (let index = entries.length - 1; index >= 0; index -= 1) {
    const entry = entries[index]
    for (const candidate of candidates) {
      const value = candidate.get(entry)
      if ((typeof value === 'string' || typeof value === 'number') && parseDebugTimestamp(value) !== null) {
        return { value: String(value), index, source: candidate.source }
      }
    }
  }
  return { value: null, index: null, source: null }
}

type SessionJsonlPanelProps = {
  currentProjectId: string
  chatContainerRef: RefObject<HTMLDivElement>
  endRef: RefObject<HTMLDivElement>
  // agent-history-store 实例 (快照订阅在本面板内部 — Chat 不随每条数据重渲染).
  historyStore: SessionHistoryStore | null
  // 空会话占位文案的状态输入 (文案规则见下, 由 Chat 的会话状态派生).
  derivedStatus: string
  showJsonlMeta: boolean
  backendAlive: boolean | null
  backendWorking: boolean | null
  backendPid: number | null
  realTimeInfo?: string
  hasNewMessages: boolean
  onScrollPositionChange: (userScrolledUp: boolean) => void
  onJumpToBottom: () => void
  // 搜索结果跳转: 命中条目 uuid / timestamp, JsonlView 解析到所属组后滚动.
  scrollToEntryUuid?: string | null
  scrollToMatchTs?: string | null
  onMatchScrollResolved?: () => void
  onEasyRoundCountChange?: (count: number) => void
  easyExpandAllSignal?: number
  variant?: 'standard' | 'easy'
}

function SessionJsonlPanelInner({
  currentProjectId,
  chatContainerRef,
  endRef,
  historyStore,
  derivedStatus,
  showJsonlMeta,
  backendAlive,
  backendWorking,
  backendPid,
  realTimeInfo,
  hasNewMessages,
  onScrollPositionChange,
  onJumpToBottom,
  scrollToEntryUuid,
  scrollToMatchTs,
  onMatchScrollResolved,
  onEasyRoundCountChange,
  easyExpandAllSignal,
  variant = 'standard',
}: SessionJsonlPanelProps) {
  // 订阅下沉: 快照/摊平条目/派生值都在本组件内算, Chat 只递 store.
  const historySnapshot = useHistorySnapshotOf(historyStore)
  // URL 中的 match/ts 会在首次精确滚动完成后被上层清理，命中视觉反馈不能随之消失。
  // 面板本地保留本次目标，直到切换到另一份 historyStore（即离开当前会话）。
  const [highlightTarget, setHighlightTarget] = useState<{ uuid: string | null; ts: string | null } | null>(null)
  useEffect(() => {
    if (scrollToEntryUuid || scrollToMatchTs) {
      setHighlightTarget({ uuid: scrollToEntryUuid || null, ts: scrollToMatchTs || null })
    }
  }, [scrollToEntryUuid, scrollToMatchTs])
  const previousStoreRef = useRef(historyStore)
  useEffect(() => {
    if (previousStoreRef.current !== historyStore) {
      previousStoreRef.current = historyStore
      setHighlightTarget(null)
    }
  }, [historyStore])
  const effectiveScrollToEntryUuid = scrollToEntryUuid || highlightTarget?.uuid || null
  const effectiveScrollToMatchTs = scrollToMatchTs || highlightTarget?.ts || null
  const visibleJsonl = useMemo(
    () => (historyStore ? historyStore.flattenEntries() : []),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [historyStore, historySnapshot.rev],
  )
  const jsonlInitialLoading = !historySnapshot.negotiated && historySnapshot.groups.length === 0 && !historySnapshot.error
  // 空会话占位文案: pending(刚发消息等创建进程) / running(agent 在跑等首条输出) 时给 loading 文案,
  // 由 JsonlView 配 spinner 显示; idle/waiting(终态空, 不会有数据自动到来) 时留空.
  const jsonlEmptyLoadingText = visibleJsonl.length === 0
    ? (derivedStatus === 'pending'
        ? (backendAlive ? '智能体进程已创建，联络中' : '正在创建智能体进程，请稍等')
        : derivedStatus === 'running' ? '智能体工作中，等待输出…' : '')
    : ''
  const lastTimestamp = useMemo(() => findLatestEntryTimestamp(visibleJsonl).value, [visibleJsonl])
  // 上一帧 scrollTop, 用于"方向性"解除判定 (仅向上滚才算用户解除钉底).
  const lastScrollTopRef = useRef<number | null>(null)

  return (
    <div data-tour="session-jsonl-view" className="mobius-chat-history flex min-w-0 flex-1 flex-col">
      <div
        className="flex-1 overflow-y-auto overflow-x-clip relative"
        ref={chatContainerRef}
        onScroll={(e) => {
          const el = e.currentTarget
          const dist = el.scrollHeight - el.scrollTop - el.clientHeight
          // 方向性解除判定 (调参台定稿, 配套 EntriesAutoScroll 的 lerp 追赶):
          // 只有"向上滚"才算用户解除钉底 — lerp 追赶与程序钉底全是向下的, 追高卡
          // 途中 dist 再大也不误判; 手动滚回贴底 (dist<4) 恢复钉底. 内容切换时
          // scrollTop 被 clamp 到边缘的跳变不算用户滚动.
          const prev = lastScrollTopRef.current
          lastScrollTopRef.current = el.scrollTop
          const clampedToEdge = el.scrollTop <= 0 || el.scrollTop >= el.scrollHeight - el.clientHeight - 0.5
          const movedUp = prev !== null && prev - el.scrollTop > 2 && !clampedToEdge
          if (movedUp && dist > 200) onScrollPositionChange(true)
          else if (dist < 4) onScrollPositionChange(false)
        }}
      >
        <div className="px-5 py-5" style={variant === 'easy' ? { paddingBottom: 176 } : undefined}>
          <VSCodeOpenProvider projectId={currentProjectId}>
            {variant === 'easy' ? (
              <Suspense fallback={<div className="py-10 text-center text-[12px] text-[var(--text-muted)]">正在整理简易对话...</div>}>
                <EasyJsonlView
                  entries={visibleJsonl}
                  emptyLoadingText={jsonlEmptyLoadingText}
                  initialLoading={jsonlInitialLoading}
                  working={!!(backendAlive && backendWorking)}
                  liveText={realTimeInfo}
                  scrollToEntryUuid={effectiveScrollToEntryUuid}
                  scrollToMatchTs={effectiveScrollToMatchTs}
                  onScrollResolved={onMatchScrollResolved}
                  onRoundCountChange={onEasyRoundCountChange}
                  expandAllSignal={easyExpandAllSignal}
                />
              </Suspense>
            ) : (
              <JsonlView
                snapshot={historySnapshot}
                store={historyStore}
                title=""
                emptyLoadingText={jsonlEmptyLoadingText}
                initialLoading={jsonlInitialLoading}
                showMeta={showJsonlMeta}
                scrollToEntryUuid={effectiveScrollToEntryUuid}
                scrollToMatchTs={effectiveScrollToMatchTs}
                onScrollResolved={onMatchScrollResolved}
              />
            )}
            {variant === 'standard' && backendAlive && backendWorking && (
              <JsonlLiveTailCard
                lastTimestamp={lastTimestamp}
                pid={backendPid}
                realTimeInfo={realTimeInfo}
              />
            )}
            <div ref={endRef} />
          </VSCodeOpenProvider>
        </div>
      </div>
      {hasNewMessages && (
        <div className="flex justify-center py-1 flex-shrink-0">
          <button onClick={onJumpToBottom} className="px-4 py-1.5 text-[12px] bg-blue-500/90 text-white rounded-full hover:bg-blue-500 transition-colors shadow-md flex items-center gap-1.5">
            <svg className="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M19 14l-7 7m0 0l-7-7m7 7V3" /></svg>
            新消息
          </button>
        </div>
      )}
    </div>
  )
}

export const SessionJsonlPanel = memo(SessionJsonlPanelInner)
