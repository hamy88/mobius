/**
 * Terminal size probe tests.
 *
 * The probe exists because Windows can leave the process with a stale terminal
 * width — a resize delivered while Ink was still booting, or a console whose
 * reported size disagrees with the window (cmd's screen buffer, ConPTY). See
 * src/lib/terminal-size.ts. These tests drive it with fake tty streams so the
 * parts that must hold on every platform are covered.
 */
import { EventEmitter } from 'node:events'
import { PassThrough } from 'node:stream'
import { createTerminalSizeProbe, extractSizeReports, interpretSizeReport } from '../src/lib/terminal-size.js'

let pass = 0, fail = 0
function ok(condition: boolean, message: string) {
  if (condition) { pass += 1; console.log(`  ✓ ${message}`) }
  else { fail += 1; console.error(`  ✗ ${message}`) }
}

const delay = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms))
/** CSI 8 ; n ; n t — the answer to CSI 18 t, given in the terminal's own order. */
const report = (first: number, second: number) => `\x1b[8;${first};${second}t`

function fakeStdin(): NodeJS.ReadStream & { rawCalls: boolean[] } {
  const stdin = new PassThrough() as any
  stdin.isTTY = true
  stdin.isRaw = false
  stdin.rawCalls = []
  stdin.setRawMode = (value: boolean) => { stdin.isRaw = value; stdin.rawCalls.push(value); return stdin }
  return stdin as NodeJS.ReadStream & { rawCalls: boolean[] }
}

function fakeStdout(columns: number, rows: number): NodeJS.WriteStream & { written: string[] } {
  const stdout = new EventEmitter() as any
  stdout.isTTY = true
  stdout.columns = columns      // Node keeps these as plain writable data properties
  stdout.rows = rows
  stdout.written = []
  stdout.write = (chunk: string) => { stdout.written.push(String(chunk)); return true }
  return stdout
}

/** Collect what the app would see downstream of the probe. */
function collector(stream: NodeJS.ReadableStream): { text: () => string } {
  let seen = ''
  stream.on('data', (chunk: Buffer | string) => { seen += String(chunk) })
  return { text: () => seen }
}

function testExtractSizeReports() {
  console.log('\n[SIZE 1] report extraction')
  const one = extractSizeReports(report(30, 113))
  ok(one.reports.length === 1 && one.reports[0][0] === 30 && one.reports[0][1] === 113,
    'a plain report yields both numbers in the order sent')
  const mixed = extractSizeReports(`ab${report(24, 80)}cd`)
  ok(mixed.rest === 'abcd' && mixed.reports.length === 1, 'report bytes are removed and the rest is kept')
  const two = extractSizeReports(`${report(24, 80)}${report(30, 100)}`)
  ok(two.reports.length === 2 && two.reports[1][1] === 100, 'back-to-back reports both parse')
  ok(extractSizeReports('no report here').reports.length === 0, 'ordinary input yields no reports')
}

function testInterpretSizeReport() {
  console.log('\n[SIZE 2] which number is the width')
  // xterm/Windows Terminal: CSI 8 ; height ; width t — a stale 120-wide console
  // answering for a 113-wide window must correct the width, not swap the axes.
  const documented = interpretSizeReport(30, 113, { columns: 120, rows: 30 })
  ok(documented.columns === 113 && documented.rows === 30, 'the documented height;width order is preferred when it is the closer read')
  // tmux answers width first (measured): 100x30 pane replying "100;30" must not
  // be read as 100 rows tall.
  const swapped = interpretSizeReport(100, 30, { columns: 100, rows: 30 })
  ok(swapped.columns === 100 && swapped.rows === 30, 'a width-first terminal is not turned into 100 rows')
  const tie = interpretSizeReport(24, 80, { columns: 0, rows: 0 })
  ok(tie.columns === 80 && tie.rows === 24, 'an unreadable current size falls back to the documented order')
}

async function testAppliesReportedSize() {
  console.log('\n[SIZE 3] the terminal answer wins over a stale stdout size')
  const stdin = fakeStdin()
  const stdout = fakeStdout(120, 30)          // stale, too-wide console size
  const probe = createTerminalSizeProbe(stdin, stdout, { pollMs: 10_000 })
  const seen = collector(probe)
  try {
    ok(stdout.written.join('') === '\x1b[18t', 'the probe asks the terminal with CSI 18 t')
    ok(stdin.rawCalls[0] === true, 'the tty is put in raw mode so the answer is not line-buffered')
    let resized = 0
    stdout.on('resize', () => { resized += 1 })
    stdin.write(report(30, 113))
    await delay(20)
    ok(stdout.columns === 113 && stdout.rows === 30, 'the reported size is mirrored onto stdout')
    ok(resized === 1, 'a resize event is emitted so Ink re-lays out')
    ok(seen.text() === '', 'the report never reaches the app as input')
  } finally { probe.destroy(); stdin.destroy() }
}

async function testSplitReportAcrossChunks() {
  console.log('\n[SIZE 4] a report split across reads')
  const stdin = fakeStdin()
  const stdout = fakeStdout(120, 30)
  const probe = createTerminalSizeProbe(stdin, stdout, { pollMs: 10_000 })
  const seen = collector(probe)
  try {
    stdin.write('\x1b[8;3')
    await delay(5)
    ok(seen.text() === '' && stdout.columns === 120, 'an unfinished report is held, not forwarded or applied')
    stdin.write('0;113t')
    await delay(25)
    ok(stdout.columns === 113 && stdout.rows === 30, 'the finished report applies once the tail arrives')
    ok(seen.text() === '', 'no half-report leaks to the app')
  } finally { probe.destroy(); stdin.destroy() }
}

async function testNormalInputPassesThrough() {
  console.log('\n[SIZE 5] ordinary input is untouched')
  const stdin = fakeStdin()
  const stdout = fakeStdout(120, 30)
  const probe = createTerminalSizeProbe(stdin, stdout, { pollMs: 10_000 })
  const seen = collector(probe)
  try {
    stdin.write('hello \x1b[A\x1b[1;5C\r')
    await delay(30)
    ok(seen.text() === 'hello \x1b[A\x1b[1;5C\r', 'typed text, arrows and Enter pass through unchanged')
    ok(stdout.columns === 120, 'unrelated input does not change the size')
  } finally { probe.destroy(); stdin.destroy() }
}

async function testResizeAsksAgain() {
  console.log('\n[SIZE 6] a real resize re-asks the terminal')
  const stdin = fakeStdin()
  const stdout = fakeStdout(120, 30)
  const probe = createTerminalSizeProbe(stdin, stdout, { pollMs: 10_000 })
  const seen = collector(probe)
  try {
    stdin.write(report(30, 113)); await delay(20)
    ok(stdout.columns === 113, 'first answer applied')
    stdout.written.length = 0
    stdout.emit('resize')                  // a real window resize
    ok(stdout.written.join('') === '\x1b[18t', 'the probe asks again instead of trusting the stale value')
    stdin.write(report(40, 90)); await delay(20)
    ok(stdout.columns === 90 && stdout.rows === 40, 'the new answer replaces the old one')
    ok(stdout.written.length === 1, 'the probe does not re-ask in a loop off its own resize event')
  } finally { probe.destroy(); stdin.destroy() }
}

async function testPollsNodeSize() {
  console.log('\n[SIZE 7] a size that moved without an event still gets repainted')
  const stdin = fakeStdin()
  const stdout = fakeStdout(120, 30)
  const probe = createTerminalSizeProbe(stdin, stdout, { pollMs: 20 })
  try {
    let resized = 0
    stdout.on('resize', () => { resized += 1 })
    stdout.columns = 90                    // the terminal pushed a resize while Ink was booting
    stdout.rows = 24
    await delay(80)
    ok(resized >= 1, 'the periodic re-read nudges Ink once the value moved')
    const settled = resized
    await delay(60)
    ok(resized === settled, 'a size that stopped moving is not nudged again')
  } finally { probe.destroy(); stdin.destroy() }
}

async function testNonsenseIsIgnored() {
  console.log('\n[SIZE 8] unusable answers and non-tty hosts')
  const stdin = fakeStdin()
  const stdout = fakeStdout(120, 30)
  const probe = createTerminalSizeProbe(stdin, stdout, { pollMs: 10_000 })
  try {
    stdin.write(report(0, 0)); await delay(20)
    ok(stdout.columns === 120 && stdout.rows === 30, 'an absurd size is ignored rather than breaking the layout')
    stdin.write(report(900, 5000)); await delay(20)
    ok(stdout.columns === 120, 'an out-of-range width is ignored too')
  } finally { probe.destroy(); stdin.destroy() }

  const pipe = fakeStdin(); pipe.isTTY = false
  const out = fakeStdout(80, 24)
  ok(createTerminalSizeProbe(pipe, out) === pipe, 'a non-tty stdin is returned untouched')

  const tty = fakeStdin()
  const pipedOut = fakeStdout(80, 24); pipedOut.isTTY = false
  ok(createTerminalSizeProbe(tty, pipedOut) === tty, 'a non-tty stdout is returned untouched')
}

async function main() {
  testExtractSizeReports()
  testInterpretSizeReport()
  await testAppliesReportedSize()
  await testSplitReportAcrossChunks()
  await testNormalInputPassesThrough()
  await testResizeAsksAgain()
  await testPollsNodeSize()
  await testNonsenseIsIgnored()
  console.log(`\n==== SIZE RESULT: ${pass} passed, ${fail} failed ====\n`)
  process.exit(fail === 0 ? 0 : 1)
}

main().catch(error => { console.error('FATAL', error); process.exit(2) })
