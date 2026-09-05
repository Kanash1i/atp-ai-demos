import { useSyncExternalStore } from 'react';
import { uuidv4 } from './uuid';

/**
 * 对话状态放在 React 之外。
 *
 * 为什么不放组件里：切到别的面板时 AgentPanel 会卸载，state 跟着没。
 * 用户的原话是「我一旦从智能体的 navi 切到执行去看一眼，我的旧会话直接被清空了」。
 *
 * ⚠️ 但那次真正的元凶不是 state 丢失，是卸载时调了
 * `DELETE /api/chat/{id}` —— 那个接口是**软删除**，不是「关闭面板」。
 * 切一下面板，服务端那条会话就真没了。现在只在用户明确点删除时调。
 *
 * 这个 store 扛的是「切面板」；扛不住刷新和换设备 —— 那两件靠
 * `/api/chat/conversations` 的历史列表翻回来。两件事都要做，缺一件都不够。
 */

/**
 * 处理过程的一条。
 *
 * thinking 是增量文本，连续的要合成一条，不然一轮 800 个 token 就是 800 个 <pre>。
 * tool 是结构化的：`▸ name` 是发起、`✓ name "…"` 是结果。
 *
 * ⚠️ 发起（▸）实际是从 **thinking 通道**下来的，不是 tool 事件 ——
 * 后端只把结果推成 tool。这里按内容识别，把它提成 call，
 * 才能在面板里配成「发起 → 结果」一对。
 */
export type TraceItem =
  | { kind: 'thinking'; text: string }
  | { kind: 'tool'; phase: 'call' | 'result'; name: string; detail: string };

/** agent 自己列的任务清单。**是一份会变的状态，不是一条消息** —— 每次覆盖，不追加 */
export type SubtaskState = 'TODO' | 'IN_PROGRESS' | 'DONE' | 'ABANDONED';

export interface PlanSubtask {
  name: string;
  state: SubtaskState;
  /** 只有 DONE 才有 */
  outcome: string | null;
}

export interface AgentPlan {
  name: string;
  subtasks: PlanSubtask[];
}

export interface Turn {
  id: string;
  question: string;
  /** 路由结论，形如「案例编写 · L2 0.98」。末尾是置信度 */
  route: string | null;
  agent: string | null;
  /** 思考 + 工具调用，按到达顺序。thinking 增量合并进最后一条 */
  trace: TraceItem[];
  /** 最后一份任务清单。后端做了指纹去重，来几条渲染几次即可 */
  plan: AgentPlan | null;
  /** message 是完整内容，直接替换 —— 当增量拼会显示两遍 */
  answer: string;
  error: string | null;
  streaming: boolean;
  /** 已按下停止、interrupt 接口已发出，但流还没收尾 */
  stopping?: boolean;
  /** 收到过 interrupted 事件 */
  interrupted?: boolean;
  /**
   * tool-aborted 带下来的工具名（「、」分隔），可能是空串。
   *
   * ⚠️ 语义是「这些工具已经执行了，结果被丢弃」，**不是「已撤销」**。
   * 打断只保证不再走下一步，已经发出去的写入不回滚。措辞不能让人以为数据回到了打断前。
   */
  abortedTools?: string | null;
  /** 历史消息没有 thinking 流，用它区分「刚跑的」和「翻回来的」 */
  fromHistory?: boolean;
}

export interface ChatState {
  conversationId: string;
  turns: Turn[];
}

let state: ChatState = { conversationId: uuidv4(), turns: [] };
const listeners = new Set<() => void>();

function set(next: ChatState): void {
  state = next;
  listeners.forEach((fn) => fn());
}

export function subscribe(fn: () => void): () => void {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

export const snapshot = (): ChatState => state;

export function useChat(): ChatState {
  return useSyncExternalStore(subscribe, snapshot, snapshot);
}

/** 开一个新会话。换 id 等于换上下文 —— 后端按 id 隔离多轮记忆 */
export function newConversation(): string {
  const id = uuidv4();
  set({ conversationId: id, turns: [] });
  return id;
}

/** 切到某个历史会话。turns 由调用方从接口拉回来后填进来 */
export function openConversation(conversationId: string, turns: Turn[]): void {
  set({ conversationId, turns });
}

export function addTurn(turn: Turn): void {
  set({ ...state, turns: [...state.turns, turn] });
}

export function patchTurn(id: string, fn: (t: Turn) => Turn): void {
  set({ ...state, turns: state.turns.map((t) => (t.id === id ? fn(t) : t)) });
}

/** 删掉的正好是当前会话时，顺手开一个新的，免得界面停在一个已经不存在的 id 上 */
export function forgetIfCurrent(conversationId: string): void {
  if (state.conversationId === conversationId) newConversation();
}

/** 思考总字数 —— 面板右上角那个计数 */
export function thinkingLength(trace: TraceItem[]): number {
  return trace.reduce((n, x) => (x.kind === 'thinking' ? n + x.text.length : n), 0);
}

/** 增量思考并进最后一条；前一条不是 thinking 就新起一条 */
export function appendThinking(trace: TraceItem[], text: string): TraceItem[] {
  const last = trace[trace.length - 1];
  if (last?.kind === 'thinking') {
    return [...trace.slice(0, -1), { kind: 'thinking', text: last.text + text }];
  }
  return [...trace, { kind: 'thinking', text }];
}
