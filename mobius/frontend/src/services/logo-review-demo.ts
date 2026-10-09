export const LOGO_REVIEW_DEMO_TOUR_EVENT = 'imac:logo-review-demo-tour:start';
export const LOGO_REVIEW_DEMO_STATE_KEY = 'imac-demo:logo-review';

export const LOGO_REVIEW_PROJECT_ID = 'logo-review-demo';
export const LOGO_REVIEW_PROJECT_NAME = '莫比乌斯光点标志空间案例';
export const LOGO_REVIEW_ISSUE_TITLE = '设计莫比乌斯光点标志空间';
export const LOGO_REVIEW_SESSION_NAME = '迭代 Three.js 光点标志空间';

export type LogoReviewDemoState = {
  active?: boolean;
  startedAt?: number;
  completedAt?: number;
  sessionCompletedAt?: number;
  projectId: string;
  projectName: string;
  projectDescription?: string;
  projectRelPath?: string;
  issueTitle: string;
  issueDescription?: string;
  sessionName: string;
  sessionDescription?: string;
  cleanupProjectId?: string;
  cleanupProjectName?: string;
  cleanupProjectRelPath?: string;
  issueId?: string;
  sessionId?: string;
};

export const LOGO_REVIEW_DEMO_DEFAULTS = {
  projectId: LOGO_REVIEW_PROJECT_ID,
  projectName: LOGO_REVIEW_PROJECT_NAME,
  issueTitle: LOGO_REVIEW_ISSUE_TITLE,
  sessionName: LOGO_REVIEW_SESSION_NAME,
} satisfies Pick<LogoReviewDemoState, 'projectId' | 'projectName' | 'issueTitle' | 'sessionName'>;

export function createLogoReviewDemoState(): LogoReviewDemoState {
  return { active: true, startedAt: Date.now(), ...LOGO_REVIEW_DEMO_DEFAULTS };
}
function normalize(v: unknown): LogoReviewDemoState | null {
  if (!v || typeof v !== 'object') return null;
  return { active: true, ...LOGO_REVIEW_DEMO_DEFAULTS, ...(v as Partial<LogoReviewDemoState>) };
}
export function readLogoReviewDemoState(): LogoReviewDemoState | null {
  try { const raw = sessionStorage.getItem(LOGO_REVIEW_DEMO_STATE_KEY); if (!raw) return null; return normalize(JSON.parse(raw)); } catch { return null; }
}
export function writeLogoReviewDemoState(s: LogoReviewDemoState) { try { sessionStorage.setItem(LOGO_REVIEW_DEMO_STATE_KEY, JSON.stringify(s)); } catch {} }
export function patchLogoReviewDemoState(p: Partial<LogoReviewDemoState>) {
  writeLogoReviewDemoState({ ...(readLogoReviewDemoState() || createLogoReviewDemoState()), ...p });
}
export function completeLogoReviewDemoState() { patchLogoReviewDemoState({ active: false, completedAt: Date.now() }); }
export function isLogoReviewDemoCleanupProject(id?: string) { const s = readLogoReviewDemoState(); return !!s?.active && !!id && s.cleanupProjectId === id; }
export function isLogoReviewDemoSession(id?: string) { const s = readLogoReviewDemoState(); return !!s?.active && !!id && s.sessionId === id; }
