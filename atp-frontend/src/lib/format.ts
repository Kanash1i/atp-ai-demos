import type { CaseStatus, ExecStatus, Priority, Severity } from './types';

/** 契约里大量字段为 null（OPEN_URL 没有定位器，CLICK 没有输入数据）。null 渲染成 — 而不是空白或 "null" */
export const DASH = '—';

export function dash(v: string | number | null | undefined): string {
  if (v === null || v === undefined || v === '') return DASH;
  return String(v);
}

/** 时间已由后端转成 Asia/Tokyo 并格式化好，前端不要再做时区转换 —— 只截显示宽度 */
export function timeOnly(ts: string | null): string {
  if (!ts) return DASH;
  const parts = ts.split(' ');
  return parts.length > 1 ? parts[1] : ts;
}

export function signed(n: number | null, unit = ''): string | null {
  if (n === null || n === undefined) return null;
  const sign = n > 0 ? '+' : '';
  return `${sign}${n}${unit}`;
}

type Tone = { fg: string; bg: string };

const NEUTRAL: Tone = { fg: 'text-ink-3', bg: 'bg-line-4' };

export const severityTone: Record<Severity, Tone> = {
  ERROR: { fg: 'text-shu', bg: 'bg-shu-soft' },
  WARN: { fg: 'text-yamabuki', bg: 'bg-yamabuki-soft' },
  INFO: { fg: 'text-ai', bg: 'bg-ai-soft' },
};

export const caseStatusTone: Record<CaseStatus, Tone> = {
  ACTIVE: { fg: 'text-matsu', bg: 'bg-matsu-soft' },
  DRAFT: { fg: 'text-yamabuki', bg: 'bg-yamabuki-soft' },
  DEPRECATED: { fg: 'text-ink-3', bg: 'bg-line-4' },
  /** agent 编写中 —— demo2 / M3 的核心状态，界面上必须看得见 */
  AI_DRAFT: { fg: 'text-ai', bg: 'bg-ai-soft' },
};

export const execStatusTone: Record<ExecStatus, Tone> = {
  PASSED: { fg: 'text-matsu', bg: 'bg-matsu-soft' },
  FAILED: { fg: 'text-shu', bg: 'bg-shu-soft' },
  SKIPPED: { fg: 'text-ink-3', bg: 'bg-line-4' },
  RUNNING: { fg: 'text-ai', bg: 'bg-ai-soft' },
  PENDING: { fg: 'text-ink-3', bg: 'bg-line-4' },
  ABORTED: { fg: 'text-yamabuki', bg: 'bg-yamabuki-soft' },
};

export const priorityTone: Record<Priority, Tone> = {
  P0: { fg: 'text-shu', bg: 'bg-shu-soft' },
  P1: { fg: 'text-yamabuki', bg: 'bg-yamabuki-soft' },
  P2: { fg: 'text-ink-2', bg: 'bg-line-4' },
  P3: { fg: 'text-ink-3', bg: 'bg-line-4' },
};

export function toneOf<T extends string>(map: Record<string, Tone>, key: T | null | undefined): Tone {
  return (key && map[key]) || NEUTRAL;
}

/** 树上的状态圆点 */
export const caseStatusDot: Record<CaseStatus, string> = {
  ACTIVE: 'bg-matsu',
  DRAFT: 'bg-yamabuki',
  DEPRECATED: 'bg-ink-5',
  AI_DRAFT: 'bg-ai',
};

/** 断言类 Action 在步骤表里用朱色标出来 —— STD-008 关心的就是这类步骤 */
export function isAssertion(action: string): boolean {
  return action.startsWith('ASSERT');
}

/** 姓名 → 两字母。演示账号是日文名，取姓名各一个字 */
export function initials(name: string): string {
  const clean = name.trim();
  if (!clean) return '??';
  const parts = clean.split(/[\s·・]+/).filter(Boolean);
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
  return clean.slice(0, 2).toUpperCase();
}
