/**
 * Mobius terminal entry point.
 *
 * Run:  npx tsx src/main.tsx   (or `npm start`)
 *
 * Ink owns Ctrl+C globally so Windows users can always exit, including from
 * setup screens that do not mount the chat composer.
 */
import React from 'react'
import { render } from 'ink'
import { App } from './App.js'
import { createInkInputStream } from './lib/windows-input.js'
import { createTerminalSizeProbe } from './lib/terminal-size.js'

// Ask the terminal for its real text-area size before Ink lays anything out:
// on Windows (ConPTY, and cmd whose screen buffer is wider than its window) the
// size Node reports can be a stale, too-wide value until the window is resized
// by hand. See lib/terminal-size.ts.
const stdout = process.stdout
const stdin = createInkInputStream(createTerminalSizeProbe(process.stdin, stdout), stdout)

render(React.createElement(App), {
  exitOnCtrlC: true,
  stdin,
})
