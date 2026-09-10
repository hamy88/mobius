// scroll-debug.ts — F12 控制台里输入 `debug_scroll` 开启实时滚动调试日志.
// 仅开发诊断用; 未开启时 enabled=false, scrollDebug 是空操作, 不影响运行.
//
// 用法: 在浏览器 DevTools 控制台输入 `debug_scroll` (回车) 即开启;
//       输入 `debug_scroll = false` 关闭.

let enabled = false

export function scrollDebug(...args: unknown[]): void {
  if (!enabled) return
  // eslint-disable-next-line no-console
  console.log('[scroll]', ...args)
}

export function isScrollDebugEnabled(): boolean {
  return enabled
}

if (typeof window !== 'undefined') {
  try {
    Object.defineProperty(window, 'debug_scroll', {
      get() {
        enabled = true
        return '✓ scroll debug ON — 滚动/追底时看 console 里的 [scroll] 日志'
      },
      set(v: unknown) {
        enabled = Boolean(v)
      },
      configurable: true,
    })
  } catch {
    // 重复定义或不可配置时忽略
  }
}
