import { useTranslation } from 'react-i18next';
import { ColLabel, LiveDot, SectionTitle, Tag } from '../../components/ui';
import { IconAgent, IconArrowRight, IconCheck } from '../../components/icons';
import M3Notice from '../../components/M3Notice';

/**
 * 智能 Agent 助手 —— 原稿的「RAG 问答助手」。
 *
 * 它不只是问答：一屏里要同时容纳流式对话、执行进度、工具调用结果卡片、
 * HITL 交互卡片（agent 会主动提问并暂停等待）、引用 chip 与检索命中面板。
 * 对标 gogo-agent 的 ChatWindow + ProcessingPanel。
 *
 * M1 没有后端，下面是静态稿，用来把布局与交互形态定下来。
 */

const HITS = [
  { ref: 'STD-005', score: 0.912, bar: 91, tone: 'text-matsu', text: 'CLICK アクションの wait_strategy は CLICKABLE を必須とする。要素の存在確認だけでは…' },
  { ref: 'STD-004', score: 0.887, bar: 88, tone: 'text-matsu', text: 'SLEEP の使用を禁止する。固定時間待機は実行時間を不必要に伸ばし、環境差で不安定になる…' },
  { ref: '手册 §4.2', score: 0.741, bar: 74, tone: 'text-yamabuki', text: '待機戦略は NONE / PRESENCE / VISIBLE / CLICKABLE の四種。アサーション系は VISIBLE を…' },
  { ref: 'ATP-PAYMENT-0001', score: 0.612, bar: 61, tone: 'text-ink-4', text: '（存量案例）SLEEP 3 が残っている歴史的ステップ。移行対象としてマーク済み…' },
];

const TIMELINE = [
  { label: 'IntentRecognition', detail: 'CASE_AUTHORING', done: true },
  { label: 'retrieve_standards', detail: 'top-k 8 → rerank 3', done: true },
  { label: 'find_similar_cases', detail: 'ATP-CART-0004 / 0007', done: true },
  { label: 'draft_case', detail: 'AI_DRAFT · version 0', done: false },
];

export default function AgentPanel() {
  const { t } = useTranslation();

  return (
    <div className="scrollable h-full px-6 pt-5 pb-6">
      <M3Notice text={t('agent.m3Notice')} />

      <div className="flex h-[calc(100%-72px)] min-h-[560px] gap-5">
        {/* ---------- 对话 ---------- */}
        <section className="card-surface flex min-w-0 grow flex-col overflow-hidden">
          <div className="flex shrink-0 items-center gap-[11px] border-b border-line px-[22px] py-[15px]">
            <SectionTitle>{t('agent.title')}</SectionTitle>
            <Tag tone="bg-ai-soft text-ai">bge-m3 + bge-reranker-v2-m3</Tag>
            <div className="grow" />
            <span className="text-[11.5px] text-ink-4">
              {t('agent.corpus')} <span className="font-mono">atp-standards-v2</span>
            </span>
          </div>

          <div className="scrollable min-h-0 grow p-[22px]">
            <div className="mb-[22px] flex justify-end">
              <div className="max-w-[64%] rounded-[8px_8px_2px_8px] bg-rail px-[17px] py-[13px] text-[13.5px] leading-[1.85]">
                CLICK 步骤的 wait_strategy 该设成什么？还有为什么不能用 SLEEP？
              </div>
            </div>

            <div className="mb-[22px] flex gap-3">
              <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-shu-soft">
                <IconAgent size={15} className="text-shu" />
              </span>
              <div className="min-w-0 grow">
                {/* 执行进度 —— agent 正在做什么、调了哪个工具 */}
                <div className="mb-4 rounded-md border border-line bg-surface-2 px-4 py-3">
                  <ColLabel className="mb-2.5 block">{t('agent.processing')}</ColLabel>
                  {TIMELINE.map((step) => (
                    <div key={step.label} className="flex items-center gap-2.5 py-[5px]">
                      {step.done ? (
                        <IconCheck size={11} className="text-matsu" />
                      ) : (
                        <LiveDot className="bg-ai" size={7} />
                      )}
                      <span className="font-mono text-[11px] text-ink-2">{step.label}</span>
                      <span className="font-mono text-[10.5px] text-ink-4">{step.detail}</span>
                    </div>
                  ))}
                </div>

                <div className="text-[13.5px] leading-[2]">
                  <p className="m-0 mb-3.5">
                    <strong className="font-semibold">
                      CLICK 必须用 <span className="font-mono text-shu">CLICKABLE</span>。
                    </strong>
                    元素在 DOM 里存在，不代表能点 —— 遮罩层还没消失、动画还在跑的时候点击会被吃掉，所以只判可见性不够。保存时平台会自动把这个值补上。
                  </p>
                  <p className="m-0 mb-3.5">
                    <strong className="font-semibold">SLEEP 被规范全面禁止。</strong>
                    固定等待在慢的时候不够、在快的时候纯浪费。执行时间被拖长，而且失败会看起来像「时序问题」，根因就追不下去了。换成显式等待（
                    <span className="font-mono">PRESENCE / VISIBLE / CLICKABLE</span>）。
                  </p>
                  <p className="m-0">
                    存量案例里残留的 SLEEP 是历史原因，新保存的案例会被校验器以{' '}
                    <span className="font-mono text-shu">ERROR</span> 拦下。
                  </p>
                </div>

                <div className="mt-4 flex flex-wrap gap-[7px]">
                  {[
                    { ref: 'STD-005', label: 'CLICK 用 CLICKABLE 等待', tone: 'text-shu' },
                    { ref: 'STD-004', label: '禁止 SLEEP', tone: 'text-shu' },
                    { ref: '手册 §4.2', label: '等待策略怎么选', tone: 'text-ai' },
                  ].map((c) => (
                    <span
                      key={c.ref}
                      className="flex items-center gap-1.5 rounded-sm border border-line px-2.5 py-[5px] text-[11.5px] text-ink-2"
                    >
                      <span className={`font-mono text-[10px] ${c.tone}`}>{c.ref}</span>
                      {c.label}
                    </span>
                  ))}
                </div>

                {/* HITL —— agent 主动提问并暂停等待 */}
                <div className="mt-4 rounded-md border border-ai/30 bg-ai-soft px-4 py-3.5">
                  <div className="mb-2.5 flex items-center gap-2">
                    <span className="font-mono text-[9.5px] tracking-[.12em] text-ai">HITL</span>
                    <span className="text-[12px] text-ink-2">{t('agent.hitl')}</span>
                  </div>
                  <div className="mb-3 text-[12.5px] leading-[1.8] text-ink-2">
                    这条案例要落在 CART 模块下，priority 取 P1 还是 P0？P0 会进入每日回归套件。
                  </div>
                  <div className="flex gap-2">
                    {['P0', 'P1', '让我改改描述'].map((opt) => (
                      <span
                        key={opt}
                        className="cursor-not-allowed rounded-sm border border-line-2 bg-card px-3 py-1.5 text-[11.5px] text-ink-3"
                      >
                        {opt}
                      </span>
                    ))}
                  </div>
                </div>

                <div className="mt-4 flex items-center gap-4 font-mono text-[10.5px] text-ink-4">
                  <span>retrieved 8 → reranked 3</span>
                  <span>1.24 s</span>
                  <span>1,830 tokens</span>
                </div>
              </div>
            </div>
          </div>

          <div className="shrink-0 border-t border-line px-[22px] pt-3.5 pb-[18px]">
            <div className="flex items-center gap-[11px] rounded-lg border border-line bg-paper px-[15px] py-[11px]">
              <span className="grow text-[13px] text-ink-4">{t('agent.placeholder')}</span>
              <span className="flex h-[30px] w-[30px] items-center justify-center rounded-md bg-shu/35">
                <IconArrowRight size={15} className="text-white" strokeWidth={2} />
              </span>
            </div>
            <div className="mt-[11px] flex flex-wrap gap-2">
              {[t('agent.q1'), t('agent.q2'), t('agent.q3')].map((q) => (
                <span
                  key={q}
                  className="rounded-sm border border-line px-[11px] py-1.5 text-[11.5px] text-ink-2"
                >
                  {q}
                </span>
              ))}
            </div>
          </div>
        </section>

        {/* ---------- 检索命中 ---------- */}
        <aside className="card-surface flex w-[336px] shrink-0 flex-col overflow-hidden">
          <div className="shrink-0 border-b border-line px-[18px] py-[15px]">
            <span className="font-jp text-[13.5px] font-bold">{t('agent.retrieved')}</span>
            <span className="ml-2 font-mono text-[11px] text-ink-4">top-k = 8</span>
          </div>
          <div className="scrollable min-h-0 grow px-[18px] py-3.5">
            {HITS.map((h) => (
              <div key={h.ref} className="border-b border-line-3 py-3.5">
                <div className="mb-[7px] flex items-center gap-2">
                  <span className="font-mono text-[10.5px] text-shu">{h.ref}</span>
                  <div className="grow" />
                  <span className={`font-mono text-[10.5px] ${h.tone}`}>{h.score.toFixed(3)}</span>
                </div>
                <div className="text-[12px] leading-[1.8] text-ink-2">{h.text}</div>
                <div className="mt-[9px] h-0.5 overflow-hidden rounded-[1px] bg-line-5">
                  <div
                    className={`animate-bar h-full ${h.bar > 80 ? 'bg-matsu' : h.bar > 70 ? 'bg-yamabuki' : 'bg-line-2'}`}
                    style={{ width: `${h.bar}%` }}
                  />
                </div>
              </div>
            ))}
            <div className="pt-3.5 pb-1 text-center text-[11.5px] text-ink-4">
              {t('agent.dropped', { count: 4 })}
            </div>
          </div>
        </aside>
      </div>
    </div>
  );
}
