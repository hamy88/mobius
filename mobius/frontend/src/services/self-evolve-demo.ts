export const SELF_EVOLVE_DEMO_TOUR_EVENT = 'imac:self-evolve-demo-tour:start';
export const SELF_EVOLVE_DEMO_STATE_KEY = 'imac-demo:self-evolve';

export type SelfEvolveDemoState = {
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

export const SELF_EVOLVE_DEMO_DEFAULTS = {
  projectName: '莫比乌斯自进化案例',
  projectDescription: '体验 Project → Issue → Session 流程, 由小莫完成自迭代任务。',
  projectRelPath: '/imac-demo/self-evolve',
  issueTitle: '为项目 README 增补一个使用示例',
  issueDescription: '请在 README.md 里增补一段使用示例(包含 docker compose / npm start 等)。',
  sessionName: 'README 使用示例增补',
  sessionDescription: '增量修改 README.md, 增补使用示例, 不要改其他文件。',
} satisfies Omit<SelfEvolveDemoState, 'active' | 'startedAt' | 'completedAt' | 'projectId' | 'issueId' | 'sessionId'>;

export function createSelfEvolveDemoState(): SelfEvolveDemoState {
  return { active: true, startedAt: Date.now(), ...SELF_EVOLVE_DEMO_DEFAULTS };
}
function normalize(v: unknown): SelfEvolveDemoState | null {
  if (!v || typeof v !== 'object') return null;
  return { ...SELF_EVOLVE_DEMO_DEFAULTS, ...(v as Partial<SelfEvolveDemoState>) };
}
export function readSelfEvolveDemoState(): SelfEvolveDemoState | null {
  try { const raw = sessionStorage.getItem(SELF_EVOLVE_DEMO_STATE_KEY); if (!raw) return null; return normalize(JSON.parse(raw)); } catch { return null; }
}
export function writeSelfEvolveDemoState(s: SelfEvolveDemoState) { try { sessionStorage.setItem(SELF_EVOLVE_DEMO_STATE_KEY, JSON.stringify(s)); } catch {} }
export function patchSelfEvolveDemoState(p: Partial<SelfEvolveDemoState>) {
  writeSelfEvolveDemoState({ ...(readSelfEvolveDemoState() || createSelfEvolveDemoState()), ...p });
}
export function completeSelfEvolveDemoState() { patchSelfEvolveDemoState({ active: false, completedAt: Date.now() }); }
export function isSelfEvolveDemoProject(id?: string) { const s = readSelfEvolveDemoState(); return !!s?.active && !!id && s.projectId === id; }
export function isSelfEvolveDemoIssue(id?: string) { const s = readSelfEvolveDemoState(); return !!s?.active && !!id && s.issueId === id; }
export function isSelfEvolveDemoSession(id?: string) { const s = readSelfEvolveDemoState(); return !!s?.active && !!id && s.sessionId === id; }
