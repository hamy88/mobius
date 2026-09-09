/**
 * wheel-fast-scroll.ts — 长内容快速滚动辅助
 *
 * 场景: 聊天/jsonl 消息流动辄几百条, 鼠标滚轮一格 100px 太慢, 滚到顶部要滚几十下。
 * 两条加速通道 (均作用于最近的滚动祖先容器, 不劫持普通滚动):
 *
 *   1. Alt + 滚轮   → 每个 notch 跳 25% 视口高 (4 下到底/到顶)
 *   2. Ctrl 已被浏览器占用 (缩放), Shift 保留给水平滚动, 故只用 Alt。
 *
 * 实现: 全局 wheel 监听 (capture), 命中 Alt 时 preventDefault 接管,
 * 找 target 最近的可滚动祖先 (overflow auto/scroll 且确有溢出), 按比例滚动。
 * fail-open: 找不到容器/任何异常都放行原生行为。
 */

const ALT_STEP_RATIO = 0.25 // 每个 notch 25% 视口
const MAX_STEP_PX = 800 // 单次上限, 防超巨视口一跳过头

function findScrollableAncestor(start: Element | null): HTMLElement | null {
  let el: Element | null = start
  for (let i = 0; i < 24 && el; i += 1) {
    if (el instanceof HTMLElement && el !== document.body) {
      const style = window.getComputedStyle(el)
      const oy = /(auto|scroll|overlay)/.test(style.overflowY)
      const canScroll = el.scrollHeight > el.clientHeight + 1
      if (oy && canScroll) return el
    }
    el = el.parentElement
  }
  // 兜底: 文档整体滚动
  const doc = document.scrollingElement as HTMLElement | null
  if (doc && doc.scrollHeight > doc.clientHeight + 1) return doc
  return null
}

export function installWheelFastScroll(): () => void {
  const onWheel = (e: WheelEvent) => {
    if (!e.altKey) return
    // 触控板双指/惯性滚动的 deltaY 可能极小, 照常放大即可
    const target = e.target instanceof Element ? e.target : null
    const container = findScrollableAncestor(target)
    if (!container) return

    e.preventDefault()
    e.stopPropagation()
    const unit = Math.min(container.clientHeight * ALT_STEP_RATIO, MAX_STEP_PX)
    // deltaY 正=向下; 触控板 deltaMode 0=像素, 鼠标滚轮通常 ±100 左右, 归一为 notch 符号
    const notches = Math.sign(e.deltaY) * Math.min(Math.abs(e.deltaY) / 50, 3)
    container.scrollTop += unit * notches
  }

  window.addEventListener('wheel', onWheel, { passive: false, capture: true })
  return () => window.removeEventListener('wheel', onWheel, { capture: true } as any)
}
