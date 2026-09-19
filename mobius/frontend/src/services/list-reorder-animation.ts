import { useLayoutEffect, useRef, type RefObject } from 'react'

// 列表顺序变化时的「位移动画」(FLIP): 顺序变化前先记录各项位置, 变化后让每项从旧位置滑动到新位置.
// 只负责「移动」, 不负责增删的淡入淡出; 容器内带 data-flip-key 的后代参与测量与动画.
const FLIP_SELECTOR = '[data-flip-key]'
const FLIP_DURATION_MS = 240
// 与 index.css 的过渡曲线保持一致 (ease-out 强, 起步快收尾稳).
const FLIP_EASING = 'cubic-bezier(0.22, 1, 0.36, 1)'
// 小于该位移视为布局抖动, 不触发动画.
const FLIP_MIN_DELTA_PX = 0.5

type FlipItem = { el: HTMLElement; left: number; top: number }

function prefersReducedMotion(): boolean {
  return !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
}

// 读相对容器内容原点的位置: 扣掉 scrollTop/scrollLeft, 单纯滚动列表不会误触发动画.
function readItems(container: HTMLElement): Map<string, FlipItem> {
  const base = container.getBoundingClientRect()
  const items = new Map<string, FlipItem>()
  container.querySelectorAll<HTMLElement>(FLIP_SELECTOR).forEach(el => {
    const key = el.dataset.flipKey
    if (!key) return
    const rect = el.getBoundingClientRect()
    items.set(key, {
      el,
      left: rect.left - base.left + container.scrollLeft,
      top: rect.top - base.top + container.scrollTop,
    })
  })
  return items
}

/**
 * 给列表容器加顺序变化动画.
 *
 * @param containerRef 列表滚动容器
 * @param orderKey 当前渲染项 key 的拼接串; 只有它变化 (增删/换序/翻页/换模式) 时才测量并动画,
 *                 普通重渲染不产生任何布局读取开销.
 */
export function useListReorderAnimation(containerRef: RefObject<HTMLElement>, orderKey: string) {
  const itemsRef = useRef<Map<string, FlipItem>>(new Map())
  const runningRef = useRef<Map<Element, Animation>>(new Map())

  useLayoutEffect(() => {
    const container = containerRef.current
    if (!container) return
    const previous = itemsRef.current
    const next = readItems(container)
    itemsRef.current = next
    if (prefersReducedMotion()) return
    next.forEach((to, key) => {
      const from = previous.get(key)
      // 首次出现 (无旧位置) 的项不动画, 交给各自的挂载效果.
      if (!from) return
      const dx = from.left - to.left
      const dy = from.top - to.top
      if (Math.abs(dx) < FLIP_MIN_DELTA_PX && Math.abs(dy) < FLIP_MIN_DELTA_PX) return
      const el = to.el
      // 上一次动画未播完就再次换序: 取消旧的, 从新位置重新滑, 避免两段动画叠加.
      runningRef.current.get(el)?.cancel()
      const animation = el.animate(
        [{ transform: `translate(${dx}px, ${dy}px)` }, { transform: 'translate(0px, 0px)' }],
        { duration: FLIP_DURATION_MS, easing: FLIP_EASING },
      )
      runningRef.current.set(el, animation)
      animation.finished.catch(() => {}).finally(() => {
        if (runningRef.current.get(el) === animation) runningRef.current.delete(el)
      })
    })
  }, [containerRef, orderKey])
}
