export const EXTENSION_DEMO_TOUR_EVENT = 'imac:extension-demo-tour:start';
export const EXTENSION_DEMO_STATE_KEY = 'imac-demo:extension';

export type ExtensionDemoState = {
  active?: boolean;
  startedAt?: number;
  completedAt?: number;
  sessionCompletedAt?: number;
  projectId?: string;
  issueId?: string;
  sessionId?: string;
  projectName: string;
  projectDescription: string;
  projectRelPath: string;
  issueTitle: string;
  issueDescription: string;
  sessionName: string;
  sessionDescription: string;
};

export const EXTENSION_DEMO_DEFAULTS = {
  projectName: '莫比乌斯拓展案例',
  projectDescription: '体验 mobius-extension 技能按 extension 协议迭代拓展原型。',
  projectRelPath: '/imac-demo/extension-demo',
  issueTitle: '基于 extension.json 迭代拓展',
  issueDescription: '请按照 extension.json 协议, 完成一个简单的可运行拓展。',
  sessionName: '完成拓展迭代',
  sessionDescription: '按 mobius-extension 技能, 迭代 extension.json + frontend + backend。',
} satisfies Omit<ExtensionDemoState, 'active' | 'startedAt' | 'completedAt' | 'projectId' | 'issueId' | 'sessionId'>;

export function createExtensionDemoState(): ExtensionDemoState {
  return { active: true, startedAt: Date.now(), ...EXTENSION_DEMO_DEFAULTS };
}
function normalize(v: unknown): ExtensionDemoState | null {
  if (!v || typeof v !== 'object') return null;
  return { ...EXTENSION_DEMO_DEFAULTS, ...(v as Partial<ExtensionDemoState>) };
}
export function readExtensionDemoState(): ExtensionDemoState | null {
  try { const raw = sessionStorage.getItem(EXTENSION_DEMO_STATE_KEY); if (!raw) return null; return normalize(JSON.parse(raw)); } catch { return null; }
}
export function writeExtensionDemoState(s: ExtensionDemoState) { try { sessionStorage.setItem(EXTENSION_DEMO_STATE_KEY, JSON.stringify(s)); } catch {} }
export function patchExtensionDemoState(p: Partial<ExtensionDemoState>) {
  writeExtensionDemoState({ ...(readExtensionDemoState() || createExtensionDemoState()), ...p });
}
export function completeExtensionDemoState() { patchExtensionDemoState({ active: false, completedAt: Date.now() }); }
export function isExtensionDemoProject(id?: string) { const s = readExtensionDemoState(); return !!s?.active && !!id && s.projectId === id; }
export function isExtensionDemoIssue(id?: string) { const s = readExtensionDemoState(); return !!s?.active && !!id && s.issueId === id; }
export function isExtensionDemoSession(id?: string) { const s = readExtensionDemoState(); return !!s?.active && !!id && s.sessionId === id; }
