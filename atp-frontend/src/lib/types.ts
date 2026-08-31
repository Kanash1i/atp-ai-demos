/**
 * 后端契约（02-前端契约.md §三）的 TS 映射。
 *
 * 命名风格是刻意的两套：外层 camelCase，`steps[]` 内部 snake_case。
 * steps 的形状是平台 / agent / atp CLI(Go) / Playwright 执行器四方共享的
 * `tc_step.step_json`，在表示层转成 camelCase 会让 API 和库里长得不一样。
 */

export type Priority = 'P0' | 'P1' | 'P2' | 'P3';
export type CaseStatus = 'DRAFT' | 'ACTIVE' | 'DEPRECATED' | 'AI_DRAFT';
export type CaseType = 'IOS' | 'ANDROID' | 'PC_WEB';
export type Severity = 'ERROR' | 'WARN' | 'INFO';
export type ExecStatus = 'PASSED' | 'FAILED' | 'SKIPPED' | 'RUNNING' | 'PENDING' | 'ABORTED';
export type ApprovalType = 'RULE_EXCEPTION' | 'CASE_CHANGE' | 'DATASET_RELEASE';
export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'HOLD';
export type Decision = 'APPROVED' | 'REJECTED' | 'HOLD';

/* ---------- 案例中心 ---------- */

export interface Project {
  projectId: string;
  /** 本身就是日中双语串（`EC サイト / 通販フロント`），不参与 i18n */
  projectName: string;
  moduleCount?: number;
  caseCount?: number;
}

export interface TreeCase {
  caseId: string;
  caseCode: string;
  /** case_code 的后四位。⚠️ 存量有不合规编号（ATP-ADMIN-0011-V2），别假设总是 4 位数字 */
  seqNo: string;
  title: string;
  priority: Priority;
  status: CaseStatus;
}

export interface TreeModule {
  moduleId: string;
  moduleCode: string;
  moduleName: string;
  caseCount: number;
  cases: TreeCase[];
}

/** tc_step.step_json 的元素 —— 四方共享契约，保持 snake_case */
export interface CaseStep {
  step_id?: string;
  case_id?: string;
  seq: number;
  action: string;
  locator_type: string | null;
  locator_value: string | null;
  input_data: string | null;
  expected: string | null;
  wait_strategy: string | null;
  wait_timeout_sec: number | null;
  on_failure: string | null;
  description: string | null;
}

export interface ValidationFinding {
  std: string;
  severity: Severity;
  /** null = 案例级问题（如编号不合规），不指向任何步骤 */
  seq: number | null;
  /** 后端生成的整句中文判断理由，当作领域内容，不参与 i18n */
  message: string;
}

export interface ValidationResult {
  passed: boolean;
  errorCount: number;
  warnCount: number;
  infoCount: number;
  violatedCodes: string[];
  findings: ValidationFinding[];
}

export interface CaseDetail {
  caseId: string;
  caseCode: string;
  title: string;
  moduleId: string;
  moduleCode: string;
  moduleName: string;
  projectId: string;
  priority: Priority;
  status: CaseStatus;
  caseType: CaseType;
  author: string | null;
  precondition: string | null;
  updatedAt: string;
  /** 乐观锁版本号，编辑时要原样带回来 */
  version: number;
  steps: CaseStep[];
  validation: ValidationResult;
}

/* ---------- 执行状态 ---------- */

export interface ExecStats {
  todayTotal: number;
  /** 分母是 PASSED + FAILED，不含 SKIPPED */
  passRate: number;
  avgDurationSec: number;
  failedCount: number;
  failedP0Count: number;
  /** 与昨日环比，没有昨日数据时为 null */
  totalDeltaPercent: number | null;
  /** 单位秒，正负都有，可能为 null */
  avgDurationDelta: number | null;
}

export type Browser = 'CHROME' | 'FIREFOX' | 'EDGE';
export type Trigger = 'MANUAL' | 'AGENT' | 'SCHEDULED';

export interface DispatchRequest {
  projectId: string;
  /** 省略或空数组 = 跑该项目下全部案例 */
  caseIds?: string[];
  browser: Browser;
  /** 自由文本，显示在批次卡片上 */
  suiteName: string;
  trigger: Trigger;
  createdBy: string;
}

export interface DispatchResponse {
  runId: string;
  runCode: string;
  totalCount: number;
  status: string;
}

export interface RunningBatch {
  runId: string;
  runCode: string;
  projectName: string;
  suiteName: string;
  browser: string;
  triggerSource: Trigger;
  /** = passedCount + failedCount + skippedCount，进度条用它除以 totalCount */
  doneCount: number;
  totalCount: number;
  passedCount: number;
  failedCount: number;
  skippedCount: number;
  /** 此刻正在跑的条数（实时查询，不是冗余计数），并发节点多时才 > 0 */
  runningCount: number;
  elapsedSec: number;
  /**
   * ⚠️ 可能为 null —— 还没有任何任务完成时推算不出来。
   * 有值时也是按已完成任务的平均耗时外推的，所以显示成「≈ N 分钟」而不是精确值。
   */
  etaSec: number | null;
}

export interface RecentRun {
  taskId: string;
  caseCode: string;
  caseTitle: string;
  browser: string;
  nodeName: string;
  status: ExecStatus;
  /** 已格式化（48.4s / 1m 4s），SKIPPED 为 null */
  duration: string | null;
  finishedAt: string | null;
  hasVideo: boolean;
}

export interface TaskStep {
  seq: number;
  action: string;
  status: ExecStatus;
  duration: string | null;
  errorMsg?: string | null;
  screenshotUrl?: string | null;
}

export interface TaskDetail {
  taskId: string;
  runCode: string;
  caseId: string;
  caseCode: string;
  caseTitle: string;
  browser: string;
  nodeName: string;
  status: ExecStatus;
  duration: string | null;
  startedAt: string;
  finishedAt: string | null;
  failedSeq: number | null;
  errorMsg: string | null;
  /** ⚠️ /api/artifacts/** 要 M2 才实现，现在请求会 404 */
  videoUrl: string | null;
  screenshotUrl: string | null;
  steps: TaskStep[];
}

export interface ExecNode {
  nodeName: string;
  status: string;
  /** ⚠️ 在线与否看这个（心跳算出来的），不要看 status —— 节点崩了不会自己改成 OFFLINE */
  online: boolean;
  currentTaskId: string | null;
  lastHeartbeat: string;
}

/* ---------- 审批中心 ---------- */

export interface ApprovalStats {
  awaitingMe: number;
  submittedByMe: number;
  completed: number;
  overdue: number;
}

export interface RuleExceptionPayload {
  violated_std: string;
  step_seq: number;
  reason: string;
  expire_at: string;
}

export interface CaseChangePayload {
  diff_summary: string[];
  standards_check: string;
  /** 整包快照，不是变更字段 —— 待审期间案例可能又被改了，diff 由前端算 */
  before: Record<string, unknown>;
  after: Record<string, unknown>;
}

export interface DatasetReleasePayload {
  corpus_name: string;
  docs_count: number;
  index_progress: number;
  evaluated: boolean;
}

export type ApprovalPayload = RuleExceptionPayload | CaseChangePayload | DatasetReleasePayload;

export interface Approval {
  requestId: string;
  type: ApprovalType;
  targetId: string;
  title: string;
  summary: string | null;
  status: ApprovalStatus;
  submitter: string;
  submittedAt: string;
  /** 后端算好的相对时间；status !== PENDING 时为 null */
  slaRemaining: string | null;
  overdue: boolean;
  assignee: string;
  decidedBy: string | null;
  decidedAt: string | null;
  decisionNote: string | null;
  payload: ApprovalPayload;
}

/* ---------- 错误 ---------- */

/** RFC 7807 */
export interface ProblemDetail {
  type: string;
  title: string;
  status: number;
  detail: string;
  instance: string;
}

/* ============================================================
   写侧（面板⑥）—— 建草稿 → 反复保存 → 提交落地
   ============================================================ */

/**
 * `draftJson` 的**编辑期**形状（对象）。
 *
 * ⚠️ 表头字段是 **snake_case**，和 `steps[]` 里一样 —— 后端 commit 时按
 * `case_code` / `title` / `module_id` / `priority` / `author` / `precondition`
 * 这几个键把它们投影进 `tc_case` 的正式列。用 camelCase 写进去会被当成不存在，
 * 落库时撞 `ck_case_complete` 约束，报 500 而不是 4xx。
 *
 * ⚠️ `case_code` **必须由调用方给**，后端不会按 STD-007 自动生成。
 */
export interface DraftHeader {
  case_code: string | null;
  title: string | null;
  module_id: string | null;
  priority: Priority | null;
  author: string | null;
  precondition: string | null;
}

export interface DraftDocument extends DraftHeader {
  steps: CaseStep[];
}

export interface DraftView {
  caseId: string;
  /**
   * JSON 字符串，形状随 status 变：
   * - `AI_DRAFT`（编辑期）→ 对象 `{title, steps: [...]}`
   * - `DRAFT`（已提交）  → **纯步骤数组** `[{seq:1,…}]`（老执行器读数组）
   * 用 `parseDraft()` 解析，不要直接 JSON.parse 后当对象使。
   */
  draftJson: string;
  /** 乐观锁。下次 save/commit 要原样带回来 */
  version: number;
  status: CaseStatus;
  caseType: CaseType;
  platformStatus: string;
  validation: ValidationResult;
}

export interface CreateDraftRequest {
  /** ⚠️ 由前端生成的 UUID，不是后端返回的。它是幂等键：同一个 id 重复调用不会建出第二条 */
  caseId: string;
  title: string;
  caseType: CaseType;
  createdBy: string;
}

export interface ModuleDictEntry {
  projectId: string;
  projectCode: string;
  projectName: string;
  moduleId: string;
  moduleCode: string;
  moduleName: string;
}

/** 规范校验被拒时后端在 ProblemDetail 上额外挂的两个字段（422） */
export interface ValidationProblem extends ProblemDetail {
  violatedCodes?: string[];
  findings?: ValidationFinding[];
}

/* ============================================================
   智能 Agent 助手（面板⑤）—— SSE
   ============================================================ */

export type ChatEventType = 'route' | 'thinking' | 'message' | 'done' | 'error';

export interface ChatEvent {
  type: ChatEventType;
  /** 路由到的助手，会变：CaseAuthoringAgent / KnowledgeAgent / router */
  agent: string;
  /**
   * ⚠️ `thinking` 是**增量**（一次几个字，一轮 150~800 个），要按顺序拼接；
   * `message` 是**完整**内容，直接替换。把 message 也当增量拼会显示两遍。
   */
  content: string;
}
