export const PROJECT_IMPORT_DEMO_TOUR_EVENT = 'imac:project-import-demo-tour:start';
export const PROJECT_IMPORT_DEMO_STATE_KEY = 'imac-demo:project-import';

export type ProjectImportDemoState = {
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
  gitUrl: string;
  uploadExampleName: string;
  uploadFileExamples: string[];
  uploadSampleDirRelPath: string;
  uploadSampleZipRelPath: string;
  uploadSampleDownloadedAt?: number;
  uploadSampleUploadedAt?: number;
};

export const PROJECT_IMPORT_DEMO_DEFAULTS = {
  projectName: '待办事项项目导入案例',
  projectDescription: '一个用于学习导入已有代码的演示项目：待办事项示例体量小、入口清楚。',
  projectRelPath: '/imac-demo/todomvc-import',
  issueTitle: '导入待办事项示例项目',
  gitUrl: 'https://github.com/tastejs/todomvc.git',
  uploadExampleName: '待办事项上传样例',
  uploadFileExamples: ['index.html', 'package.json', 'src/app.js', 'src/styles.css'],
  uploadSampleDirRelPath: 'upload-samples/vanilla-todomvc',
  uploadSampleZipRelPath: 'upload-samples/vanilla-todomvc-upload-sample.zip',
  issueDescription: '请把 https://github.com/tastejs/todomvc.git 浅克隆到当前项目目录，并指出入口文件。',
  sessionName: '下载并整理待办事项示例仓库',
  sessionDescription: '从公开仓库下载 TodoMVC 示例到项目目录, 列出最关键的几个文件。',
} satisfies Omit<ProjectImportDemoState, 'active' | 'startedAt' | 'completedAt' | 'uploadSampleDownloadedAt' | 'uploadSampleUploadedAt' | 'projectId' | 'issueId' | 'sessionId'>;

export function createProjectImportDemoState(): ProjectImportDemoState {
  return { active: true, startedAt: Date.now(), ...PROJECT_IMPORT_DEMO_DEFAULTS };
}

function normalize(v: unknown): ProjectImportDemoState | null {
  if (!v || typeof v !== 'object') return null;
  return { ...PROJECT_IMPORT_DEMO_DEFAULTS, ...(v as Partial<ProjectImportDemoState>) };
}

export function readProjectImportDemoState(): ProjectImportDemoState | null {
  try { const raw = sessionStorage.getItem(PROJECT_IMPORT_DEMO_STATE_KEY); if (!raw) return null; return normalize(JSON.parse(raw)); } catch { return null; }
}
export function writeProjectImportDemoState(s: ProjectImportDemoState) {
  try { sessionStorage.setItem(PROJECT_IMPORT_DEMO_STATE_KEY, JSON.stringify(s)); } catch {}
}
export function patchProjectImportDemoState(p: Partial<ProjectImportDemoState>) {
  writeProjectImportDemoState({ ...(readProjectImportDemoState() || createProjectImportDemoState()), ...p });
}
export function completeProjectImportDemoState() { patchProjectImportDemoState({ active: false, completedAt: Date.now() }); }
export function isProjectImportDemoProject(id?: string) { const s = readProjectImportDemoState(); return !!s?.active && !!id && s.projectId === id; }
export function isProjectImportDemoIssue(id?: string) { const s = readProjectImportDemoState(); return !!s?.active && !!id && s.issueId === id; }
export function isProjectImportDemoSession(id?: string) { const s = readProjectImportDemoState(); return !!s?.active && !!id && s.sessionId === id; }
