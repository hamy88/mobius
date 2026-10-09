/**
 * easy-jsonl-model.ts — stub 让 EasyJsonlView.tsx 能编译。真实版应从 GitLab 同步过来。
 */
import type { AnyEntry } from '../viewer/types';
export type EasyActivityKind = 'user' | 'assistant' | 'tool' | 'status' | 'explore' | 'command' | 'file-change' | 'plan' | 'progress' | 'error' | 'image';
export type EasyActivity = {
  id: string;
  kind: EasyActivityKind;
  text: string;
  entries: AnyEntry[];
  title?: string;
  summary?: string;
  details?: any[];
  imageUrls?: string[];
  state?: string;
  defaultExpanded?: boolean;
  [k: string]: any;
};
export function buildEasyJsonlRounds(_entries: AnyEntry[]): any[] { return []; }
