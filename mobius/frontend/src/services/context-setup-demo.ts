export const CONTEXT_SETUP_DEMO_TOUR_EVENT = 'imac:context-setup-demo-tour:start';
export const CONTEXT_SETUP_DEMO_STATE_KEY = 'imac-demo:context-setup';

export type ContextSetupDemoState = {
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

export const CONTEXT_SETUP_DEMO_DEFAULTS = {
  projectName: '项目知识与 Skill 注入案例',
  projectDescription: '体验 project knowledge + skill prompt 注入对完成质量的差异。',
  projectRelPath: '/imac-demo/context-setup',
  issueTitle: '复用一个 JS 工具方法',
  issueDescription: '请基于项目记忆中已注入的工具函数, 完成一个日期格式化任务。',
  sessionName: '完成一个日期格式化小任务',
  sessionDescription: '直接调用项目记忆里已经准备好的日期格式化函数, 输出三种格式。',
} satisfies Omit<ContextSetupDemoState, 'active' | 'startedAt' | 'completedAt' | 'projectId' | 'issueId' | 'sessionId'>;

export function createContextSetupDemoState(): ContextSetupDemoState {
  return { active: true, startedAt: Date.now(), ...CONTEXT_SETUP_DEMO_DEFAULTS };
}
function normalize(v: unknown): ContextSetupDemoState | null {
  if (!v || typeof v !== 'object') return null;
  return { ...CONTEXT_SETUP_DEMO_DEFAULTS, ...(v as Partial<ContextSetupDemoState>) };
}
export function readContextSetupDemoState(): ContextSetupDemoState | null {
  try { const raw = sessionStorage.getItem(CONTEXT_SETUP_DEMO_STATE_KEY); if (!raw) return null; return normalize(JSON.parse(raw)); } catch { return null; }
}
export function writeContextSetupDemoState(s: ContextSetupDemoState) {
  try { sessionStorage.setItem(CONTEXT_SETUP_DEMO_STATE_KEY, JSON.stringify(s)); } catch {}
}
export function patchContextSetupDemoState(p: Partial<ContextSetupDemoState>) {
  writeContextSetupDemoState({ ...(readContextSetupDemoState() || createContextSetupDemoState()), ...p });
}
export function completeContextSetupDemoState() { patchContextSetupDemoState({ active: false, completedAt: Date.now() }); }
export function isContextSetupDemoProject(id?: string) { const s = readContextSetupDemoState(); return !!s?.active && !!id && s.projectId === id; }
export function isContextSetupDemoIssue(id?: string) { const s = readContextSetupDemoState(); return !!s?.active && !!id && s.issueId === id; }
export function isContextSetupDemoSession(id?: string) { const s = readContextSetupDemoState(); return !!s?.active && !!id && s.sessionId === id; }
