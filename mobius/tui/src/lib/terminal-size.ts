/**
 * Terminal size probe — the Windows width workaround.
 *
 * Two things make `stdout.columns` disagree with the visible window:
 *
 * 1. A missed resize. Windows has no SIGWINCH, so the size only arrives as a
 *    console event. The terminal can push that event while Node is still
 *    booting (`tsx` takes a beat), i.e. before Ink has attached its listener,
 *    and the frame is then laid out with whatever width the process started
 *    with — until the user resizes the window by hand and a fresh event lands.
 * 2. A console that reports the wrong size at all. Classic cmd.exe has a screen
 *    buffer (120 columns by default) that can be wider than its window, and
 *    under ConPTY (Windows Terminal, VS Code, the Win11 default host) the child
 *    talks to a pseudo console whose size is only refreshed on a real resize.
 *
 * Either way Ink lays the frame out a few columns too wide (`ink.js`:
 * `stdout.columns || 80`) and the terminal hard-wraps the overflow — text
 * "falls" onto the next row and borders stop lining up.
 *
 * So the probe does both cheap things: re-reads Node's own value (cures 1) and
 * asks the terminal itself with `CSI 18 t` ("report text area size in
 * characters", cures 2). A terminal that never answers is left as it was.
 */
import { Transform, type TransformCallback } from 'node:stream'

/** Ask the terminal to report its text-area size (CSI 18 t). */
const SIZE_QUERY = '\x1b[18t'
/** The answer: CSI 8 ; <n> ; <n> t. Which number is height and which is width varies — see interpretSizeReport. */
const SIZE_REPORT = /\x1b\[8;(\d+);(\d+)t/g
/**
 * A chunk tail that might still grow into a size report (`\x1b`, `\x1b[`, `\x1b[8;24;80…`).
 * Reports can straddle two reads, so the tail is held for one beat rather than
 * forwarded — otherwise Ink would receive report bytes as junk keystrokes.
 */
const PARTIAL_REPORT = /^\x1b(\[(\d*;)*\d*)?$/
const PARTIAL_HOLD_MS = 15
/** Ignore nonsense sizes rather than handing the layout an unusable width. */
const MIN_COLUMNS = 20, MAX_COLUMNS = 1000, MIN_ROWS = 5, MAX_ROWS = 500
const DEFAULT_POLL_MS = 1_000

export interface TerminalSize { columns: number; rows: number }

/**
 * Pull every complete size report out of `text`, leaving `rest` for the app.
 * The two numbers are returned in the order the terminal sent them; deciding
 * which is which is `interpretSizeReport`'s job.
 */
export function extractSizeReports(text: string): { reports: Array<[number, number]>; rest: string } {
  const reports: Array<[number, number]> = []
  let rest = ''
  let lastIndex = 0
  SIZE_REPORT.lastIndex = 0
  let match = SIZE_REPORT.exec(text)
  while (match) {
    rest += text.slice(lastIndex, match.index)
    reports.push([Number(match[1]), Number(match[2])])
    lastIndex = match.index + match[0].length
    match = SIZE_REPORT.exec(text)
  }
  rest += text.slice(lastIndex)
  return { reports, rest }
}

/**
 * xterm's ctlseqs says the report is `CSI 8 ; height ; width t`, and Windows
 * Terminal follows that — but not every terminal does: tmux answers width first
 * (measured). Both readings are plausible integers, so take the one closer to
 * what we already believe: this probe only ever corrects a *stale* size, and
 * swapping the axes moves both of them by far more than a stale value does.
 * Ties go to the documented order.
 */
export function interpretSizeReport(first: number, second: number, current: TerminalSize): TerminalSize {
  const documented: TerminalSize = { rows: first, columns: second }
  const swapped: TerminalSize = { rows: second, columns: first }
  const drift = (size: TerminalSize) => Math.abs(size.columns - current.columns) + Math.abs(size.rows - current.rows)
  return drift(documented) <= drift(swapped) ? documented : swapped
}

function usable({ columns, rows }: TerminalSize): boolean {
  return columns >= MIN_COLUMNS && columns <= MAX_COLUMNS && rows >= MIN_ROWS && rows <= MAX_ROWS
}

/**
 * Wrap `input` in a stream that swallows terminal size reports, applies them (and
 * Node's own size once it moves) to `output`, and nudges Ink's resize handler so
 * the frame is re-laid out. Returns `input` untouched when probing cannot work
 * (no TTY, or explicitly disabled), so callers can use it unconditionally.
 */
export function createTerminalSizeProbe(
  input: NodeJS.ReadStream,
  output: NodeJS.WriteStream,
  options: { pollMs?: number } = {},
): NodeJS.ReadStream {
  if (process.env.MOBIUS_TUI_DISABLE_SIZE_PROBE === '1') return input
  if (!input.isTTY || !output.isTTY || typeof input.setRawMode !== 'function') return input

  const pollMs = options.pollMs ?? DEFAULT_POLL_MS
  let held = ''
  let holdTimer: ReturnType<typeof setTimeout> | null = null
  let applied: TerminalSize | null = null
  // Our own `emit('resize')` must not be mistaken for a real one and re-ask.
  let applying = false

  const requestSize = (): void => {
    try { output.write(SIZE_QUERY) } catch { /* terminal went away */ }
  }

  const nudge = (): void => {
    applying = true
    try { output.emit('resize') } finally { applying = false }
  }

  const applySize = (size: TerminalSize): void => {
    if (!usable(size)) return
    if (applied && applied.columns === size.columns && applied.rows === size.rows) return
    applied = { columns: size.columns, rows: size.rows }
    // Node keeps columns/rows as writable own properties on a tty stream and
    // updates them the same way, so a plain assignment is enough here.
    output.columns = size.columns
    output.rows = size.rows
    nudge()
  }

  const onResize = (): void => { if (!applying) requestSize() }

  // (1) Node's value can move without an event reaching us — after the terminal
  // pushed its size while Ink was still booting, say. A cheap 1s re-read turns
  // "resize the window by hand to fix it" into "it just settles".
  const pollTimer = setInterval(() => {
    if (applying) return
    const columns = output.columns, rows = output.rows
    if (applied && applied.columns === columns && applied.rows === rows) return
    applied = { columns, rows }
    if (usable(applied)) nudge()
  }, pollMs)
  if (typeof pollTimer.unref === 'function') pollTimer.unref()

  const probe = new Transform({
    transform(chunk: Buffer | string, _encoding: string, callback: TransformCallback) {
      if (holdTimer) { clearTimeout(holdTimer); holdTimer = null }
      const { reports, rest } = extractSizeReports(held + String(chunk))
      held = ''
      for (const [first, second] of reports) {
        applySize(interpretSizeReport(first, second, { columns: output.columns, rows: output.rows }))
      }
      // Hold a tail that could still become a report; forward everything else.
      if (rest.length > 0 && rest.length <= 16 && PARTIAL_REPORT.test(rest)) {
        held = rest
        holdTimer = setTimeout(() => {
          holdTimer = null
          const flushed = held
          held = ''
          if (flushed) probe.push(flushed)
        }, PARTIAL_HOLD_MS)
        callback(null, '')
        return
      }
      callback(null, rest)
    },
    flush(callback: TransformCallback) {
      if (holdTimer) clearTimeout(holdTimer)
      holdTimer = null
      const flushed = held
      held = ''
      callback(null, flushed)
    },
  }) as Transform & Partial<NodeJS.ReadStream>

  // Mimic the tty surface Ink and the win32 input decoder expect downstream.
  Object.defineProperty(probe, 'isTTY', { value: true })
  Object.defineProperty(probe, 'isRaw', { get: () => input.isRaw })
  probe.setRawMode = (enabled: boolean) => { input.setRawMode?.(enabled); return probe as NodeJS.ReadStream }
  probe.ref = () => { input.ref(); return probe as NodeJS.ReadStream }
  probe.unref = () => { input.unref(); return probe as NodeJS.ReadStream }

  output.on('resize', onResize)
  // The answer only arrives once the tty is raw — in cooked mode it would sit in
  // the line buffer until the user pressed Enter.
  try { input.setRawMode(true) } catch { /* not a real tty after all */ }
  requestSize()
  input.pipe(probe)
  return probe as NodeJS.ReadStream
}
