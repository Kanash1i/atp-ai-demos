import type {
  Approval, ApprovalStats, CaseDetail, Decision, ExecNode, ExecStats,
  ProblemDetail, Project, RecentRun, RunningBatch, TaskDetail, TreeModule, ValidationResult,
} from './types';

/**
 * dev 下走 vite 代理（同源 /api），生产同域部署也是同源。
 * 要指向别的机器就设 VITE_API_ORIGIN，不要在代码里写死。
 */
const BASE = import.meta.env.VITE_API_BASE ?? '';

/** M1 还没接 Sa-Token，当前用户由查询参数带；接上之后这个参数保留，用于演示切身份 */
export const DEMO_USER: string = import.meta.env.VITE_DEMO_USER ?? 'kaneshiro';

export class ApiError extends Error {
  readonly status: number;
  readonly problem: ProblemDetail | null;

  constructor(status: number, problem: ProblemDetail | null, fallback: string) {
    super(problem?.detail || fallback);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
  }

  /** 审批被别人抢先处理了。前端提示「刷新看看」，不要自动重试 */
  get isConflict(): boolean {
    return this.status === 409;
  }

  get isNotFound(): boolean {
    return this.status === 404;
  }
}

/** 204 用这个哨兵表示「接口在，但当前没有内容」—— 例如没有执行中的批次 */
export const NO_CONTENT = Symbol('no-content');
export type NoContent = typeof NO_CONTENT;

async function parseProblem(res: Response): Promise<ProblemDetail | null> {
  try {
    const body = await res.json();
    return typeof body === 'object' && body !== null && 'status' in body ? (body as ProblemDetail) : null;
  } catch {
    return null;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T | NoContent> {
  const res = await fetch(`${BASE}${path}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...init?.headers,
    },
  });

  if (res.status === 204) return NO_CONTENT;

  if (!res.ok) {
    throw new ApiError(res.status, await parseProblem(res), `${res.status} ${res.statusText}`);
  }

  return (await res.json()) as T;
}

/** 大部分接口不会返回 204，用这个拿到确定的 T */
async function get<T>(path: string): Promise<T> {
  const out = await request<T>(path);
  if (out === NO_CONTENT) throw new ApiError(204, null, 'unexpected 204');
  return out;
}

function withUser(path: string, user = DEMO_USER): string {
  return `${path}${path.includes('?') ? '&' : '?'}user=${encodeURIComponent(user)}`;
}

/* ---------- 案例中心 ---------- */
export const api = {
  projects: () => get<Project[]>('/api/projects'),

  /** 一次返回该项目下全部模块与案例 —— 展开/收起是纯客户端行为，不再逐个模块请求 */
  projectTree: (projectId: string) => get<TreeModule[]>(`/api/projects/${projectId}/tree`),

  caseDetail: (caseId: string) => get<CaseDetail>(`/api/cases/${caseId}`),

  caseValidation: (caseId: string) => get<ValidationResult>(`/api/cases/${caseId}/validation`),

  /* ---------- 执行状态 ---------- */
  execStats: () => get<ExecStats>('/api/executions/stats'),

  /** 没有批次在跑时后端返回 204，这里透传 NO_CONTENT，由 UI 渲染空状态 */
  execRunning: () => request<RunningBatch>('/api/executions/running'),

  execRecent: (limit = 200) => get<RecentRun[]>(`/api/executions/recent?limit=${limit}`),

  execTask: (taskId: string) => get<TaskDetail>(`/api/executions/tasks/${taskId}`),

  execNodes: () => get<ExecNode[]>('/api/executions/nodes'),

  /* ---------- 审批中心 ---------- */
  approvalStats: (user?: string) => get<ApprovalStats>(withUser('/api/approvals/stats', user)),

  approvalsPending: (assignee?: string) =>
    get<Approval[]>(assignee ? `/api/approvals/pending?assignee=${encodeURIComponent(assignee)}` : '/api/approvals/pending'),

  approvalsMine: (user?: string) => get<Approval[]>(withUser('/api/approvals/mine', user)),

  approval: (requestId: string) => get<Approval>(`/api/approvals/${requestId}`),
};

/** POST 单独写，避免上面 get<> 的语义被误用 */
export async function decideApproval(
  requestId: string,
  body: { decision: Decision; decidedBy?: string; note?: string },
): Promise<Approval> {
  const out = await request<Approval>(`/api/approvals/${requestId}/decision`, {
    method: 'POST',
    body: JSON.stringify({ decidedBy: DEMO_USER, ...body }),
  });
  if (out === NO_CONTENT) throw new ApiError(204, null, 'unexpected 204');
  return out;
}
