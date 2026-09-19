import type { ChangeEvent, ClipboardEvent, CSSProperties, FocusEvent, KeyboardEvent, RefObject } from 'react'
import { Mic, RefreshCw, SendHorizontal, Square, Zap } from 'lucide-react'
import { AdvancedInteractionBtn } from './advanced-interaction-btn'
import type { VoiceInputState } from '../services/assistant-voice'

type EasySessionChatInputProps = {
  input: string
  inputRef: RefObject<HTMLTextAreaElement>
  inputPlaceholder: string
  inputFocused: boolean
  theme: string
  voiceState: VoiceInputState
  voiceTip: string
  voiceBusy: boolean
  messageSubmitting: boolean
  anyUploading: boolean
  hasPendingSend: boolean
  modelAvailable: boolean
  onChange: (event: ChangeEvent<HTMLTextAreaElement>) => void
  onKeyDown: (event: KeyboardEvent<HTMLTextAreaElement>) => void
  onPaste: (event: React.ClipboardEvent<HTMLDivElement>) => void
  onFocus: () => void
  onBlur: (event: FocusEvent<HTMLDivElement>) => void
  onToggleVoice: () => void
  onSend: (urgent?: boolean) => void
}

/** Isolated composer for easy mode; the standard composer does not share its layout. */
export function EasySessionChatInput({
  input,
  inputRef,
  inputPlaceholder,
  inputFocused,
  theme,
  voiceState,
  voiceTip,
  voiceBusy,
  messageSubmitting,
  anyUploading,
  hasPendingSend,
  modelAvailable,
  onChange,
  onKeyDown,
  onPaste,
  onFocus,
  onBlur,
  onToggleVoice,
  onSend,
}: EasySessionChatInputProps) {
  const disabled = (!input.trim() && !anyUploading) || anyUploading || hasPendingSend || messageSubmitting || voiceBusy || !modelAvailable
  const sendBg = disabled ? (theme !== 'light' ? '#374151' : '#e5e7eb') : (theme !== 'light' ? '#ffffff' : '#111827')
  const sendFg = disabled ? (theme !== 'light' ? '#6b7280' : '#9ca3af') : (theme !== 'light' ? '#111827' : '#ffffff')
  const border = theme !== 'light' ? 'rgba(255,255,255,0.10)' : 'rgba(0,0,0,0.08)'

  return (
    <div
      data-tour="session-chat-input"
      className="easy-session-chat-input relative min-w-0 w-full overflow-hidden rounded-[22px] transition-all focus-within:ring-2 focus-within:ring-blue-500/15"
      style={{
        height: 96,
        minHeight: 0,
        maxHeight: 96,
        background: 'color-mix(in srgb, var(--bg-secondary) 94%, transparent)',
        border: `1px solid ${border}`,
        boxShadow: inputFocused
          ? '0 4px 20px rgba(0,0,0,0.28), 0 0 0 1px rgba(255,255,255,0.02) inset'
          : '0 2px 12px rgba(0,0,0,0.22), 0 0 0 1px rgba(255,255,255,0.02) inset',
        backdropFilter: 'blur(22px)',
        WebkitBackdropFilter: 'blur(22px)',
      } as CSSProperties}
      onPaste={onPaste}
      onFocusCapture={onFocus}
      onBlurCapture={onBlur}
    >
      <div className="px-3 pt-3 pb-2.5">
        {!input && (
          <div className="pointer-events-none absolute inset-x-3 top-3 z-10 grid min-w-0 grid-cols-2 gap-x-3 text-[11px] leading-[1.35]" style={{ color: 'var(--placeholder-color)' }}>
            <span className="col-span-2 min-w-0 truncate">发送指令：</span>
            <span className="min-w-0 truncate">· Shift+Enter 换行</span>
            <span className="min-w-0 truncate">· ↑键回溯</span>
          </div>
        )}
        <textarea
          ref={inputRef}
          value={input}
          onChange={onChange}
          onKeyDown={onKeyDown}
          placeholder={input ? inputPlaceholder : undefined}
          className="h-[42px] min-h-[42px] max-h-[42px] w-full resize-none overflow-y-auto border-0 bg-transparent px-0 pt-0 pb-1 text-[15px] leading-[1.6] focus:outline-none"
          style={{ color: 'var(--text-primary)' }}
        />
      </div>
      <div className="flex min-w-0 items-center justify-end gap-2 px-3 pb-3 pt-0 overflow-hidden">
        <AdvancedInteractionBtn
          onClick={onToggleVoice}
          disabled={messageSubmitting || voiceState === 'transcribing'}
          aria-pressed={voiceState === 'recording'}
          label={voiceTip}
          tooltip={voiceTip}
          accent="cyan"
          motion="breathe"
          buttonClassName="h-7 w-7 flex-shrink-0 rounded-full"
          iconClassName="h-[17px] w-[17px]"
          style={{ color: voiceState === 'recording' ? '#f87171' : '#d1d5db', border: '1px solid rgba(255,255,255,0.12)' }}
          icon={voiceState === 'recording' ? <Square className="h-[17px] w-[17px]" fill="currentColor" /> : voiceState === 'transcribing' ? <RefreshCw className="h-[17px] w-[17px] animate-spin" /> : <Mic className="h-[17px] w-[17px]" />}
        />
        <AdvancedInteractionBtn
          onClick={() => onSend(true)}
          disabled={disabled}
          data-tour="session-chat-send-urgent"
          label="加急发送"
          tooltip="发送（加急）— 打断当前输出并立即发送"
          accent="amber"
          motion="breathe"
          buttonClassName="h-7 w-7 flex-shrink-0 rounded-full"
          iconClassName="h-[17px] w-[17px]"
          style={{ color: '#d1d5db', border: '1px solid rgba(255,255,255,0.12)' }}
          icon={<Zap className="h-[17px] w-[17px]" />}
        />
        <AdvancedInteractionBtn
          onClick={() => onSend()}
          disabled={disabled}
          data-tour="session-chat-send"
          label="发送"
          tooltip={voiceBusy ? voiceTip : hasPendingSend || messageSubmitting ? '正在提交上一条消息...' : '发送 (Enter)'}
          accent="emerald"
          motion="breathe"
          buttonClassName="h-7 w-7 flex-shrink-0 rounded-full"
          iconClassName="h-[18px] w-[18px]"
          style={{ background: sendBg, color: sendFg, cursor: disabled ? 'not-allowed' : 'pointer' }}
          icon={anyUploading || voiceState === 'transcribing' ? <RefreshCw className="h-4 w-4 animate-spin" /> : <SendHorizontal className="h-[18px] w-[18px]" strokeWidth={2.4} />}
        />
      </div>
    </div>
  )
}
