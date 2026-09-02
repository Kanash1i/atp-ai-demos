import type {
  Approval, ApprovalStats, AuthUser, CaseDetail, ChatConversation, ChatEvent, ChatMessage,
  CreateDraftRequest, Decision,
  DispatchRequest, DispatchResponse, DraftView, ExecNode, ExecStats, LoginResponse,
  ModuleDictEntry, ProblemDetail, Project, RecentRun, RunningBatch, TaskDetail, TreeModule,
  ValidationFinding, ValidationResult,
} from './types';
import { clearSession, getToken, setSession } from './auth';

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

/** 有 token 就带上。和机器 token 同一个头 —— Sa-Token 那套配置是全局的 */
function authHeader(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function request<T>(path: string, init?: RequestInit): Promise<T | NoContent> {
  const res = await fetch(`${BASE}${path}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...authHeader(),
      ...init?.headers,
    },
  });

  /*
   * 「成功但没有 body」不止 204 一种形状。
   *
   * 踩过一次：后端的删除接口返回 200 + 零字节 body，这里只对 204 短路，
   * 于是走到下面的 res.json() 去解析空字符串，抛 SyntaxError ——
   * 而链条上每一环看着都正常：库里删掉了、HTTP 200、异常被 catch 吞掉，
   * 用户唯一看得到的是「列表不动」。没有任何一处报错到他面前。
   *
   * 后端那一处已经改成 204 了，但这个形状还会从别处来（反代、网关、
   * 某些框架的默认行为）。所以按**有没有内容**判断，而不是按状态码。
   */
  if (res.status === 204 || res.headers.get('content-length') === '0') return NO_CONTENT;

  if (!res.ok) {
    const problem = await parseProblem(res);

    /*
     * 401 = 没带 token / token 无效或过期 → 清掉登录态，路由守卫会把人送回登录页。
     * 403 = token 有效但缺 scope → **不清、不跳**。重新登录也拿不到那个权限，
     *       把人踢回登录页只会让他再登一次、再撞一次同样的 403。
     *
     * 登录接口自己的 401（用户名或密码不正确）不走这里 —— 那时本来就没有登录态。
     */
    if (res.status === 401 && !path.startsWith('/api/auth/')) {
      clearSession();
    }

    throw new ApiError(res.status, problem, `${res.status} ${res.statusText}`);
  }

  // content-length 可能因为分块传输而缺失，所以还要兜一次：拿到文本再判空
  const text = await res.text();
  if (!text) return NO_CONTENT;
  return JSON.parse(text) as T;
}

/** 大部分接口不会返回 204，用这个拿到确定的 T */
async function get<T>(path: string): Promise<T> {
  const out = await request<T>(path);
  if (out === NO_CONTENT) throw new ApiError(204, null, 'unexpected 204');
  return out;
}

async function post<T>(path: string, body: unknown, method: 'POST' | 'PUT' = 'POST'): Promise<T> {
  const out = await request<T>(path, { method, body: JSON.stringify(body) });
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

  /** 平铺不分页（当前 12 条）。新建案例的模块下拉框用它 */
  modules: () => get<ModuleDictEntry[]>('/api/modules'),

  /**
   * 按 STD-007 取该模块的下一个 case_code。
   *
   * 后端与 agent 的 `next_case_code` 工具调的是同一份实现 —— 两边不会算出不同的号。
   * ⚠️ 并发下两个人仍可能拿到同一个号，后提交的会被 `uk_case_code` 拦下（见 commit 的重试）。
   */
  nextCaseCode: (moduleId: string) =>
    get<{ caseCode: string }>(`/api/modules/${moduleId}/next-case-code`),

  /** 读回当前草稿。刷新页面、或撞到 409 之后重新载入时用 */
  draft: (caseId: string) => get<DraftView>(`/api/cases/${caseId}/draft`),

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

/**
 * 派发一批执行。接口立刻返回，不等执行完 —— 进度靠轮询 /running。
 *
 * ⚠️ 没有节点在线时任务会一直挂在队列里，这是故意的（任务不丢，节点起来接着跑）。
 * 那时 /running 返回 doneCount: 0 的批次，而 /nodes 里没有 online: true 的节点 ——
 * **两个信号合起来**才说明问题出在「没有执行节点」，任何单独一个都不够。
 */
export async function dispatchRun(body: DispatchRequest): Promise<DispatchResponse> {
  const out = await request<DispatchResponse>('/api/executions/dispatch', {
    method: 'POST',
    body: JSON.stringify(body),
  });
  if (out === NO_CONTENT) throw new ApiError(204, null, 'unexpected 204');
  return out;
}

/* ---------- 登录 ---------- */

/** 登录成功即写入 session；后续所有请求自动带 Authorization */
export async function login(username: string, password: string): Promise<AuthUser> {
  const out = await post<LoginResponse>('/api/auth/login', { username, password });
  setSession(out.token, out.user);
  return out.user;
}

/** 凭据不对（用户名或密码错），区别于「token 过期」那种 401 */
export function isBadCredentials(err: unknown): boolean {
  return problemType(err) === 'https://atp.example/problems/bad-credentials';
}

/** token 有效但缺权限。detail 会写明缺哪个 scope */
export function isForbidden(err: unknown): boolean {
  return err instanceof ApiError && err.status === 403;
}

/* ---------- 写侧（面板⑥）---------- */

/**
 * 建草稿。`caseId` 由**调用方**生成（UUID），是幂等键 ——
 * 「点了新建但响应丢了」重试一次即可，不会建出两条空草稿。
 */
export async function createDraft(body: CreateDraftRequest): Promise<DraftView> {
  return post<DraftView>('/api/cases/draft', body);
}

/**
 * 保存草稿。`version` 是并发仲裁点，带你手上那份的版本号；
 * 中间被别人改过会 409 —— 提示「已被他人修改」并提供重新载入，**不要静默重试**，
 * 重试会覆盖别人的改动。
 */
export async function saveDraft(caseId: string, draftJson: string, version: number): Promise<DraftView> {
  return post<DraftView>(`/api/cases/${caseId}/draft`, { draftJson, version }, 'PUT');
}

/**
 * 提交落地。**只带 version，不带内容** —— 提交的是库里那份已经确认过的快照。
 * 规范校验有 ERROR 时 422，`findings` 直接渲染。
 */
export async function commitDraft(caseId: string, version: number): Promise<DraftView> {
  return post<DraftView>(`/api/cases/${caseId}/commit`, { version });
}

/**
 * RFC 7807 的 `type` —— 用它分支，**不要匹配 `detail`**。
 *
 * `detail` 是给人看的中文文案，后端会改；`type` 的前缀与 slug 是稳定契约面。
 * ⚠️ 它是标识符不是地址（RFC 7807 允许不可解引用），别去请求它。
 */
export const PROBLEM = {
  /** 内容在你确认之后被别人改过 —— 提示「已被他人修改」并给「重新载入」 */
  versionConflict: 'https://atp.example/problems/version-conflict',
  /**
   * 案例已经提交过了 —— **没有重试的意义**，别给「重新载入再试」：
   * 重新载入之后状态还是已提交的，用户会在一个永远失败的循环里打转
   */
  stateConflict: 'https://atp.example/problems/state-conflict',
  /** 规范校验未通过 → 看 findings / violatedCodes */
  validationFailed: 'https://atp.example/problems/validation-failed',
  /** 表头必填缺失 → 看 missingFields */
  headerIncomplete: 'https://atp.example/problems/header-incomplete',
} as const;

export function problemType(err: unknown): string | null {
  return err instanceof ApiError ? (err.problem?.type ?? null) : null;
}

/**
 * 两种 409 的判定。
 *
 * ⚠️ 带**降级**：`type` 是后端 PR #49 才加的，在还没部署它的后端上 409 没有 type。
 * 那种情况按 `version-conflict` 处理 —— 也就是加 type 之前的老行为（给「重新载入」）。
 * 宁可在老后端上对 state-conflict 多给一个没用的重试按钮，也不能两种都不认、
 * 保存失败却一句提示都不给。
 */
export function isVersionConflict(e: unknown): boolean {
  const type = problemType(e);
  if (type) return type === PROBLEM.versionConflict;
  return e instanceof ApiError && e.isConflict;
}

export function isStateConflict(e: unknown): boolean {
  return problemType(e) === PROBLEM.stateConflict;
}

/** 422 的 ProblemDetail 上挂着 findings，取出来直接喂给校验面板 */
export function findingsOf(err: unknown): ValidationFinding[] {
  if (!(err instanceof ApiError) || !err.problem) return [];
  const p = err.problem as ProblemDetail & { findings?: ValidationFinding[] };
  return Array.isArray(p.findings) ? p.findings : [];
}

/**
 * 缺必填字段时 422 的 `missingFields` —— 六个 snake_case 键里少了哪几个。
 * 它们是 draftJson 顶层的键，提交时被投影进案例表。
 */
export function missingFieldsOf(err: unknown): string[] {
  if (!(err instanceof ApiError) || !err.problem) return [];
  const p = err.problem as ProblemDetail & { missingFields?: string[] };
  return Array.isArray(p.missingFields) ? p.missingFields : [];
}

/**
 * case_code 撞号了吗。
 *
 * 取号接口不是原子的：两个人同时新建同一模块的案例会拿到同一个号，
 * 后提交的被 `uk_case_code` 唯一约束拦下。
 *
 * ⚠️ 这一个还**没有** problem type：后端仍报 500 `DuplicateKeyException`
 * （调用方的错报成 5xx），所以只能靠匹配约束名。约束名一改这里就静默失效 ——
 * 后端给了 type 之后换成按 type 判，这个兜底可以删。
 */
export function isDuplicateCaseCode(err: unknown): boolean {
  if (!(err instanceof ApiError)) return false;
  const p = err.problem;
  if (p?.type && p.type.endsWith('/duplicate-case-code')) return true;
  return (p?.detail ?? '').includes('uk_case_code');
}

/* ---------- 智能 Agent 助手（面板⑤）---------- */

/**
 * 一轮对话。**用 fetch 而不是 EventSource** —— EventSource 只能 GET，
 * 而这个接口要 POST 一个 body。
 *
 * ⚠️ 一轮可能长达 1~3 分钟（agent 要查规范、用浏览器探查页面、写草稿、校验、提交、跑自验）。
 * 后端 SseEmitter 超时 300 秒，**这里不设更短的超时** —— 只认外部传进来的 signal，
 * 那是用户主动取消或组件卸载。
 */
export async function streamChat(
  conversationId: string,
  message: string,
  onEvent: (e: ChatEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const res = await fetch(`${BASE}/api/chat/${conversationId}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', ...authHeader() },
    body: JSON.stringify({ message }),
    signal,
  });

  if (!res.ok || !res.body) {
    throw new ApiError(res.status, await parseProblem(res), `${res.status} ${res.statusText}`);
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });

    // SSE 以空行分隔一个事件块。\r\n 也要认，别假设服务端只发 \n
    let sep: number;
    while ((sep = buffer.search(/\r?\n\r?\n/)) !== -1) {
      const block = buffer.slice(0, sep);
      buffer = buffer.slice(sep).replace(/^\r?\n\r?\n/, '');

      for (const line of block.split(/\r?\n/)) {
        // 后端发的是 `data:{...}`（冒号后没有空格），但规范允许有，两种都认
        if (!line.startsWith('data:')) continue;
        const raw = line.slice(5).trimStart();
        if (!raw) continue;
        try {
          onEvent(JSON.parse(raw) as ChatEvent);
        } catch {
          /* 半个 JSON —— 只可能出现在流被掐断时，丢掉即可 */
        }
      }
    }
  }
}

/**
 * 我的会话列表。按 token 里的 userId 隔离 —— 不接受前端传 userId，
 * 会话 id 是前端生成的 UUID，靠「猜不到」保护别人的对话不成立。
 *
 * ⚠️ 实际返回的是 conversationId / title / createdAt / updatedAt，
 * **没有** messageCount，时间字段叫 updatedAt 不叫 lastMessageAt。
 */
export const listConversations = () => get<ChatConversation[]>('/api/chat/conversations');

/** 某个会话的历史消息 */
export const conversationMessages = (conversationId: string) =>
  get<ChatMessage[]>(`/api/chat/${conversationId}/messages`);

/**
 * 软删除一个会话，同时释放该会话的 agent 实例与上下文。
 *
 * ⚠️ 这是**删除**，不是「关闭面板」—— 早先它被当成 onUnmount 的清理调用，
 * 于是切一下面板旧会话就没了。现在只在用户明确点删除时调。
 */
export async function deleteConversation(conversationId: string): Promise<void> {
  await request(`/api/chat/${conversationId}`, { method: 'DELETE' });
}

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
