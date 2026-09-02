import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ColLabel, LiveDot, SectionTitle, Tag } from '../../components/ui';
import { IconAgent, IconArrowRight, IconPlus } from '../../components/icons';
import {
  conversationMessages, deleteConversation, listConversations, streamChat,
} from '../../lib/api';
import {
  addTurn, forgetIfCurrent, newConversation, openConversation, patchTurn, useChat, type Turn,
} from '../../lib/chatStore';
import { uuidv4 } from '../../lib/uuid';
import type { ChatConversation, ChatEvent, ChatMessage, ChatTimeline } from '../../lib/types';

/**
 * 智能 Agent 助手。
 *
 * 它不只是问答：一屏里同时容纳会话列表、流式对话、路由结论、思考过程。
 * 对标参考实现的「对话窗 + 处理过程面板」。
 */

const SUGGESTION_KEYS = ['agent.q1', 'agent.q2', 'agent.q3'] as const;

/**
 * agent 的表格有时 4~5 列，气泡宽度放不下。
 * 让表格自己横向滚，而不是把整个对话区撑宽 —— 撑宽的话右边的思考过程面板会被挤掉。
 */
const MD_COMPONENTS = {
  table: ({ children, ...rest }: React.ComponentPropsWithoutRef<'table'>) => (
    <div className="table-scroll">
      <table {...rest}>{children}</table>
    </div>
  ),
};

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

/** 历史消息（user / assistant 交替）折成 Turn */
function toTurns(messages: ChatMessage[]): Turn[] {
  const turns: Turn[] = [];
  for (const m of messages) {
    if (m.role === 'user') {
      turns.push({
        id: m.messageId,
        question: m.content,
        route: null, agent: null, thinking: '', answer: '',
        error: null, streaming: false, fromHistory: true,
      });
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
        id: m.messageId, question: '', route: routeOf(m), agent: m.agentName,
        thinking: '', answer: m.content, error: null, streaming: false, fromHistory: true,
      });
    }
  }
  return turns;
}

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

export default function AgentPanel() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const { conversationId, turns } = useChat();

  const abort = useRef<AbortController | null>(null);
  const scroller = useRef<HTMLDivElement>(null);
  const thinkScroller = useRef<HTMLDivElement>(null);

  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);

  /*
   * ⚠️ 卸载时**不要**调 DELETE /api/chat/{id} —— 那个接口是软删除，不是「关闭面板」。
   * 早先就是这么写的，于是从助手切到执行看一眼，服务端那条会话就真没了。
   * 现在只中断进行中的流；会话本身留着，历史列表里能翻回来。
   */
  useEffect(() => () => abort.current?.abort(), []);

  useEffect(() => {
    scroller.current?.scrollTo({ top: scroller.current.scrollHeight, behavior: 'smooth' });
  }, [turns.length, conversationId]);

  const openHistory = async (id: string) => {
    if (busy || id === conversationId) return;
    try {
      const msgs = await conversationMessages(id);
      openConversation(id, toTurns(msgs));
    } catch {
      openConversation(id, []);
    }
  };

  const send = (text: string) => {
    const question = text.trim();
    if (!question || busy) return;

    const id = uuidv4();
    addTurn({
      id, question, route: null, agent: null,
      thinking: '', answer: '', error: null, streaming: true,
    });
    setInput('');
    setBusy(true);

    const onEvent = (e: ChatEvent) => {
      switch (e.type) {
        case 'route':
          patchTurn(id, (x) => ({ ...x, route: e.content, agent: e.agent }));
          break;
        case 'thinking':
          // 增量：拼接
          patchTurn(id, (x) => ({ ...x, thinking: x.thinking + e.content, agent: e.agent || x.agent }));
          queueMicrotask(() => {
            const el = thinkScroller.current;
            if (el) el.scrollTop = el.scrollHeight;
          });
          break;
        case 'message':
          // 完整：替换
          patchTurn(id, (x) => ({ ...x, answer: e.content, agent: e.agent || x.agent }));
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
        patchTurn(id, (x) => ({ ...x, streaming: false }));
        setBusy(false);
        // 第一轮结束后会话才在后端落库，这时列表里才有它
        void qc.invalidateQueries({ queryKey: ['chat', 'conversations'] });
      });
  };

  const live = turns.find((x) => x.streaming) ?? [...turns].reverse().find((x) => x.thinking) ?? null;

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
                    <div className="text-[12.5px] text-ink-4">{t('common.loading')}</div>
                  ) : null}

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
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  send(input);
                }
              }}
              disabled={busy}
              placeholder={t('agent.placeholder')}
              className="grow bg-transparent text-[13px] outline-none placeholder:text-ink-4 disabled:opacity-50"
            />
            <button
              type="button"
              onClick={() => send(input)}
              disabled={busy || !input.trim()}
              aria-label={t('agent.send')}
              className="flex h-[30px] w-[30px] items-center justify-center rounded-md bg-shu transition-colors hover:bg-shu-hover disabled:opacity-40"
            >
              <IconArrowRight size={15} className="text-white" strokeWidth={2} />
            </button>
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
          <div className="mt-2 text-[10.5px] leading-[1.7] text-ink-4">{t('agent.slowNotice')}</div>
        </div>
      </section>

      {/* ---------- 思考过程 ---------- */}
      <aside className="card-surface flex w-[300px] shrink-0 flex-col overflow-hidden">
        <div className="flex shrink-0 items-center gap-2 border-b border-line px-[18px] py-[15px]">
          <span className="font-jp text-[13.5px] font-bold">{t('agent.processing')}</span>
          {live?.streaming && <LiveDot className="bg-ai" size={6} />}
          <div className="grow" />
          <ColLabel>{live?.thinking.length ? `${live.thinking.length}` : ''}</ColLabel>
        </div>
        <div ref={thinkScroller} className="scrollable min-h-0 grow px-[18px] py-3.5">
          {live?.thinking ? (
            <pre className="m-0 font-sans text-[11.5px] leading-[1.9] whitespace-pre-wrap text-ink-3">
              {live.thinking}
            </pre>
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
