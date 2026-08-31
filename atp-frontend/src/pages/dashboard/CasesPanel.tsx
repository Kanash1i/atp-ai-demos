import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { AsyncBlock, ColLabel, NotReadyButton, SectionTitle, Tag } from '../../components/ui';
import { IconCheck, IconChevron, IconPlay, IconPlus } from '../../components/icons';
import { useNavigate } from 'react-router-dom';
import { useCaseDetail, useDispatch, useProjects, useProjectTree } from '../../lib/queries';
import { isRunnableModule } from '../../lib/runnable';
import { DEMO_USER } from '../../lib/api';
import CaseEditor from './CaseEditor';
import {
  caseStatusDot, caseStatusTone, dash, isAssertion, priorityTone, severityTone, toneOf,
} from '../../lib/format';
import type { CaseDetail, Severity, TreeModule, ValidationFinding } from '../../lib/types';

/** 步骤表的列宽，表头与每一行必须用同一份，否则错位 */
const STEP_COLS = '42px 116px 92px minmax(0,1fr) 150px 108px 84px';

/* ============================================================
   左：案例树
   ============================================================ */

function CaseTree({
  modules,
  selected,
  onSelect,
}: {
  modules: TreeModule[];
  selected: string | null;
  onSelect: (caseId: string) => void;
}) {
  // 树是一次全取的，展开/收起是纯客户端行为
  const [open, setOpen] = useState<Record<string, boolean>>(() =>
    Object.fromEntries(modules.map((m, i) => [m.moduleId, i === 0])),
  );

  useEffect(() => {
    setOpen(Object.fromEntries(modules.map((m, i) => [m.moduleId, i === 0])));
  }, [modules]);

  return (
    <>
      {modules.map((m) => (
        <div key={m.moduleId}>
          <button
            type="button"
            onClick={() => setOpen((o) => ({ ...o, [m.moduleId]: !o[m.moduleId] }))}
            className="flex w-full items-center gap-2 rounded-sm px-2.5 py-2 text-left transition-colors hover:bg-line-4"
          >
            <IconChevron
              size={11}
              className="shrink-0 text-ink-4 transition-transform duration-200"
              style={{ transform: `rotate(${open[m.moduleId] ? 90 : 0}deg)` }}
            />
            <span className="font-mono text-[10px] text-ink-5">{m.moduleId}</span>
            {/* 模块名本身就是日中双语串，不参与 i18n */}
            <span className="grow truncate text-[13px]">{m.moduleName}</span>
            <span className="font-mono text-[11px] text-ink-4">{m.caseCount}</span>
          </button>

          {open[m.moduleId] && (
            <div>
              {m.cases.map((c) => {
                const active = c.caseId === selected;
                return (
                  <button
                    key={c.caseId}
                    type="button"
                    onClick={() => onSelect(c.caseId)}
                    className={[
                      'flex w-full items-center gap-2 rounded-sm py-[7px] pr-2.5 pl-[30px] text-left transition-colors',
                      active ? 'bg-shu-soft shadow-[inset_2px_0_0_var(--color-shu)]' : 'hover:bg-line-4',
                    ].join(' ')}
                  >
                    <span className={`h-[5px] w-[5px] shrink-0 rounded-full ${caseStatusDot[c.status]}`} />
                    {/* seqNo 是 case_code 后四位；⚠️ 存量有 ATP-ADMIN-0011-V2 这种，不一定是 4 位数字 */}
                    <span className="shrink-0 font-mono text-[10.5px] text-ink-5">{c.seqNo}</span>
                    <span className="grow truncate text-[12.5px] text-ink-2">{c.title}</span>
                    <span className={`shrink-0 font-mono text-[9.5px] ${toneOf(priorityTone, c.priority).fg}`}>
                      {c.priority}
                    </span>
                  </button>
                );
              })}
            </div>
          )}
        </div>
      ))}
    </>
  );
}

/* ============================================================
   右：案例详情
   ============================================================ */

/** violatedCodes 上色要看那条 STD 实际报出来的最高严重度 */
function severityOf(findings: ValidationFinding[], std: string): Severity {
  const hits = findings.filter((f) => f.std === std);
  if (hits.some((f) => f.severity === 'ERROR')) return 'ERROR';
  if (hits.some((f) => f.severity === 'WARN')) return 'WARN';
  return 'INFO';
}

function ValidationBar({
  detail,
  onJump,
}: {
  detail: CaseDetail;
  onJump: (seq: number | null) => void;
}) {
  const { t } = useTranslation();
  const { validation: v } = detail;

  return (
    <div className="shrink-0 border-b border-line bg-surface-2 px-[22px] py-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="mr-1 text-[10.5px] tracking-[.1em] text-ink-4">{t('cases.standardsCheck')}</span>

        {v.violatedCodes.length === 0 ? (
          <Tag tone="bg-matsu-soft text-matsu">
            <IconCheck size={10} />
            {t('cases.passed')}
          </Tag>
        ) : (
          v.violatedCodes.map((code) => {
            const tone = severityTone[severityOf(v.findings, code)];
            return (
              <Tag key={code} tone={`${tone.bg} ${tone.fg}`}>
                {code}
              </Tag>
            );
          })
        )}

        <div className="grow" />

        <span className="font-mono text-[11px] text-ink-4">
          <span className={v.errorCount ? 'text-shu' : ''}>ERROR {v.errorCount}</span>
          {' / '}
          <span className={v.warnCount ? 'text-yamabuki' : ''}>WARN {v.warnCount}</span>
          {' / '}
          <span className={v.infoCount ? 'text-ai' : ''}>INFO {v.infoCount}</span>
        </span>
      </div>

      {v.findings.length > 0 && (
        <ul className="mt-2.5 flex list-none flex-col gap-1.5 p-0">
          {v.findings.map((f, i) => {
            const tone = severityTone[f.severity];
            return (
              <li key={`${f.std}-${i}`}>
                <button
                  type="button"
                  onClick={() => onJump(f.seq)}
                  className="flex w-full items-start gap-2 rounded-sm px-1.5 py-1 text-left transition-colors hover:bg-line-4"
                >
                  <span className={`shrink-0 font-mono text-[10px] ${tone.fg}`}>{f.std}</span>
                  <span className={`shrink-0 font-mono text-[9.5px] ${tone.fg}`}>{f.severity}</span>
                  <span className="shrink-0 font-mono text-[10px] text-ink-5">
                    {f.seq === null ? t('cases.caseLevel') : `#${f.seq}`}
                  </span>
                  {/* message 是后端生成的整句中文判断理由 —— 领域内容，不参与 i18n */}
                  <span className="grow text-[11.5px] leading-[1.7] text-ink-2">{f.message}</span>
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

/**
 * 单条案例的「执行」。
 *
 * 只有 LOGIN / CART / ORDER 有 mock 页面，其余模块派发出去必然超时失败 ——
 * 那种按钮点了只会浪费一次演示，所以直接禁掉并说明原因，而不是让人踩。
 */
function RunButton({ detail }: { detail: CaseDetail }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const dispatch = useDispatch();
  const runnable = isRunnableModule(detail.moduleCode);

  if (!runnable) {
    return (
      <button
        type="button"
        disabled
        title={t('runs.notRunnable')}
        className="flex cursor-not-allowed items-center gap-[6px] rounded-md border-none bg-shu/35 px-[15px] py-[7px] text-[12.5px] text-white"
      >
        <IconPlay size={12} />
        {t('cases.run')}
      </button>
    );
  }

  return (
    <button
      type="button"
      disabled={dispatch.isPending}
      onClick={() =>
        dispatch.mutate(
          {
            projectId: detail.projectId,
            caseIds: [detail.caseId],
            browser: 'CHROME',
            suiteName: detail.caseCode,
            trigger: 'MANUAL',
            createdBy: DEMO_USER,
          },
          { onSuccess: () => navigate('/dashboard/runs') },
        )
      }
      className="flex items-center gap-[6px] rounded-md bg-shu px-[15px] py-[7px] text-[12.5px] text-white transition-colors hover:bg-shu-hover disabled:opacity-50"
    >
      <IconPlay size={12} />
      {dispatch.isPending ? t('runs.dispatchSending') : t('cases.run')}
    </button>
  );
}

function CaseDetailPanel({ caseId, onEdit }: { caseId: string | null; onEdit: (id: string) => void }) {
  const { t } = useTranslation();
  const { data, isLoading, error, refetch } = useCaseDetail(caseId ?? undefined);
  const [highlight, setHighlight] = useState<number | null>(null);

  useEffect(() => setHighlight(null), [caseId]);

  if (!caseId) {
    return (
      <section className="card-surface flex min-w-0 grow flex-col items-center justify-center">
        <div className="text-[13.5px] text-ink-3">{t('cases.pickOne')}</div>
        <div className="mt-2 text-[11.5px] text-ink-4">{t('cases.pickOneHint')}</div>
      </section>
    );
  }

  return (
    <section className="card-surface flex min-w-0 grow flex-col overflow-hidden">
      <AsyncBlock isLoading={isLoading} error={error} onRetry={() => void refetch()}>
        {data && (
          <>
            <div className="shrink-0 border-b border-line px-[22px] pt-[18px] pb-4">
              <div className="mb-2.5 flex flex-wrap items-center gap-2.5">
                <span className="font-mono text-[12.5px] tracking-[.04em] text-shu">{data.caseCode}</span>
                <Tag tone={`${toneOf(caseStatusTone, data.status).bg} ${toneOf(caseStatusTone, data.status).fg}`}>
                  {data.status}
                </Tag>
                <Tag tone={`${toneOf(priorityTone, data.priority).bg} ${toneOf(priorityTone, data.priority).fg}`}>
                  {data.priority}
                </Tag>
                <div className="grow" />
                {/*
                  写侧只对 AI_DRAFT 开放。案例一旦 commit 落地，后端就不再接受 PUT
                  （409「案例已经提交过了，本次更新不予执行」）—— 库里 80 条存量全是
                  已落地状态，所以这个按钮在它们身上是禁用的，而不是点了才报错。
                */}
                {data.status === 'AI_DRAFT' ? (
                  <button
                    type="button"
                    onClick={() => onEdit(data.caseId)}
                    className="rounded-md border border-line bg-card px-[13px] py-[7px] text-[12.5px] text-ink-2 transition-colors hover:bg-line-4"
                  >
                    {t('cases.edit')}
                  </button>
                ) : (
                  <button
                    type="button"
                    disabled
                    title={t('cases.editCommitted')}
                    className="cursor-not-allowed rounded-md border border-dashed border-line-2 bg-card px-[13px] py-[7px] text-[12.5px] text-ink-5"
                  >
                    {t('cases.edit')}
                  </button>
                )}
                {/* 「案例变更 → 生成审批单」这一步后端还没接（审批**决策**接口早就有） */}
                <NotReadyButton>{t('cases.requestApproval')}</NotReadyButton>
                <RunButton detail={data} />
              </div>

              {/* 案例标题来自库，不翻译 */}
              <h2 className="font-jp m-0 mb-4 text-[20px] font-bold">{data.title}</h2>

              {/*
                稿子这里原本是 BROWSER / TIMEOUT。两个字段在数据模型里不存在，也不会加：
                browser 是执行参数（同一条案例本该能在 Chrome 和 Firefox 各跑一遍），
                timeout_sec 没有消费方（执行器按步骤各自的 wait_timeout_sec 走）。
                换成 CASE TYPE 与 VERSION（乐观锁版本号，编辑时要带回来）。
              */}
              <div className="grid grid-cols-2 border-t border-line-4 md:grid-cols-5">
                {[
                  { label: t('cases.module'), value: `${data.moduleId} · ${data.moduleCode}` },
                  { label: t('cases.author'), value: dash(data.author) },
                  { label: t('cases.typeCol'), value: data.caseType },
                  { label: t('cases.version'), value: `v${data.version}`, mono: true },
                  { label: t('cases.updated'), value: data.updatedAt, mono: true },
                ].map((f, i, arr) => (
                  <div
                    key={f.label}
                    className={`pt-3 pb-0.5 ${i === 0 ? 'pr-4' : 'px-4'} ${i < arr.length - 1 ? 'border-r border-line-4' : ''}`}
                  >
                    <ColLabel className="tracking-[.14em]">{f.label}</ColLabel>
                    <div className={`mt-[5px] text-[12.5px] ${f.mono ? 'font-mono' : ''}`}>{f.value}</div>
                  </div>
                ))}
              </div>
            </div>

            <ValidationBar detail={data} onJump={setHighlight} />

            <div
              className="grid shrink-0 border-b border-line bg-card px-[22px]"
              style={{ gridTemplateColumns: STEP_COLS }}
            >
              {[
                t('cases.colSeq'), t('cases.colAction'), t('cases.colLocator'), t('cases.colValue'),
                t('cases.colInput'), t('cases.colWait'), t('cases.colOnFail'),
              ].map((h, i, arr) => (
                <div key={h} className={`py-[11px] ${i === 0 ? 'pr-1.5' : i === arr.length - 1 ? 'pl-2' : 'px-2'}`}>
                  <ColLabel>{h}</ColLabel>
                </div>
              ))}
            </div>

            <div className="scrollable min-h-0 grow px-[22px] pb-4">
              {data.steps.length === 0 && (
                <div className="py-10 text-center text-[12.5px] text-ink-4">{t('cases.noSteps')}</div>
              )}
              {data.steps.map((s) => (
                <div
                  key={s.seq}
                  className={[
                    'grid items-center border-b border-line-3 transition-colors',
                    highlight === s.seq ? 'bg-shu-soft' : 'hover:bg-surface-2',
                  ].join(' ')}
                  style={{ gridTemplateColumns: STEP_COLS }}
                >
                  <div className="py-2.5 pr-1.5 font-mono text-[11.5px] text-ink-5">{s.seq}</div>
                  <div className="px-2 py-2.5">
                    <span
                      className={`rounded-xs px-[7px] py-[3px] font-mono text-[10.5px] ${
                        isAssertion(s.action) ? 'bg-shu-soft text-shu' : 'bg-line-4 text-ink-2'
                      }`}
                    >
                      {s.action}
                    </span>
                  </div>
                  <div className="px-2 py-2.5 font-mono text-[11px] text-ink-3">{dash(s.locator_type)}</div>
                  <div className="min-w-0 truncate px-2 py-2.5 font-mono text-[11px] text-ink-2">
                    {dash(s.locator_value)}
                  </div>
                  <div className="min-w-0 truncate px-2 py-2.5 text-[11.5px] text-ink-2">
                    {dash(s.input_data ?? s.expected)}
                  </div>
                  <div
                    className={`px-2 py-2.5 font-mono text-[10.5px] ${
                      s.wait_strategy && s.wait_strategy !== 'NONE' ? 'text-matsu' : 'text-ink-4'
                    }`}
                  >
                    {dash(s.wait_strategy)}
                  </div>
                  <div
                    className={`py-2.5 pl-2 font-mono text-[10.5px] ${
                      s.on_failure === 'CONTINUE' ? 'text-ai' : 'text-ink-4'
                    }`}
                  >
                    {dash(s.on_failure)}
                  </div>
                </div>
              ))}
            </div>
          </>
        )}
      </AsyncBlock>
    </section>
  );
}

/* ============================================================
   面板
   ============================================================ */

export default function CasesPanel() {
  const { t } = useTranslation();
  const { data: projects, isLoading: pLoading, error: pError, refetch } = useProjects();
  const [projectId, setProjectId] = useState<string | null>(null);
  const [caseId, setCaseId] = useState<string | null>(null);
  const [editor, setEditor] = useState<{ mode: 'new' | 'open'; caseId?: string } | null>(null);

  const activeProject = projectId ?? projects?.[0]?.projectId ?? null;
  const { data: modules, isLoading: tLoading, error: tError } = useProjectTree(activeProject ?? undefined);

  // 换项目时清掉选中的案例，避免详情和树对不上
  useEffect(() => setCaseId(null), [activeProject]);

  const firstCase = useMemo(
    () => modules?.find((m) => m.cases.length > 0)?.cases[0]?.caseId ?? null,
    [modules],
  );
  const selected = caseId ?? firstCase;

  return (
    <div className="flex h-full gap-5 overflow-hidden p-5 px-6">
      <section className="card-surface flex w-[356px] shrink-0 flex-col overflow-hidden">
        <div className="shrink-0 border-b border-line px-4 pt-4 pb-3.5">
          <div className="mb-3 flex items-center justify-between">
            <SectionTitle>{t('cases.tree')}</SectionTitle>
            <button
              type="button"
              onClick={() => setEditor({ mode: 'new' })}
              className="flex items-center gap-1.5 rounded-sm border border-line bg-card px-[9px] py-[5px] text-[11.5px] text-ink-2 transition-colors hover:bg-line-4"
            >
              <IconPlus size={12} />
              {t('cases.new')}
            </button>
          </div>

          <ColLabel className="mb-2 block tracking-[.18em]">{t('cases.project')}</ColLabel>
          <div className="flex flex-wrap gap-1.5">
            {projects?.map((p) => {
              const active = p.projectId === activeProject;
              return (
                <button
                  key={p.projectId}
                  type="button"
                  onClick={() => setProjectId(p.projectId)}
                  title={p.projectName}
                  className={[
                    'max-w-[150px] truncate rounded-sm border px-[11px] py-1.5 text-[11.5px] transition-colors',
                    active
                      ? 'border-ink bg-ink text-paper'
                      : 'border-line bg-card text-ink-2 hover:bg-line-4',
                  ].join(' ')}
                >
                  {/* 项目名是日中双语串，原样显示 */}
                  {p.projectName}
                </button>
              );
            })}
          </div>
        </div>

        <div className="scrollable min-h-0 grow p-2 pb-4">
          <AsyncBlock
            isLoading={pLoading || tLoading}
            error={pError ?? tError}
            isEmpty={!modules?.length}
            onRetry={() => void refetch()}
          >
            {modules && <CaseTree modules={modules} selected={selected} onSelect={setCaseId} />}
          </AsyncBlock>
        </div>
      </section>

      <CaseDetailPanel caseId={selected} onEdit={(id) => setEditor({ mode: 'open', caseId: id })} />

      {editor && (
        <CaseEditor
          mode={editor.mode}
          caseId={editor.caseId}
          onClose={() => setEditor(null)}
          onCommitted={(id) => setCaseId(id)}
        />
      )}
    </div>
  );
}
