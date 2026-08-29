import { useTranslation } from 'react-i18next';
import { ColLabel, LiveDot, SectionTitle, Tag } from '../../components/ui';
import { IconPlus } from '../../components/icons';
import M3Notice from '../../components/M3Notice';

/**
 * 数据集中心 —— M1 没有后端，M3 才做。
 * 表已经建好（rag_corpus / rag_document / rag_eval_run），向量存储走 pgvector
 * （AgentScope 的 PgVectorStore，与业务表同库），embedding 走本地 TEI 的 bge-m3。
 */

const CORPORA = [
  { name: 'atp-standards-v2', desc: '内部规范 8 条 + 平台手册', docs: 14, chunks: '199', embedding: 'bge-m3', status: 'READY' },
  { name: 'atp-legacy-cases', desc: '存量案例说明文（日中英混杂）', docs: 80, chunks: '412', embedding: 'bge-m3', status: 'READY' },
  { name: 'atp-runbook-ops', desc: '执行基础设施的运维手册', docs: 23, chunks: '—', embedding: 'bge-m3', status: 'INDEXING' },
  { name: 'atp-standards-v1', desc: '旧版（已被 v2 取代）', docs: 11, chunks: '168', embedding: 'm3e-base', status: 'ARCHIVED' },
] as const;

const EVAL = [
  { metric: 'recall@1', value: 0.62, tone: 'bg-ai' },
  { metric: 'recall@3', value: 0.79, tone: 'bg-ai' },
  { metric: 'recall@5', value: 0.86, tone: 'bg-ai' },
  { metric: 'MRR', value: 0.71, tone: 'bg-shu' },
] as const;

const STATUS_TONE: Record<string, string> = {
  READY: 'bg-matsu-soft text-matsu',
  INDEXING: 'bg-ai-soft text-ai',
  ARCHIVED: 'bg-line-4 text-ink-3',
};

const COLS = 'minmax(0,1fr) 92px 96px 116px 108px';

export default function DatasetsPanel() {
  const { t } = useTranslation();

  return (
    <div className="scrollable h-full px-6 pt-5 pb-6">
      <M3Notice text={t('datasets.m3Notice')} />

      <div className="mb-[18px] grid gap-[18px] xl:grid-cols-[minmax(0,1fr)_380px]">
        {/* ---------- 语料集 ---------- */}
        <div className="card-surface overflow-hidden">
          <div className="flex items-center border-b border-line px-[22px] py-[15px]">
            <SectionTitle>{t('datasets.corpora')}</SectionTitle>
            <div className="grow" />
            <span className="flex cursor-not-allowed items-center gap-1.5 rounded-md border border-dashed border-line-2 px-3 py-1.5 text-[12px] text-ink-5">
              <IconPlus size={12} />
              {t('datasets.ingest')}
            </span>
          </div>

          <div className="grid border-b border-line bg-surface-2 px-[22px]" style={{ gridTemplateColumns: COLS }}>
            {[t('datasets.colName'), t('datasets.colDocs'), t('datasets.colChunks'), t('datasets.colEmbedding'), t('datasets.colStatus')].map(
              (h, i, arr) => (
                <div key={h} className={`py-2.5 ${i === 0 ? 'pr-2' : i === arr.length - 1 ? 'pl-2' : 'px-2'}`}>
                  <ColLabel>{h}</ColLabel>
                </div>
              ),
            )}
          </div>

          {CORPORA.map((c) => (
            <div
              key={c.name}
              className="grid items-center border-b border-line-3 px-[22px] transition-colors last:border-b-0 hover:bg-surface-2"
              style={{ gridTemplateColumns: COLS }}
            >
              <div className="min-w-0 py-3 pr-2">
                <div className={`font-mono text-[12.5px] ${c.status === 'ARCHIVED' ? 'text-ink-3' : ''}`}>
                  {c.name}
                </div>
                <div className="mt-[3px] text-[11px] text-ink-4">{c.desc}</div>
              </div>
              <div className="px-2 py-3 font-mono text-[12px] text-ink-2">{c.docs}</div>
              <div className="px-2 py-3 font-mono text-[12px] text-ink-2">{c.chunks}</div>
              <div className="px-2 py-3 font-mono text-[11px] text-ink-3">{c.embedding}</div>
              <div className="py-3 pl-2">
                <Tag tone={STATUS_TONE[c.status]}>
                  {c.status === 'INDEXING' && <LiveDot className="bg-ai" size={5} />}
                  {c.status}
                </Tag>
              </div>
            </div>
          ))}
        </div>

        {/* ---------- 检索评估 ---------- */}
        <div className="card-surface px-[22px] py-5">
          <div className="mb-1 flex items-center gap-2.5">
            <SectionTitle>{t('datasets.eval')}</SectionTitle>
            <span className="font-mono text-[10.5px] text-ink-4">atp-qa-eval-60</span>
          </div>
          <div className="mb-5 text-[11.5px] text-ink-4">
            {t('datasets.evalSub', { count: 60, date: '2026-08-25' })}
          </div>

          {EVAL.map((e) => (
            <div key={e.metric} className="mb-4">
              <div className="mb-[7px] flex items-baseline">
                <span className="grow font-mono text-[11.5px] text-ink-2">{e.metric}</span>
                <span className="font-mono text-[13px]">{e.value.toFixed(2)}</span>
              </div>
              <div className="h-[5px] overflow-hidden rounded-[3px] bg-line-5">
                <div className={`animate-bar h-full ${e.tone}`} style={{ width: `${e.value * 100}%` }} />
              </div>
            </div>
          ))}

          <div className="mt-[22px] rounded-md border border-line-4 bg-surface-2 px-[15px] py-3 text-[11.5px] leading-[1.85] text-ink-2">
            相比 v1（m3e-base），recall@5 提升 <span className="font-mono text-matsu">+0.14</span>
            。主要增量来自「日文提问 → 中文文档」的跨语言命中。
          </div>
        </div>
      </div>

      {/* ---------- 分块设置 ---------- */}
      <div className="card-surface px-[22px] py-5">
        <div className="font-jp mb-4 text-[14px] font-bold">{t('datasets.chunking')}</div>

        <div className="grid grid-cols-2 border-t border-line-4 md:grid-cols-4">
          {[
            { label: t('datasets.strategy'), value: t('datasets.strategyValue'), mono: false },
            { label: t('datasets.embedding'), value: 'bge-m3 · 1024d · TEI', mono: true },
            { label: t('datasets.reranker'), value: 'bge-reranker-v2-m3', mono: true },
            { label: t('datasets.vectorStore'), value: 'pgvector', mono: true },
          ].map((f, i, arr) => (
            <div
              key={f.label}
              className={`pt-3.5 pb-1 ${i === 0 ? 'pr-[18px]' : 'px-[18px]'} ${
                i < arr.length - 1 ? 'border-r border-line-4' : ''
              }`}
            >
              <ColLabel className="tracking-[.14em]">{f.label}</ColLabel>
              <div className={`mt-1.5 text-[13px] ${f.mono ? 'font-mono' : ''}`}>{f.value}</div>
            </div>
          ))}
        </div>

        {/*
          稿子这里原本是 CHUNK SIZE 512 / OVERLAP 64。删掉了：
          主策略下切块边界由标题层级决定，这两个值只在 baseline 那一行真正生效。
          把死参数摆在界面上，等于给自己埋一个当场被问穿的坑。
        */}
        <div className="mt-4 rounded-md border border-line-4 bg-surface-2 px-[15px] py-3 text-[11.5px] leading-[1.85] text-ink-2">
          {t('datasets.chunkNote')}
        </div>
      </div>
    </div>
  );
}
