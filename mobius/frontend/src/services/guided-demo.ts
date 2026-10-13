/**
 * guided-demo.ts — guided demo 状态与同代各种生日/项目导入等 demo 的聚合出口 (生产: 真正的实装从 GitLab 同步过来)。
 *
 * merge 时 chat.tsx/modals.tsx 调用了本服务的函数, 但实现文件没有同步进来;
 * 提供 read/write/patch 接口与 ActiveGuidedDemo 形状, 把每个 demo 委托给对应的子模块。
 *
 * 教学 demo 识别永远开启 (active=true), 仅当对应 demo state 在 sessionStorage 中存在时,
 * 才在 UI 上识别为教学 session。真实版同步过来时把整个文件覆盖即可。
 */

import type { BirthdayDemoState } from './birthday-demo';
import type { ProjectImportDemoState } from './project-import-demo';
import type { ContextSetupDemoState } from './context-setup-demo';
import type { SelfEvolveDemoState } from './self-evolve-demo';
import type { ExtensionDemoState } from './extension-demo';
import type { LogoReviewDemoState } from './logo-review-demo';

export type GuidedDemoKind = 'birthday' | 'project-import' | 'context-setup' | 'self-evolve' | 'extension' | 'logo-review';
export type GuidedDemoState = BirthdayDemoState | ProjectImportDemoState | ContextSetupDemoState | SelfEvolveDemoState | ExtensionDemoState | LogoReviewDemoState;
export type ActiveGuidedDemo = { kind: GuidedDemoKind; state: GuidedDemoState };

import {
  readBirthdayDemoState,
  patchBirthdayDemoState,
  completeBirthdayDemoState,
  isBirthdayDemoIssue,
  isBirthdayDemoProject,
  isBirthdayDemoSession,
} from './birthday-demo';
import {
  readProjectImportDemoState,
  patchProjectImportDemoState,
  completeProjectImportDemoState,
  isProjectImportDemoIssue,
  isProjectImportDemoProject,
  isProjectImportDemoSession,
} from './project-import-demo';
import {
  readContextSetupDemoState,
  patchContextSetupDemoState,
  completeContextSetupDemoState,
  isContextSetupDemoIssue,
  isContextSetupDemoProject,
  isContextSetupDemoSession,
} from './context-setup-demo';
import {
  readSelfEvolveDemoState,
  patchSelfEvolveDemoState,
  completeSelfEvolveDemoState,
  isSelfEvolveDemoIssue,
  isSelfEvolveDemoProject,
  isSelfEvolveDemoSession,
} from './self-evolve-demo';
import {
  readExtensionDemoState,
  patchExtensionDemoState,
  completeExtensionDemoState,
  isExtensionDemoIssue,
  isExtensionDemoProject,
  isExtensionDemoSession,
} from './extension-demo';
import {
  readLogoReviewDemoState,
  patchLogoReviewDemoState,
  completeLogoReviewDemoState,
  isLogoReviewDemoCleanupProject,
} from './logo-review-demo';

export function readActiveGuidedDemo(preferred?: GuidedDemoKind): ActiveGuidedDemo | null {
  const brand = readBirthdayDemoState();
  const pi = readProjectImportDemoState();
  const cs = readContextSetupDemoState();
  const se = readSelfEvolveDemoState();
  const ex = readExtensionDemoState();
  const lr = readLogoReviewDemoState();

  if (preferred === 'birthday' && brand?.active) return { kind: 'birthday', state: brand };
  if (preferred === 'project-import' && pi?.active) return { kind: 'project-import', state: pi };
  if (preferred === 'context-setup' && cs?.active) return { kind: 'context-setup', state: cs };
  if (preferred === 'self-evolve' && se?.active) return { kind: 'self-evolve', state: se };
  if (preferred === 'extension' && ex?.active) return { kind: 'extension', state: ex };
  if (preferred === 'logo-review' && lr?.active) return { kind: 'logo-review', state: lr };
  if (lr?.active) return { kind: 'logo-review', state: lr };
  if (se?.active) return { kind: 'self-evolve', state: se };
  if (cs?.active) return { kind: 'context-setup', state: cs };
  if (ex?.active) return { kind: 'extension', state: ex };
  if (pi?.active) return { kind: 'project-import', state: pi };
  if (brand?.active) return { kind: 'birthday', state: brand };
  return null;
}

export function patchGuidedDemoState(kind: GuidedDemoKind, patch: Partial<GuidedDemoState>) {
  if (kind === 'birthday') patchBirthdayDemoState(patch as Partial<BirthdayDemoState>);
  else if (kind === 'project-import') patchProjectImportDemoState(patch as Partial<ProjectImportDemoState>);
  else if (kind === 'context-setup') patchContextSetupDemoState(patch as Partial<ContextSetupDemoState>);
  else if (kind === 'self-evolve') patchSelfEvolveDemoState(patch as Partial<SelfEvolveDemoState>);
  else if (kind === 'extension') patchExtensionDemoState(patch as Partial<ExtensionDemoState>);
  else patchLogoReviewDemoState(patch as Partial<LogoReviewDemoState>);
}

export function isGuidedDemoProject(projectId?: string) {
  return isBirthdayDemoProject(projectId) || isProjectImportDemoProject(projectId) || isContextSetupDemoProject(projectId) || isSelfEvolveDemoProject(projectId) || isExtensionDemoProject(projectId);
}
export function isGuidedDemoIssue(issueId?: string) {
  return isBirthdayDemoIssue(issueId) || isProjectImportDemoIssue(issueId) || isContextSetupDemoIssue(issueId) || isSelfEvolveDemoIssue(issueId) || isExtensionDemoIssue(issueId);
}
export function isGuidedDemoSession(sessionId?: string) {
  return isBirthdayDemoSession(sessionId) || isProjectImportDemoSession(sessionId) || isContextSetupDemoSession(sessionId) || isSelfEvolveDemoSession(sessionId) || isExtensionDemoSession(sessionId);
}
export function completeGuidedDemoStateForProject(projectId?: string) {
  if (isBirthdayDemoProject(projectId)) completeBirthdayDemoState();
  else if (isProjectImportDemoProject(projectId)) completeProjectImportDemoState();
  else if (isContextSetupDemoProject(projectId)) completeContextSetupDemoState();
  else if (isSelfEvolveDemoProject(projectId)) completeSelfEvolveDemoState();
  else if (isExtensionDemoProject(projectId)) completeExtensionDemoState();
  else if (isLogoReviewDemoCleanupProject(projectId)) completeLogoReviewDemoState();
}
export function patchGuidedDemoSessionCompleted(sessionId?: string) {
  if (isBirthdayDemoSession(sessionId)) patchBirthdayDemoState({ sessionCompletedAt: Date.now() } as Partial<BirthdayDemoState>);
  else if (isProjectImportDemoSession(sessionId)) patchProjectImportDemoState({ sessionCompletedAt: Date.now() } as Partial<ProjectImportDemoState>);
  else if (isContextSetupDemoSession(sessionId)) patchContextSetupDemoState({ sessionCompletedAt: Date.now() } as Partial<ContextSetupDemoState>);
  else if (isSelfEvolveDemoSession(sessionId)) patchSelfEvolveDemoState({ sessionCompletedAt: Date.now() } as Partial<SelfEvolveDemoState>);
  else if (isExtensionDemoSession(sessionId)) patchExtensionDemoState({ sessionCompletedAt: Date.now() } as Partial<ExtensionDemoState>);
}
