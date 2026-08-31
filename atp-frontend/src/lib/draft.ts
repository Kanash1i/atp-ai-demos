import type { CaseStep, DraftDocument, DraftView, Priority } from './types';

/** 步骤表用得到的枚举。取值以 00-SHARED-CONTEXT.md §1.3 和库里的实际数据为准 */
export const ACTIONS = [
  'OPEN_URL', 'CLICK', 'INPUT', 'SELECT',
  'ASSERT_TEXT', 'ASSERT_VISIBLE', 'ASSERT_NOT_EXIST',
  'WAIT_FOR', 'SCROLL_TO', 'SWITCH_FRAME', 'SWITCH_WINDOW', 'UPLOAD',
  // 规范禁止（STD-004），只在历史案例里存在。列出来是为了能读懂老案例，新写会被校验器 ERROR 拦下
  'SLEEP',
] as const;

/** 库里存量全是 XPATH，规范（STD-003）推荐 data-testid，也就是 CSS */
export const LOCATOR_TYPES = ['XPATH', 'CSS'] as const;
export const WAIT_STRATEGIES = ['NONE', 'PRESENCE', 'VISIBLE', 'CLICKABLE'] as const;
export const ON_FAILURES = ['ABORT', 'CONTINUE'] as const;
export const PRIORITIES: Priority[] = ['P0', 'P1', 'P2', 'P3'];

export function emptyStep(seq: number): CaseStep {
  return {
    seq,
    action: 'CLICK',
    locator_type: 'CSS',
    locator_value: null,
    input_data: null,
    expected: null,
    // STD-005：CLICK 必须 CLICKABLE。规则能定的先填好，别让人去背规范
    wait_strategy: 'CLICKABLE',
    wait_timeout_sec: 10,
    on_failure: 'ABORT',
    description: null,
  };
}

/**
 * 规则能自动定的就自动填（STD-005 / STD-006）。
 * 校验器本来也会拦，但等保存之后再报错，不如换 action 的当下就填对。
 */
export function waitStrategyFor(action: string, current: string | null): string | null {
  if (action === 'CLICK') return 'CLICKABLE';
  if (action.startsWith('ASSERT')) return 'VISIBLE';
  if (action === 'OPEN_URL') return 'NONE';
  return current;
}

/**
 * 解析 draftJson。
 *
 * ⚠️ 形状随 status 变：AI_DRAFT 是对象 `{title, steps}`，DRAFT 是**纯步骤数组**。
 * 不按 status 分支的话，提交成功那一刻页面就会白屏 —— 因为 `.steps` 变成了 undefined。
 */
export function parseDraft(view: DraftView): DraftDocument {
  const base: DraftDocument = {
    case_code: null, title: null, module_id: null,
    priority: null, author: null, precondition: null, steps: [],
  };

  let raw: unknown;
  try {
    raw = JSON.parse(view.draftJson);
  } catch {
    return base;
  }

  if (Array.isArray(raw)) {
    // 已提交：只剩步骤数组，表头字段已经投影进 tc_case 的正式列了
    return { ...base, steps: raw as CaseStep[] };
  }

  const obj = (raw ?? {}) as Partial<DraftDocument>;
  return {
    ...base,
    ...obj,
    steps: Array.isArray(obj.steps) ? obj.steps : [],
  };
}

/** 组回 draftJson 字符串。表头一律 snake_case —— 后端按这几个键投影 */
export function serializeDraft(doc: DraftDocument): string {
  return JSON.stringify({
    case_code: doc.case_code || null,
    title: doc.title || null,
    module_id: doc.module_id || null,
    priority: doc.priority || null,
    author: doc.author || null,
    precondition: doc.precondition || null,
    steps: doc.steps.map((s, i) => ({ ...s, seq: i + 1 })),
  });
}

