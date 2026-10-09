/**
 * Is this process running with an administrator token?
 *
 * Only a *report* — nothing here changes what aimux does. What decides whether
 * `--enable-gui` raises a UAC prompt is aimux's own `IsUserAnAdmin()` check on
 * the daemon, and Windows children inherit the parent's token, so a daemon
 * started from an elevated terminal is already elevated and never asks. The
 * wizard uses this to stop warning about a prompt that will not appear.
 *
 * The token's integrity level is read from `whoami /groups` rather than by
 * running an admin-only command and checking its exit code: `whoami` works
 * unelevated too, and the SID it prints is not localized.
 */
import { spawnSync } from 'node:child_process'

/** High (elevated by UAC) and System (elevated by service) integrity levels. */
const ELEVATED_SIDS = ['S-1-16-12288', 'S-1-16-16384']

/** Interpret `whoami /groups` output: a High or System mandatory label means elevated. */
export function parseElevatedGroups(output: string): boolean {
  return ELEVATED_SIDS.some(sid => output.includes(sid))
}

function readWhoamiGroups(): string | null {
  try {
    const r = spawnSync('whoami', ['/groups'], { encoding: 'utf8', windowsHide: true })
    return r.status === 0 ? `${r.stdout ?? ''}` : null
  } catch { return null }
}

let elevatedCache: boolean | undefined = undefined

/** Cached; one probe per process. False off Windows, and on any probe failure. */
export function windowsElevated(): boolean {
  if (elevatedCache === undefined) {
    elevatedCache = process.platform === 'win32' ? parseElevatedGroups(readWhoamiGroups() ?? '') : false
  }
  return elevatedCache
}
