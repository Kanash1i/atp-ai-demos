import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import Markdown from 'react-markdown';
import { ColLabel, LiveDot, SectionTitle, Tag } from '../../components/ui';
import { IconAgent, IconArrowRight } from '../../components/icons';
import { closeChat, streamChat } from '../../lib/api';
import type { ChatEvent } from '../../lib/types';
import { uuidv4 } from '../../lib/uuid';

/**
 * 智能 Agent 助手 —— 设计稿里那个「RAG 问答助手」。
 *
 * 它不只是问答：一屏里同时容纳流式对话、路由结论、思考过程。
 * 对标 gogo-agent 的 ChatWindow + ProcessingPanel。
 */

interface Turn {
  id: number;
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
}

const SUGGESTION_KEYS = ['agent.q1', 'agent.q2', 'agent.q3'] as const;

export default function AgentPanel() {
  const { t } = useTranslation();

  // 同一个 id 的多轮对话共享上下文；换 id 等于开新会话
  const conversationId = useRef(uuidv4());
  const abort = useRef<AbortController | null>(null);
  const scroller = useRef<HTMLDivElement>(null);
  const thinkScroller = useRef<HTMLDivElement>(null);

  const [turns, setTurns] = useState<Turn[]>([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);

  // 关闭面板时释放该会话的 agent 实例与上下文
  useEffect(() => {
    const id = conversationId.current;
    return () => {
      abort.current?.abort();
      void closeChat(id).catch(() => {
        /* 面板已经卸载了，这里失败没人看得见，也不影响什么 */
      });
    };
  }, []);

  useEffect(() => {
    scroller.current?.scrollTo({ top: scroller.current.scrollHeight, behavior: 'smooth' });
  }, [turns.length]);

  const send = (text: string) => {
    const question = text.trim();
    if (!question || busy) return;

    const id = Date.now();
    setTurns((v) => [
      ...v,
      { id, question, route: null, agent: null, thinking: '', answer: '', error: null, streaming: true },
    ]);
    setInput('');
    setBusy(true);

    const patch = (fn: (t: Turn) => Turn) =>
      setTurns((v) => v.map((x) => (x.id === id ? fn(x) : x)));

    const onEvent = (e: ChatEvent) => {
      switch (e.type) {
        case 'route':
          patch((x) => ({ ...x, route: e.content, agent: e.agent }));
          break;
        case 'thinking':
          // 增量：拼接
          patch((x) => ({ ...x, thinking: x.thinking + e.content, agent: e.agent || x.agent }));
          queueMicrotask(() => {
            const el = thinkScroller.current;
            if (el) el.scrollTop = el.scrollHeight;
          });
          break;
        case 'message':
          // 完整：替换
          patch((x) => ({ ...x, answer: e.content, agent: e.agent || x.agent }));
          break;
        case 'error':
          patch((x) => ({ ...x, error: e.content || 'error', streaming: false }));
          break;
        case 'done':
          patch((x) => ({ ...x, streaming: false }));
          break;
      }
    };

    const ctrl = new AbortController();
    abort.current = ctrl;

    // ⚠️ 不设自己的超时。一轮可能 1~3 分钟（agent 要查规范、探查页面、写草稿、
    // 校验、提交、跑自验），后端 SseEmitter 是 300 秒
    void streamChat(conversationId.current, question, onEvent, ctrl.signal)
      .catch((err: unknown) => {
        if (ctrl.signal.aborted) return;
        patch((x) => ({ ...x, error: err instanceof Error ? err.message : String(err), streaming: false }));
      })
      .finally(() => {
        patch((x) => ({ ...x, streaming: false }));
        setBusy(false);
      });
  };

  const live = turns.find((x) => x.streaming) ?? turns[turns.length - 1] ?? null;

  return (
    <div className="flex h-full gap-5 overflow-hidden px-6 py-5">
      {/* ---------- 对话 ---------- */}
      <section className="card-surface flex min-w-0 grow flex-col overflow-hidden">
        <div className="flex shrink-0 items-center gap-[11px] border-b border-line px-[22px] py-[15px]">
          <SectionTitle>{t('agent.title')}</SectionTitle>
          <Tag tone="bg-ai-soft text-ai">bge-m3 + bge-reranker-v2-m3</Tag>
          <div className="grow" />
          <span className="font-mono text-[10.5px] text-ink-5">
            {conversationId.current.slice(0, 8)}
          </span>
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
              <div className="mb-[18px] flex justify-end">
                <div className="max-w-[64%] rounded-[8px_8px_2px_8px] bg-rail px-[17px] py-[13px] text-[13.5px] leading-[1.85]">
                  {turn.question}
                </div>
              </div>

              <div className="flex gap-3">
                <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-shu-soft">
                  <IconAgent size={15} className="text-shu" />
                </span>
                <div className="min-w-0 grow">
                  <div className="mb-2.5 flex flex-wrap items-center gap-2">
                    {/*
                      路由结论要显示出来。路由是会判错的 —— 用户看得见就能立刻说
                      「不是这个意思」，而不是等一大段答案跑完才发现跑偏
                    */}
                    {turn.route && <Tag tone="bg-ai-soft text-ai" mono={false}>{turn.route}</Tag>}
                    {turn.agent && <span className="font-mono text-[10.5px] text-ink-4">{turn.agent}</span>}
                    {turn.streaming && <LiveDot className="bg-ai" size={6} />}
                  </div>

                  {turn.answer ? (
                    <div className="agent-md text-[13.5px] leading-[2]">
                      <Markdown>{turn.answer}</Markdown>
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
      <aside className="card-surface flex w-[336px] shrink-0 flex-col overflow-hidden">
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
              {t('agent.processingEmpty')}
            </div>
          )}
        </div>
      </aside>
    </div>
  );
}
