import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { AsyncBlock, ColLabel, SectionTitle, Tag } from '../../components/ui';
import { IconPlus } from '../../components/icons';
import { ApiError, useCommitDraft, useCreateDraft, useDraft, useModules, useSaveDraft } from '../../lib/queries';
import { findingsOf, DEMO_USER } from '../../lib/api';
import {
  ACTIONS, LOCATOR_TYPES, ON_FAILURES, PRIORITIES, WAIT_STRATEGIES,
  emptyStep, nextCaseCode, parseDraft, serializeDraft, waitStrategyFor,
} from '../../lib/draft';
import { caseStatusTone, isAssertion, severityTone, toneOf } from '../../lib/format';
import type {
  CaseStep, CaseType, DraftDocument, DraftView, Priority, ValidationFinding, ValidationResult,
} from '../../lib/types';

const STEP_COLS = '34px 132px 84px minmax(0,1fr) minmax(0,1fr) 108px 92px 30px';

const FIELD =
  'w-full rounded-sm border border-line bg-paper px-2 py-1.5 text-[12px] outline-none focus:border-line-2 disabled:bg-surface-2 disabled:text-ink-4';

/** 已提交的案例（DRAFT / ACTIVE / DEPRECATED）后端不再接受 PUT，编辑器整体只读 */
function isEditable(status: string | undefined): boolean {
  return status === 'AI_DRAFT';
}

function ValidationStrip({ result, extra }: { result: ValidationResult | null; extra: ValidationFinding[] }) {
  const { t } = useTranslation();
  const findings = [...(result?.findings ?? []), ...extra];
  if (!result && findings.length === 0) return null;

  return (
    <div className="shrink-0 border-b border-line bg-surface-2 px-6 py-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="mr-1 text-[10.5px] tracking-[.1em] text-ink-4">{t('cases.standardsCheck')}</span>
        {result && (
          <span className="font-mono text-[11px] text-ink-4">
            <span className={result.errorCount ? 'text-shu' : ''}>ERROR {result.errorCount}</span>
            {' / '}
            <span className={result.warnCount ? 'text-yamabuki' : ''}>WARN {result.warnCount}</span>
            {' / '}
            <span className={result.infoCount ? 'text-ai' : ''}>INFO {result.infoCount}</span>
          </span>
        )}
        {result?.passed && findings.length === 0 && (
          <Tag tone="bg-matsu-soft text-matsu">{t('cases.passed')}</Tag>
        )}
      </div>

      {findings.length > 0 && (
        <ul className="mt-2 flex list-none flex-col gap-1 p-0">
          {findings.map((f, i) => {
            const tone = severityTone[f.severity];
            return (
              <li key={`${f.std}-${i}`} className="flex items-start gap-2">
                <span className={`shrink-0 font-mono text-[10px] ${tone.fg}`}>{f.std}</span>
                <span className={`shrink-0 font-mono text-[9.5px] ${tone.fg}`}>{f.severity}</span>
                {/* seq 为 null（或 -1）表示整条案例级别的问题，不指向任何步骤 */}
                <span className="shrink-0 font-mono text-[10px] text-ink-5">
                  {f.seq === null || f.seq < 0 ? t('cases.caseLevel') : `#${f.seq}`}
                </span>
                <span className="grow text-[11.5px] leading-[1.7] text-ink-2">{f.message}</span>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

function StepRow({
  step, index, editable, onChange, onRemove,
}: {
  step: CaseStep;
  index: number;
  editable: boolean;
  onChange: (next: CaseStep) => void;
  onRemove: () => void;
}) {
  const { t } = useTranslation();
  const set = (patch: Partial<CaseStep>) => onChange({ ...step, ...patch });

  return (
    <div className="grid items-center gap-1 border-b border-line-3 py-1.5" style={{ gridTemplateColumns: STEP_COLS }}>
      <div className="font-mono text-[11px] text-ink-5">{index + 1}</div>

      <select
        className={`${FIELD} font-mono ${isAssertion(step.action) ? 'text-shu' : ''}`}
        value={step.action}
        disabled={!editable}
        onChange={(e) => {
          const action = e.target.value;
          // 换 action 时把规则能定的 wait_strategy 一起填好（STD-005 / STD-006）
          set({ action, wait_strategy: waitStrategyFor(action, step.wait_strategy) });
        }}
      >
        {ACTIONS.map((a) => (
          <option key={a} value={a}>{a}</option>
        ))}
      </select>

      <select
        className={`${FIELD} font-mono`}
        value={step.locator_type ?? ''}
        disabled={!editable}
        onChange={(e) => set({ locator_type: e.target.value || null })}
      >
        <option value="">—</option>
        {LOCATOR_TYPES.map((l) => (
          <option key={l} value={l}>{l}</option>
        ))}
      </select>

      <input
        className={`${FIELD} font-mono`}
        value={step.locator_value ?? ''}
        disabled={!editable}
        placeholder="—"
        onChange={(e) => set({ locator_value: e.target.value || null })}
      />

      {/* INPUT / EXPECTED 合成一列：断言类填 expected，其余填 input_data */}
      <input
        className={FIELD}
        value={(isAssertion(step.action) ? step.expected : step.input_data) ?? ''}
        disabled={!editable}
        placeholder="—"
        onChange={(e) =>
          set(isAssertion(step.action) ? { expected: e.target.value || null } : { input_data: e.target.value || null })
        }
      />

      <select
        className={`${FIELD} font-mono`}
        value={step.wait_strategy ?? 'NONE'}
        disabled={!editable}
        onChange={(e) => set({ wait_strategy: e.target.value })}
      >
        {WAIT_STRATEGIES.map((w) => (
          <option key={w} value={w}>{w}</option>
        ))}
      </select>

      <select
        className={`${FIELD} font-mono`}
        value={step.on_failure ?? 'ABORT'}
        disabled={!editable}
        onChange={(e) => set({ on_failure: e.target.value })}
      >
        {ON_FAILURES.map((o) => (
          <option key={o} value={o}>{o}</option>
        ))}
      </select>

      <button
        type="button"
        onClick={onRemove}
        disabled={!editable}
        title={t('editor.removeStep')}
        aria-label={t('editor.removeStep')}
        className="rounded-sm text-[15px] leading-none text-ink-5 hover:text-shu disabled:opacity-30"
      >
        ×
      </button>
    </div>
  );
}

export default function CaseEditor({
  mode, caseId, onClose, onCommitted,
}: {
  mode: 'new' | 'open';
  /** open 模式下要打开的案例；new 模式下忽略 */
  caseId?: string;
  onClose: () => void;
  onCommitted?: (caseId: string) => void;
}) {
  const { t } = useTranslation();
  const { data: modules } = useModules();

  const create = useCreateDraft();
  const save = useSaveDraft();
  const commit = useCommitDraft();

  // new 模式下 caseId 由前端生成，且**只生成一次** —— 它是幂等键，
  // 每次渲染换一个的话「重试」就会建出第二条空草稿
  const generated = useRef<string>(crypto.randomUUID());
  const id = mode === 'new' ? generated.current : (caseId ?? null);

  const remote = useDraft(mode === 'open' ? id : null);

  const [view, setView] = useState<DraftView | null>(null);
  const [doc, setDoc] = useState<DraftDocument | null>(null);
  const [caseType, setCaseType] = useState<CaseType>('PC_WEB');
  const [dirty, setDirty] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [committed, setCommitted] = useState(false);
  const started = useRef(false);

  const editable = isEditable(view?.status) && !committed;

  const adopt = (v: DraftView) => {
    setView(v);
    setDoc(parseDraft(v));
    setCaseType(v.caseType);
    setDirty(false);
    setConflict(false);
  };

  // new：进来就建草稿（幂等，重复调用返回已存在那份）
  useEffect(() => {
    if (mode !== 'new' || started.current) return;
    started.current = true;
    create.mutate(
      { caseId: generated.current, title: '', caseType: 'PC_WEB', createdBy: DEMO_USER },
      { onSuccess: adopt },
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode]);

  // open：读回库里那份
  useEffect(() => {
    if (mode === 'open' && remote.data) adopt(remote.data);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [remote.data]);

  const moduleCode = useMemo(
    () => modules?.find((m) => m.moduleId === doc?.module_id)?.moduleCode ?? null,
    [modules, doc?.module_id],
  );

  const patch = (p: Partial<DraftDocument>) => {
    setDoc((d) => (d ? { ...d, ...p } : d));
    setDirty(true);
  };

  const setSteps = (steps: CaseStep[]) => patch({ steps });

  const onPickModule = (moduleId: string) => {
    const code = modules?.find((m) => m.moduleId === moduleId)?.moduleCode;
    // 选了模块就把 case_code 顺手拼出来 —— 后端不自动生成，留空到 commit 才炸
    const case_code =
      code && !doc?.case_code ? nextCaseCode(code, []) : doc?.case_code ?? null;
    patch({ module_id: moduleId, case_code });
  };

  const doSave = () => {
    if (!id || !doc || !view) return;
    save.mutate(
      { caseId: id, draftJson: serializeDraft(doc), version: view.version },
      {
        onSuccess: adopt,
        onError: (e) => {
          if (e instanceof ApiError && e.isConflict) setConflict(true);
        },
      },
    );
  };

  const doCommit = () => {
    if (!id || !view) return;
    commit.mutate(
      { caseId: id, version: view.version },
      {
        onSuccess: (v) => {
          setView(v);
          setDoc(parseDraft(v));
          setCommitted(true);
          onCommitted?.(id);
        },
        onError: (e) => {
          if (e instanceof ApiError && e.isConflict) setConflict(true);
        },
      },
    );
  };

  const reload = () => {
    if (!id) return;
    void remote.refetch().then((r) => r.data && adopt(r.data));
  };

  const commitFindings = findingsOf(commit.error);
  const blocked = (view?.validation?.errorCount ?? 0) > 0 || commitFindings.length > 0;
  const missing = !doc?.title?.trim() ? t('editor.needTitle') : !doc?.module_id ? t('editor.needModule') : null;

  return (
    <div className="fixed inset-0 z-50 flex justify-end bg-ink/25" onClick={onClose}>
      <div
        className="flex h-full w-[1000px] max-w-full flex-col border-l border-line bg-card"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex shrink-0 items-center gap-3 border-b border-line px-6 py-4">
          <SectionTitle>{mode === 'new' ? t('editor.newTitle') : t('editor.editTitle')}</SectionTitle>
          {view && (
            <>
              <Tag tone={`${toneOf(caseStatusTone, view.status).bg} ${toneOf(caseStatusTone, view.status).fg}`}>
                {view.status}
              </Tag>
              <span className="font-mono text-[11px] text-ink-4">v{view.version}</span>
            </>
          )}
          {dirty && <span className="text-[11.5px] text-yamabuki">{t('editor.dirty')}</span>}
          <div className="grow" />
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-line px-3 py-1.5 text-[12px] text-ink-2 hover:bg-line-4"
          >
            {t('editor.close')}
          </button>
        </div>

        {/*
          409：别人在你之后改过了。给「重新载入」而不是自动重试 ——
          重试会拿你手上这份把别人的改动整个盖掉。
        */}
        {conflict && (
          <div className="shrink-0 border-b border-line bg-shu-soft px-6 py-3">
            <div className="text-[12.5px] text-shu">{t('editor.conflict')}</div>
            <div className="mt-1 text-[11.5px] leading-[1.8] text-ink-2">{t('editor.conflictHint')}</div>
            <button
              type="button"
              onClick={reload}
              className="mt-2 rounded-md border border-shu/40 bg-card px-3 py-1.5 text-[12px] text-shu hover:bg-shu-soft"
            >
              {t('editor.reload')}
            </button>
          </div>
        )}

        {!editable && view && (
          <div className="shrink-0 border-b border-line bg-surface-2 px-6 py-2.5 text-[11.5px] leading-[1.8] text-ink-3">
            {committed ? t('editor.committed') : t('editor.committedReadonly')}
          </div>
        )}

        <ValidationStrip result={view?.validation ?? null} extra={commitFindings} />

        <div className="scrollable min-h-0 grow px-6 py-5">
          <AsyncBlock
            isLoading={create.isPending || remote.isLoading}
            error={create.error ?? remote.error}
            onRetry={reload}
          >
            {doc && (
              <>
                <div className="mb-5 grid grid-cols-2 gap-x-5 gap-y-4 lg:grid-cols-3">
                  <label className="flex flex-col gap-1.5">
                    <ColLabel className="tracking-[.14em]">{t('editor.title')}</ColLabel>
                    <input
                      className={FIELD}
                      value={doc.title ?? ''}
                      disabled={!editable}
                      onChange={(e) => patch({ title: e.target.value })}
                    />
                  </label>

                  <label className="flex flex-col gap-1.5">
                    <ColLabel className="tracking-[.14em]">{t('editor.module')}</ColLabel>
                    <select
                      className={FIELD}
                      value={doc.module_id ?? ''}
                      disabled={!editable}
                      onChange={(e) => onPickModule(e.target.value)}
                    >
                      <option value="">{t('editor.modulePick')}</option>
                      {modules?.map((m) => (
                        <option key={m.moduleId} value={m.moduleId}>
                          {m.moduleId} · {m.moduleCode} — {m.moduleName}
                        </option>
                      ))}
                    </select>
                  </label>

                  <label className="flex flex-col gap-1.5">
                    <ColLabel className="tracking-[.14em]">{t('editor.caseCode')}</ColLabel>
                    <input
                      className={`${FIELD} font-mono`}
                      value={doc.case_code ?? ''}
                      disabled={!editable}
                      placeholder={moduleCode ? `ATP-${moduleCode}-0001` : 'ATP-…'}
                      onChange={(e) => patch({ case_code: e.target.value || null })}
                    />
                    <span className="text-[10.5px] leading-[1.7] text-ink-4">{t('editor.caseCodeHint')}</span>
                  </label>

                  <label className="flex flex-col gap-1.5">
                    <ColLabel className="tracking-[.14em]">{t('editor.priority')}</ColLabel>
                    <select
                      className={`${FIELD} font-mono`}
                      value={doc.priority ?? 'P2'}
                      disabled={!editable}
                      onChange={(e) => patch({ priority: e.target.value as Priority })}
                    >
                      {PRIORITIES.map((p) => (
                        <option key={p} value={p}>{p}</option>
                      ))}
                    </select>
                  </label>

                  <label className="flex flex-col gap-1.5">
                    <ColLabel className="tracking-[.14em]">{t('editor.caseType')}</ColLabel>
                    {/* caseType 在建草稿时定，之后 draftJson 里没有它的位置 */}
                    <input className={`${FIELD} font-mono`} value={caseType} disabled readOnly />
                  </label>

                  <label className="flex flex-col gap-1.5">
                    <ColLabel className="tracking-[.14em]">{t('editor.author')}</ColLabel>
                    <input
                      className={FIELD}
                      value={doc.author ?? ''}
                      disabled={!editable}
                      onChange={(e) => patch({ author: e.target.value || null })}
                    />
                  </label>

                  <label className="col-span-2 flex flex-col gap-1.5 lg:col-span-3">
                    <ColLabel className="tracking-[.14em]">{t('editor.precondition')}</ColLabel>
                    <input
                      className={FIELD}
                      value={doc.precondition ?? ''}
                      disabled={!editable}
                      onChange={(e) => patch({ precondition: e.target.value || null })}
                    />
                  </label>
                </div>

                <div className="mb-2 flex items-center gap-3">
                  <ColLabel className="tracking-[.14em]">{t('editor.steps')}</ColLabel>
                  <span className="font-mono text-[11px] text-ink-4">{doc.steps.length}</span>
                  <div className="grow" />
                  <button
                    type="button"
                    disabled={!editable}
                    onClick={() => setSteps([...doc.steps, emptyStep(doc.steps.length + 1)])}
                    className="flex items-center gap-1.5 rounded-sm border border-line bg-card px-2.5 py-1 text-[11.5px] text-ink-2 hover:bg-line-4 disabled:opacity-40"
                  >
                    <IconPlus size={11} />
                    {t('editor.addStep')}
                  </button>
                </div>

                <div className="grid gap-1 border-b border-line pb-1.5" style={{ gridTemplateColumns: STEP_COLS }}>
                  {['SEQ', 'ACTION', 'LOCATOR', 'VALUE', 'INPUT / EXPECTED', 'WAIT', 'ON FAIL', ''].map((h, i) => (
                    <ColLabel key={`${h}-${i}`}>{h}</ColLabel>
                  ))}
                </div>

                {doc.steps.map((s, i) => (
                  <StepRow
                    key={i}
                    step={s}
                    index={i}
                    editable={editable}
                    onChange={(next) => setSteps(doc.steps.map((x, j) => (j === i ? next : x)))}
                    onRemove={() => setSteps(doc.steps.filter((_, j) => j !== i))}
                  />
                ))}

                {doc.steps.length === 0 && (
                  <div className="py-8 text-center text-[12px] text-ink-4">{t('cases.noSteps')}</div>
                )}
              </>
            )}
          </AsyncBlock>
        </div>

        <div className="flex shrink-0 items-center gap-3 border-t border-line px-6 py-4">
          {blocked && <span className="text-[11.5px] text-shu">{t('editor.errorBlocks')}</span>}
          {!blocked && missing && editable && <span className="text-[11.5px] text-yamabuki">{missing}</span>}
          {save.isSuccess && !dirty && !save.isPending && view && (
            <span className="text-[11.5px] text-matsu">{t('editor.saved', { version: view.version })}</span>
          )}
          <div className="grow" />
          <button
            type="button"
            disabled={!editable || save.isPending || !dirty}
            onClick={doSave}
            className="rounded-md border border-line bg-card px-3.5 py-[7px] text-[12.5px] text-ink-2 hover:bg-line-4 disabled:opacity-40"
          >
            {save.isPending ? t('editor.saving') : t('editor.save')}
          </button>
          <button
            type="button"
            disabled={!editable || commit.isPending || dirty || Boolean(missing)}
            title={dirty ? t('editor.dirty') : undefined}
            onClick={doCommit}
            className="rounded-md bg-shu px-4 py-[7px] text-[12.5px] text-white hover:bg-shu-hover disabled:cursor-not-allowed disabled:opacity-45"
          >
            {commit.isPending ? t('editor.committing') : t('editor.commit')}
          </button>
        </div>
      </div>
    </div>
  );
}
