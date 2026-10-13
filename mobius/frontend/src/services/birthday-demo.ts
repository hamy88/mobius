export const BIRTHDAY_DEMO_TOUR_EVENT = 'imac:birthday-demo-tour:start';
export const BIRTHDAY_DEMO_STATE_KEY = 'imac-demo:birthday';

export type BirthdayDemoState = {
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

export const BIRTHDAY_DEMO_DEFAULTS = {
  projectName: '生日祝福案例',
  projectDescription: '庆祝莫比乌斯 0.3.0 生日，生成一份带图、带歌的生日祝福网页。',
  projectRelPath: '/imac-demo/birthday-greeting',
  issueTitle: '为莫比乌斯 0.3.0 做一份生日祝福网页',
  issueDescription: '请基于 Three.js 与一段简单的 WebAudio 编排一份生日祝福网页，配色温暖，最终以单文件 HTML 形式输出，方便预览。',
  sessionName: '生成 0.3.0 生日祝福网页',
  sessionDescription: '完成一份独立的生日祝福网页，使用 Three.js 渲染光点流动，使用 WebAudio 播放生日歌旋律，并把代码单文件化。',
} satisfies Omit<BirthdayDemoState, 'active' | 'startedAt' | 'completedAt' | 'projectId' | 'issueId' | 'sessionId'>;

export function createBirthdayDemoState(): BirthdayDemoState {
  return { active: true, startedAt: Date.now(), ...BIRTHDAY_DEMO_DEFAULTS };
}

function normalizeBirthdayDemoState(value: unknown): BirthdayDemoState | null {
  if (!value || typeof value !== 'object') return null;
  return { ...BIRTHDAY_DEMO_DEFAULTS, ...(value as Partial<BirthdayDemoState>) };
}

export function readBirthdayDemoState(): BirthdayDemoState | null {
  try {
    const raw = sessionStorage.getItem(BIRTHDAY_DEMO_STATE_KEY);
    if (!raw) return null;
    return normalizeBirthdayDemoState(JSON.parse(raw));
  } catch { return null; }
}

export function writeBirthdayDemoState(state: BirthdayDemoState) {
  try { sessionStorage.setItem(BIRTHDAY_DEMO_STATE_KEY, JSON.stringify(state)); } catch {}
}

export function patchBirthdayDemoState(patch: Partial<BirthdayDemoState>) {
  const current = readBirthdayDemoState() || createBirthdayDemoState();
  writeBirthdayDemoState({ ...current, ...patch });
}

export function completeBirthdayDemoState() {
  patchBirthdayDemoState({ active: false, completedAt: Date.now() });
}

export function isBirthdayDemoProject(projectId?: string) {
  const s = readBirthdayDemoState();
  return !!s?.active && !!projectId && s.projectId === projectId;
}
export function isBirthdayDemoIssue(issueId?: string) {
  const s = readBirthdayDemoState();
  return !!s?.active && !!issueId && s.issueId === issueId;
}
export function isBirthdayDemoSession(sessionId?: string) {
  const s = readBirthdayDemoState();
  return !!s?.active && !!sessionId && s.sessionId === sessionId;
}
