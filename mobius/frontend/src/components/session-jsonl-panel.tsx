import { lazy, memo, Suspense, type RefObject } from 'react'
import { JsonlLiveTailCard, JsonlView } from './jsonl-view'
import { VSCodeOpenProvider } from './jsonl-vscode-link'
import type { HistorySnapshot } from '../services/agent-history-store'

const EasyJsonlView = lazy(() => import('./easy-jsonl/EasyJsonlView'))

type SessionJsonlPanelProps = {
  currentProjectId: string
  chatContainerRef: RefObject<HTMLDivElement>
  endRef: RefObject<HTMLDivElement>
  // agent-history-store 快照 + store 实例 (视图只发状态机转移意图).
  historySnapshot: HistorySnapshot
  historyStore: import('../services/agent-history-store').SessionHistoryStore | null
  // 简易视图仍吃摊平的已加载条目 (组结构对它是轮次列表, 派生自同一 store).
  visibleJsonl: any[]
  jsonlEmptyLoadingText: string
  jsonlInitialLoading: boolean
  showJsonlMeta: boolean
  backendAlive: boolean | null
  backendWorking: boolean | null
  backendPid: number | null
  realTimeInfo?: string
  lastTimestamp?: string | null
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
  historySnapshot,
  historyStore,
  visibleJsonl,
  jsonlEmptyLoadingText,
  jsonlInitialLoading,
  showJsonlMeta,
  backendAlive,
  backendWorking,
  backendPid,
  realTimeInfo,
  lastTimestamp,
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
  return (
    <div data-tour="session-jsonl-view" className="mobius-chat-history flex min-w-0 flex-1 flex-col">
      <div
        className="flex-1 overflow-y-auto relative"
        ref={chatContainerRef}
        onScroll={(e) => {
          const el = e.currentTarget
          const distFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight
          onScrollPositionChange(distFromBottom > 200)
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
                  scrollToEntryUuid={scrollToEntryUuid}
                  scrollToMatchTs={scrollToMatchTs}
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
                scrollToEntryUuid={scrollToEntryUuid}
                scrollToMatchTs={scrollToMatchTs}
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
