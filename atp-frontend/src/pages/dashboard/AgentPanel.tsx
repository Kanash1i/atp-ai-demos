import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ColLabel, LiveDot, SectionTitle, Tag } from '../../components/ui';
import { IconAgent, IconArrowRight, IconPlus, IconStop } from '../../components/icons';
import {
  conversationMessages, deleteConversation, interruptChat, listConversations, streamChat,
} from '../../lib/api';
import {
  addTurn, appendThinking, forgetIfCurrent, newConversation, openConversation, patchTurn,
  thinkingLength, useChat,
  type AgentPlan, type SubtaskState, type TraceItem, type Turn,
} from '../../lib/chatStore';
import { uuidv4 } from '../../lib/uuid';
import type { ChatConversation, ChatEvent, ChatMessage, ChatTimeline } from '../../lib/types';

/**
 * 智能 Agent 助手。
 *
 * 它不只是问答：一屏里同时容纳会话列表、流式对话、路由结论、任务清单、工具调用、思考过程。
 * 对标参考实现的「对话窗 + 处理过程面板」。
 */

const SUGGESTION_KEYS = ['agent.q1', 'agent.q2', 'agent.q3'] as const;

/**
 * agent 的表格有时 4~5 列，气泡宽度放不下。
 * 让表格自己横向滚，而不是把整个对话区撑宽 —— 撑宽的话右边的处理过程面板会被挤掉。
 */
const MD_COMPONENTS = {
  table: ({ children, ...rest }: React.ComponentPropsWithoutRef<'table'>) => (
    <div className="table-scroll">
      <table {...rest}>{children}</table>
    </div>
  ),
};

/* ============================================================
   事件内容的解析
   ============================================================ */

/** `✓ search_standards "检索到 3 条规范：…"` 或 `▸ inspect_page` */
const TOOL_LINE = /^([▸✓✗])\s+([A-Za-z_][\w.-]*)\s*([\s\S]*)$/;

/**
 * 工具**发起**是从 thinking 通道下来的，不是 tool 事件 —— 后端只把结果推成 tool。
 * 整块恰好是 `▸ 工具名` 时提成一条 call，面板里才配得成「发起 → 结果」一对。
 * 卡这么死是有意的：thinking 是逐字增量，别让半句话被误判成工具调用。
 */
const TOOL_CALL_CHUNK = /^▸\s+([A-Za-z_][\w.-]*)$/;

/**
 * 工具结果外面还裹着一层 JSON 字符串的引号，剥掉再显示。
 *
 * ⚠️ 不能用 JSON.parse 剥：结果被后端截到 400 字，尾部的引号可能整个没了 ——
 * 所以是「有才剥」，不是「假定成对」。
 *
 * 2026-09-06：这里原本还要还原字面量 `\n`（后端把 JSON 节点 toString 过一道）。
 * 后端已在源头修掉，实测新流里字面量 `\n` 为 0 条，那段反转义就删了 ——
 * 留着会让下一个人以为后端还在推转义文本。
 */
function unquote(raw: string): string {
  const s = raw.trim();
  const body = s.startsWith('"') ? s.slice(1) : s;
  return body.endsWith('"') ? body.slice(0, -1) : body;
}

function parseTool(content: string): TraceItem {
  const m = TOOL_LINE.exec(content.trim());
  // 认不出来就整条当结果显示 —— 宁可样子糙，也别把一次工具调用吞掉
  if (!m) return { kind: 'tool', phase: 'result', name: '', detail: content };
  return {
    kind: 'tool',
    phase: m[1] === '▸' ? 'call' : 'result',
    name: m[2],
    detail: unquote(m[3]),
  };
}

/** plan 的 content 是一段 JSON 字符串。坏了一份就留着上一份，别把面板清空 */
function parsePlan(raw: string): AgentPlan | null {
  try {
    const p = JSON.parse(raw) as AgentPlan;
    return p && Array.isArray(p.subtasks) ? p : null;
  } catch {
    return null;
  }
}

/** 历史消息里的路由结论藏在 timelineJson 里，还原成「案例编写 · L2 0.98」那种标签 */
function routeOf(msg: ChatMessage): string | null {
  if (!msg.timelineJson) return null;
  try {
    const t = JSON.parse(msg.timelineJson) as ChatTimeline;
    const bits = [t.agent, t.layer, typeof t.score === 'number' ? t.score.toFixed(2) : null];
    const text = bits.filter(Boolean).join(' · ');
    return text || null;
  } catch {
    return null;
  }
}

const blankTurn = (id: string, question: string): Turn => ({
  id, question, route: null, agent: null,
  trace: [], plan: null, answer: '', error: null, streaming: false,
});

/** 历史消息（user / assistant 交替）折成 Turn */
function toTurns(messages: ChatMessage[]): Turn[] {
  const turns: Turn[] = [];
  for (const m of messages) {
    if (m.role === 'user') {
      turns.push({ ...blankTurn(m.messageId, m.content), fromHistory: true });
      continue;
    }
    const last = turns[turns.length - 1];
    if (last && !last.answer) {
      last.answer = m.content;
      last.agent = m.agentName;
      last.route = routeOf(m);
    } else {
      // 落单的 assistant 消息（历史里理论上不该有，但别把它吞掉）
      turns.push({
        ...blankTurn(m.messageId, ''),
        route: routeOf(m), agent: m.agentName, answer: m.content, fromHistory: true,
      });
    }
  }
  return turns;
}

/* ============================================================
   会话列表
   ============================================================ */

function ConversationRail({
  activeId, onPick, onNew,
}: {
  activeId: string;
  onPick: (id: string) => void;
  onNew: () => void;
}) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const list = useQuery({ queryKey: ['chat', 'conversations'], queryFn: listConversations, retry: 0 });

  /*
   * 乐观删除：点下去那一刻就把行拿掉，不等接口回来。
   *
   * 用户的原话是「点击删除要主动刷新页面下一次才能发现变化」——
   * 那次的根因是请求在前端看来失败了（200 + 空 body 解析炸了），但即便请求正常，
   * 「点了之后等一个往返才有反应」本身也不是好的删除手感。
   *
   * 失败就把那一行放回去，并且不吞错误 —— 删不掉却装作删掉了，比不动更糟。
   */
  const del = useMutation({
    mutationFn: (id: string) => deleteConversation(id),
    retry: 0,
    onMutate: async (id: string) => {
      await qc.cancelQueries({ queryKey: ['chat', 'conversations'] });
      const previous = qc.getQueryData<ChatConversation[]>(['chat', 'conversations']);
      qc.setQueryData<ChatConversation[]>(['chat', 'conversations'], (old) =>
        (old ?? []).filter((c) => c.conversationId !== id),
      );
      return { previous };
    },
    onError: (_e, _id, ctx) => {
      if (ctx?.previous) qc.setQueryData(['chat', 'conversations'], ctx.previous);
    },
    onSuccess: (_d, id) => {
      // 删掉的正好是当前会话时，顺手开一个新的，别停在一个已经不存在的 id 上
      forgetIfCurrent(id);
    },
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: ['chat', 'conversations'] });
    },
  });

  return (
    <aside className="card-surface flex w-[220px] shrink-0 flex-col overflow-hidden">
      <div className="flex shrink-0 items-center gap-2 border-b border-line px-3.5 py-[15px]">
        <span className="font-jp grow text-[13.5px] font-bold">{t('agent.conversations')}</span>
        <button
          type="button"
          onClick={onNew}
          title={t('agent.newChat')}
          aria-label={t('agent.newChat')}
          className="flex items-center gap-1 rounded-sm border border-line px-2 py-1 text-[11px] text-ink-2 hover:bg-line-4"
        >
          <IconPlus size={11} />
          {t('agent.newChat')}
        </button>
      </div>

      {/* 删失败要说出来 —— 删不掉却装作删掉了，比列表不动更糟 */}
      {del.error && (
        <div className="shrink-0 border-b border-line bg-shu-soft px-3.5 py-2 text-[11px] leading-[1.7] text-shu">
          {t('agent.deleteFailed')}
        </div>
      )}

      <div className="scrollable min-h-0 grow p-2">
        {list.data?.length === 0 && (
          <div className="px-2 pt-8 text-center text-[11.5px] leading-[1.8] text-ink-4">
            {t('agent.noConversations')}
          </div>
        )}
        {list.data?.map((c) => {
          const active = c.conversationId === activeId;
          return (
            <div
              key={c.conversationId}
              className={`group mb-0.5 flex items-start gap-1.5 rounded-sm px-2 py-2 transition-all ${
                active ? 'bg-shu-soft shadow-[inset_2px_0_0_var(--color-shu)]' : 'hover:bg-line-4'
              } ${del.isPending && del.variables === c.conversationId ? 'pointer-events-none opacity-40' : ''}`}
            >
              <button
                type="button"
                onClick={() => onPick(c.conversationId)}
                className="min-w-0 grow text-left"
              >
                {/* 标题是后端按首条用户消息生成的，属于领域内容，不翻译 */}
                <div className="truncate text-[12px] text-ink-2">{c.title}</div>
                <div className="mt-1 font-mono text-[9.5px] text-ink-5">{c.updatedAt}</div>
              </button>
              <button
                type="button"
                onClick={() => del.mutate(c.conversationId)}
                disabled={del.isPending}
                title={t('agent.deleteChat')}
                aria-label={t('agent.deleteChat')}
                className="shrink-0 rounded-sm px-1 text-[14px] leading-none text-ink-5 opacity-0 transition-opacity group-hover:opacity-100 hover:text-shu"
              >
                ×
              </button>
            </div>
          );
        })}
      </div>
    </aside>
  );
}

/* ============================================================
   任务清单
   ============================================================ */

const PLAN_MARK: Record<SubtaskState, { glyph: string; cls: string }> = {
  DONE: { glyph: '✓', cls: 'text-matsu' },
  IN_PROGRESS: { glyph: '◐', cls: 'text-ai' },
  TODO: { glyph: '○', cls: 'text-ink-5' },
  ABANDONED: { glyph: '⊘', cls: 'text-ink-5' },
};

/**
 * 任务清单钉在顶上，每来一份**覆盖**上一份 —— 它是一个会变的状态，不是一条消息。
 * 追加成第 N 条的话，看的人得自己找哪份是最新的。
 *
 * 打断时这块最有用：停下的那一刻哪些 DONE、哪个停在半路、还剩几个，
 * 比一句「已停止」有用得多 —— 用户据此决定要不要接着做。
 */
function PlanBlock({ plan, stopped }: { plan: AgentPlan; stopped: boolean }) {
  const { t } = useTranslation();
  const done = plan.subtasks.filter((s) => s.state === 'DONE').length;

  return (
    <div className="scrollable max-h-[45%] shrink-0 border-b border-line px-[18px] py-3">
      <div className="mb-1.5 flex items-center gap-2">
        <ColLabel>{t('agent.plan')}</ColLabel>
        <div className="grow" />
        <span className="font-mono text-[10px] tabular-nums text-ink-4">
          {done}/{plan.subtasks.length}
        </span>
      </div>

      {/* 清单名与子任务名都是 agent 生成的领域内容，不翻译 */}
      <div className="mb-2 text-[11.5px] leading-[1.6] text-ink-2">{plan.name}</div>

      <ol className="m-0 list-none p-0">
        {plan.subtasks.map((s, i) => {
          /*
           * 打断之后那条 IN_PROGRESS 其实已经不在跑了 —— 后端不会再补一份把它改成 ABANDONED。
           * 继续画成「进行中」是在骗人，所以这里换成「停在这一步」。
           */
          const stoppedHere = stopped && s.state === 'IN_PROGRESS';
          const mark = stoppedHere
            ? { glyph: '⏸', cls: 'text-yamabuki' }
            : (PLAN_MARK[s.state] ?? PLAN_MARK.TODO);

          return (
            <li key={`${i}-${s.name}`} className="mb-1.5 flex gap-2 last:mb-0">
              <span className={`mt-px shrink-0 text-[11px] leading-[1.6] ${mark.cls}`}>{mark.glyph}</span>
              <div className="min-w-0 grow">
                <div
                  className={`text-[11.5px] leading-[1.6] ${
                    s.state === 'DONE' ? 'text-ink-3'
                      : s.state === 'ABANDONED' ? 'text-ink-5 line-through'
                        : 'text-ink-2'
                  }`}
                >
                  {s.name}
                  {stoppedHere && (
                    <span className="ml-1.5 font-mono text-[9.5px] text-yamabuki">
                      {t('agent.planStopped')}
                    </span>
                  )}
                </div>
                {s.outcome && (
                  <div
                    title={s.outcome}
                    className="mt-0.5 line-clamp-2 text-[10.5px] leading-[1.65] text-ink-4"
                  >
                    {s.outcome}
                  </div>
                )}
              </div>
            </li>
          );
        })}
      </ol>
    </div>
  );
}

/* ============================================================
   处理过程：思考 + 工具调用，按到达顺序
   ============================================================ */

function TraceBody({ trace }: { trace: TraceItem[] }) {
  return (
    <>
      {trace.map((item, i) =>
        item.kind === 'thinking' ? (
          <pre
            key={i}
            className="m-0 font-sans text-[11.5px] leading-[1.9] whitespace-pre-wrap text-ink-3"
          >
            {item.text}
          </pre>
        ) : (
          <div key={i} className="my-2 rounded-sm border border-line bg-surface-2 px-2.5 py-2">
            <div className="flex items-center gap-1.5">
              <span className={`text-[11px] leading-none ${item.phase === 'call' ? 'text-ai' : 'text-matsu'}`}>
                {item.phase === 'call' ? '▸' : '✓'}
              </span>
              {/* 工具名是领域内容，不翻译 */}
              <span className="font-mono text-[10.5px] text-ink-2">{item.name}</span>
            </div>
            {item.detail && (
              <div
                title={item.detail}
                className="mt-1 line-clamp-3 font-mono text-[10px] leading-[1.7] break-words whitespace-pre-wrap text-ink-4"
              >
                {item.detail}
              </div>
            )}
          </div>
        ),
      )}
    </>
  );
}

/* ============================================================
   主面板
   ============================================================ */

export default function AgentPanel() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const { conversationId, turns } = useChat();

  const abort = useRef<AbortController | null>(null);
  const liveId = useRef<string | null>(null);
  const scroller = useRef<HTMLDivElement>(null);
  const traceScroller = useRef<HTMLDivElement>(null);

  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [stopping, setStopping] = useState(false);

  /*
   * ⚠️ 卸载时**不要**调 DELETE /api/chat/{id} —— 那个接口是软删除，不是「关闭面板」。
   * 早先就是这么写的，于是从助手切到执行看一眼，服务端那条会话就真没了。
   * 现在只中断进行中的流；会话本身留着，历史列表里能翻回来。
   */
  useEffect(() => () => abort.current?.abort(), []);

  useEffect(() => {
    scroller.current?.scrollTo({ top: scroller.current.scrollHeight, behavior: 'smooth' });
  }, [turns.length, conversationId]);

  const scrollTrace = () => {
    queueMicrotask(() => {
      const el = traceScroller.current;
      if (el) el.scrollTop = el.scrollHeight;
    });
  };

  const openHistory = async (id: string) => {
    if (busy || id === conversationId) return;
    try {
      const msgs = await conversationMessages(id);
      openConversation(id, toTurns(msgs));
    } catch {
      openConversation(id, []);
    }
  };

  /**
   * 停止这一轮。
   *
   * ⚠️ 关键：调完 interrupt **不 abort 本地的 fetch**。
   * 收尾的 `tool-aborted` / `interrupted` 两条还在后面，本地一断就全收不到 ——
   * 而「哪些工具做了一半」正是用户最需要看到的信息。让服务端把流关掉。
   *
   * 兜底：interrupt 送不到（或第二次点）时才本地硬断，并说清后端可能还在跑。
   */
  const stop = useCallback(async () => {
    const id = liveId.current;
    if (!id) return;

    /*
     * 第二次点 = 本地硬断。收尾的两条事件就收不到了，所以在断之前
     * 先把已知的结论落下来 —— 否则界面就是「点了之后什么都没发生，流停住了」，
     * 这个仓库已经栽过一次同类的坑（删会话 200 + 空 body，也是静默失败）。
     */
    if (stopping) {
      patchTurn(id, (x) => ({
        ...x, interrupted: true, stopping: false, error: x.error ?? t('agent.forceStopped'),
      }));
      abort.current?.abort();
      return;
    }

    setStopping(true);
    patchTurn(id, (x) => ({ ...x, stopping: true }));
    try {
      const r = await interruptChat(conversationId);
      /*
       * 响应体里也带 pendingTools。正常情况下随后的 `tool-aborted` 会给出同样的内容，
       * 但流要是被掐断（硬断 / 连接断），这就是「哪些工具做了一半」的唯一来源。
       * 已经有值就不覆盖 —— 事件里的那份更权威。
       */
      if (r.pendingTools) {
        patchTurn(id, (x) => ({ ...x, abortedTools: x.abortedTools ?? r.pendingTools }));
      }
      // ⚠️ 到这里**不 abort**，等服务端把收尾事件推完再自己关流
    } catch {
      patchTurn(id, (x) => ({
        ...x, interrupted: true, stopping: false, error: t('agent.stopFailed'),
      }));
      abort.current?.abort();
    }
  }, [conversationId, stopping, t]);

  /*
   * 用 Esc 而不是 Ctrl+C：输入框里选中文字时 Ctrl+C 必须是复制，不能抢。
   * `isComposing` 要挡 —— 日文/中文输入法转换途中按 Esc 是取消候选，那也不能抢。
   */
  useEffect(() => {
    if (!busy) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape' || e.isComposing) return;
      if (document.querySelector('[role="dialog"]')) return; // 有弹窗时 Esc 归弹窗
      e.preventDefault();
      void stop();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [busy, stop]);

  const send = (text: string) => {
    const question = text.trim();
    if (!question || busy) return;

    const id = uuidv4();
    addTurn({ ...blankTurn(id, question), streaming: true });
    liveId.current = id;
    setInput('');
    setBusy(true);
    setStopping(false);

    const onEvent = (e: ChatEvent) => {
      switch (e.type) {
        case 'route':
          patchTurn(id, (x) => ({ ...x, route: e.content, agent: e.agent }));
          break;

        case 'thinking': {
          // 增量：拼接。整块恰好是 `▸ 工具名` 时提成一条工具发起
          const call = TOOL_CALL_CHUNK.exec(e.content.trim());
          patchTurn(id, (x) => ({
            ...x,
            agent: e.agent || x.agent,
            trace: call
              ? [...x.trace, { kind: 'tool', phase: 'call', name: call[1], detail: '' }]
              : appendThinking(x.trace, e.content),
          }));
          scrollTrace();
          break;
        }

        case 'tool':
          patchTurn(id, (x) => ({
            ...x, agent: e.agent || x.agent, trace: [...x.trace, parseTool(e.content)],
          }));
          scrollTrace();
          break;

        case 'plan':
          patchTurn(id, (x) => ({ ...x, plan: parsePlan(e.content) ?? x.plan }));
          break;

        case 'message':
          // 完整：替换
          patchTurn(id, (x) => ({ ...x, answer: e.content, agent: e.agent || x.agent }));
          break;

        /*
         * ⚠️ tool-aborted 先于 interrupted 到，而且它才是用户真正需要看到的那条。
         * agent 字段这两条都是 "system"，别拿它去覆盖本轮的 agent 名。
         */
        case 'tool-aborted':
          patchTurn(id, (x) => ({ ...x, abortedTools: e.content || null }));
          break;

        case 'interrupted':
          patchTurn(id, (x) => ({ ...x, interrupted: true, streaming: false, stopping: false }));
          break;

        case 'error':
          patchTurn(id, (x) => ({ ...x, error: e.content || 'error', streaming: false }));
          break;

        case 'done':
          patchTurn(id, (x) => ({ ...x, streaming: false }));
          break;
      }
    };

    const ctrl = new AbortController();
    abort.current = ctrl;

    // ⚠️ 不设自己的超时。一轮可能 1~3 分钟，后端 SseEmitter 是 300 秒
    void streamChat(conversationId, question, onEvent, ctrl.signal)
      .catch((err: unknown) => {
        if (ctrl.signal.aborted) return;
        patchTurn(id, (x) => ({ ...x, error: err instanceof Error ? err.message : String(err), streaming: false }));
      })
      .finally(() => {
        // 打断时后端**不发 done**，流直接结束 —— 收尾只能靠这里
        patchTurn(id, (x) => ({ ...x, streaming: false, stopping: false }));
        liveId.current = null;
        setBusy(false);
        setStopping(false);
        // 第一轮结束后会话才在后端落库，这时列表里才有它
        void qc.invalidateQueries({ queryKey: ['chat', 'conversations'] });
      });
  };

  const live =
    turns.find((x) => x.streaming) ??
    [...turns].reverse().find((x) => x.trace.length > 0 || x.plan) ??
    null;

  return (
    <div className="flex h-full gap-4 overflow-hidden px-6 py-5">
      <ConversationRail
        activeId={conversationId}
        onPick={(id) => void openHistory(id)}
        onNew={() => newConversation()}
      />

      {/* ---------- 对话 ---------- */}
      <section className="card-surface flex min-w-0 grow flex-col overflow-hidden">
        <div className="flex shrink-0 items-center gap-[11px] border-b border-line px-[22px] py-[15px]">
          <SectionTitle>{t('agent.title')}</SectionTitle>
          <Tag tone="bg-ai-soft text-ai">bge-m3 + bge-reranker-v2-m3</Tag>
          <div className="grow" />
          <span className="font-mono text-[10.5px] text-ink-5">{conversationId.slice(0, 8)}</span>
        </div>

        <div ref={scroller} className="scrollable min-h-0 grow p-[22px]">
          {turns.length === 0 && (
            <div className="flex h-full flex-col items-center justify-center text-center">
              <IconAgent size={26} className="text-ink-5" />
              <div className="mt-3 max-w-[460px] text-[12.5px] leading-[1.9] text-ink-4">
                {t('agent.intro')}
              </div>
            </div>
          )}

          {turns.map((turn) => (
            <div key={turn.id} className="mb-[22px]">
              {turn.question && (
                <div className="mb-[18px] flex justify-end">
                  <div className="max-w-[64%] rounded-[8px_8px_2px_8px] bg-rail px-[17px] py-[13px] text-[13.5px] leading-[1.85]">
                    {turn.question}
                  </div>
                </div>
              )}

              <div className="flex gap-3">
                <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-shu-soft">
                  <IconAgent size={15} className="text-shu" />
                </span>
                <div className="min-w-0 grow">
                  <div className="mb-2.5 flex flex-wrap items-center gap-2">
                    {/*
                      路由结论要显示出来，历史里也要 —— 「多 agent 协作」是这个 demo 的看点之一，
                      历史里全是无差别的气泡就把这个看点弄没了。
                      历史消息的路由从 timelineJson 还原。
                    */}
                    {turn.route && <Tag tone="bg-ai-soft text-ai" mono={false}>{turn.route}</Tag>}
                    {turn.agent && <span className="font-mono text-[10.5px] text-ink-4">{turn.agent}</span>}
                    {turn.streaming && <LiveDot className="bg-ai" size={6} />}
                  </div>

                  {turn.answer ? (
                    <div className="agent-md text-[13.5px] leading-[2]">
                      {/*
                        ⚠️ 表格、删除线、任务列表、自动链接都不是 CommonMark，是 GFM 扩展。
                        react-markdown 默认只认 CommonMark，不挂 remark-gfm 的话
                        agent 生成的案例表会原样显示成一堆竖线 ——
                        而那一屏正是演示里最关键的一屏。
                      */}
                      <Markdown remarkPlugins={[remarkGfm]} components={MD_COMPONENTS}>
                        {turn.answer}
                      </Markdown>
                    </div>
                  ) : turn.streaming ? (
                    <div className="text-[12.5px] text-ink-4">
                      {turn.stopping ? t('agent.stopping') : t('common.loading')}
                    </div>
                  ) : null}

                  {/*
                    ⚠️ 打断的语义是「不再继续下一步」，**不是「撤销已做的」**。
                    commit_case 的 HTTP 一旦发出去就是提交了，数据库那侧不回滚也不该回滚。
                    所以这里的措辞必须是「已经执行、结果被丢弃」，不能写成「已取消 / 已回滚」——
                    让人以为数据回到了打断前，比不给停止按钮更危险。
                  */}
                  {turn.interrupted && (
                    <div className="mt-2.5 rounded-md border border-yamabuki/30 bg-yamabuki-soft px-3 py-2.5 text-[11.5px] leading-[1.85]">
                      <div className="font-medium text-yamabuki">{t('agent.interrupted')}</div>
                      {turn.abortedTools && (
                        <div className="mt-1 text-ink-2">
                          {t('agent.abortedTools', { tools: turn.abortedTools })}
                        </div>
                      )}
                      <div className="mt-1 text-ink-3">{t('agent.noRollback')}</div>
                    </div>
                  )}

                  {turn.error && (
                    <div className="mt-2 rounded-md border border-shu/30 bg-shu-soft px-3 py-2 text-[11.5px] leading-[1.8] text-shu">
                      {turn.error}
                    </div>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>

        <div className="shrink-0 border-t border-line px-[22px] pt-3.5 pb-[18px]">
          <div className="flex items-center gap-[11px] rounded-lg border border-line bg-paper px-[15px] py-[11px]">
            <input
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => {
                // isComposing：输入法选字时的回车是确认候选，不是发送
                if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                  e.preventDefault();
                  send(input);
                }
              }}
              disabled={busy}
              placeholder={t('agent.placeholder')}
              className="grow bg-transparent text-[13px] outline-none placeholder:text-ink-4 disabled:opacity-50"
            />
            {busy ? (
              <button
                type="button"
                onClick={() => void stop()}
                title={t('agent.stopHint')}
                aria-label={t('agent.stop')}
                className={`flex h-[30px] w-[30px] items-center justify-center rounded-md border border-shu text-shu transition-colors hover:bg-shu-soft ${
                  stopping ? 'opacity-60' : ''
                }`}
              >
                <IconStop size={11} />
              </button>
            ) : (
              <button
                type="button"
                onClick={() => send(input)}
                disabled={!input.trim()}
                aria-label={t('agent.send')}
                className="flex h-[30px] w-[30px] items-center justify-center rounded-md bg-shu transition-colors hover:bg-shu-hover disabled:opacity-40"
              >
                <IconArrowRight size={15} className="text-white" strokeWidth={2} />
              </button>
            )}
          </div>
          <div className="mt-[11px] flex flex-wrap gap-2">
            {SUGGESTION_KEYS.map((k) => (
              <button
                key={k}
                type="button"
                disabled={busy}
                onClick={() => send(t(k))}
                className="rounded-sm border border-line px-[11px] py-1.5 text-[11.5px] text-ink-2 hover:bg-line-4 disabled:opacity-40"
              >
                {t(k)}
              </button>
            ))}
          </div>
          <div className="mt-2 text-[10.5px] leading-[1.7] text-ink-4">
            {busy ? t('agent.stopHint') : t('agent.slowNotice')}
          </div>
        </div>
      </section>

      {/* ---------- 执行进度：任务清单 + 工具调用 + 思考 ---------- */}
      <aside className="card-surface flex w-[320px] shrink-0 flex-col overflow-hidden">
        <div className="flex shrink-0 items-center gap-2 border-b border-line px-[18px] py-[15px]">
          <span className="font-jp text-[13.5px] font-bold">{t('agent.processing')}</span>
          {live?.streaming && <LiveDot className="bg-ai" size={6} />}
          <div className="grow" />
          <ColLabel>{live ? thinkingLength(live.trace) || '' : ''}</ColLabel>
        </div>

        {live?.plan && <PlanBlock plan={live.plan} stopped={live.interrupted === true} />}

        <div ref={traceScroller} className="scrollable min-h-0 grow px-[18px] py-3.5">
          {live && live.trace.length > 0 ? (
            <TraceBody trace={live.trace} />
          ) : (
            <div className="pt-10 text-center text-[11.5px] leading-[1.9] text-ink-4">
              {/* 历史会话没有 thinking 流 —— 后端只落了 message，没落增量 */}
              {turns.some((x) => x.fromHistory) ? t('agent.noHistoryThinking') : t('agent.processingEmpty')}
            </div>
          )}
        </div>
      </aside>
    </div>
  );
}
