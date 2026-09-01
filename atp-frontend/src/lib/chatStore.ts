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

export interface Turn {
  id: string;
  question: string;
  /** 路由结论，形如「案例编写 · L2 0.98」。末尾是置信度 */
  route: string | null;
  agent: string | null;
  /** thinking 是增量，按顺序拼 */
  thinking: string;
  /** message 是完整内容，直接替换 —— 当增量拼会显示两遍 */
  answer: string;
  error: string | null;
  streaming: boolean;
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
