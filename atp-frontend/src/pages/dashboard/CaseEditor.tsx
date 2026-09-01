import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { AsyncBlock, ColLabel, SectionTitle, Tag } from '../../components/ui';
import { IconPlus } from '../../components/icons';
import {
  useCommitDraft, useCreateDraft, useDraft, useModules, useNextCaseCode, useSaveDraft,
} from '../../lib/queries';
import {
  findingsOf, isDuplicateCaseCode, isStateConflict, isVersionConflict, missingFieldsOf, DEMO_USER,
} from '../../lib/api';
import {
  ACTIONS, LOCATOR_TYPES, ON_FAILURES, PRIORITIES, WAIT_STRATEGIES,
  emptyStep, parseDraft, serializeDraft, waitStrategyFor,
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
  const nextCode = useNextCaseCode();

  // new 模式下 caseId 由前端生成，且**只生成一次** —— 它是幂等键，
  // 每次渲染换一个的话「重试」就会建出第二条空草稿
  const generated = useRef<string>(crypto.randomUUID());
  const id = mode === 'new' ? generated.current : (caseId ?? null);

  const remote = useDraft(mode === 'open' ? id : null);

  /*
   * 保存反馈以 **version 为准**，时间戳只是补充：
   * 同一分钟内保存两次，时间戳看不出变化，而 version 每次必跳。
   *
   * editUpdatedAt 来自 tc_step（草稿的最后保存时间），不是 tc_case 的 updatedAt ——
   * 后者是案例本身的最后变更，编辑草稿根本不动它，拿它显示「最后修改」的话，
   * 用户改完点保存会看到时间纹丝不动。
   */

  const [view, setView] = useState<DraftView | null>(null);
  const [doc, setDoc] = useState<DraftDocument | null>(null);
  const [caseType, setCaseType] = useState<CaseType>('PC_WEB');
  const [dirty, setDirty] = useState(false);
  /**
   * 两种 409 的处置**完全相反**，所以不能合成一个布尔：
   * version = 内容被别人改过，重新载入有意义；
   * state   = 案例已提交，重新载入之后状态还是已提交的 —— 给重试按钮等于把人放进死循环。
   */
  const [conflict, setConflict] = useState<'version' | 'state' | null>(null);
  const [committed, setCommitted] = useState(false);
  const started = useRef(false);

  const editable = isEditable(view?.status) && !committed;

  const adopt = (v: DraftView) => {
    setView(v);
    setDoc(parseDraft(v));
    setCaseType(v.caseType);
    setDirty(false);
    setConflict(null);
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
    patch({ module_id: moduleId });
    // 选了模块就顺手取号 —— 后端不自动生成 case_code，留空到 commit 才 422。
    // 取号走后端（agent 的 next_case_code 工具调的是同一份实现），不在前端自己推：
    // 「已有条数 +1」和「最大序号 +1」在删过案例之后就不一样了，编号是单调的、计数不是。
    if (!doc?.case_code) {
      nextCode.mutate(moduleId, {
        onSuccess: (r) => patch({ case_code: r.caseCode }),
      });
    }
  };

  const doSave = () => {
    if (!id || !doc || !view) return;
    save.mutate(
      { caseId: id, draftJson: serializeDraft(doc), version: view.version },
      {
        onSuccess: adopt,
        onError: (e) => {
          // state 先判：isVersionConflict 在没有 type 的老后端上会兜住所有 409
          if (isStateConflict(e)) setConflict('state');
          else if (isVersionConflict(e)) setConflict('version');
        },
      },
    );
  };

  const finish = (v: DraftView) => {
    setView(v);
    setDoc(parseDraft(v));
    setCommitted(true);
    onCommitted?.(id!);
  };

  /**
   * 提交。撞号时自动换一个号重来一次。
   *
   * 取号接口不是原子的 —— 两个人同时新建同一模块的案例会拿到同一个号，
   * 后提交的被 uk_case_code 拦下。这不是数据写坏了，是号被抢了，
   * 所以重取一个号、重存、重提交是安全的；只做一次，避免真出问题时死循环。
   */
  const doCommit = (retried = false) => {
    if (!id || !view || !doc) return;
    commit.mutate(
      { caseId: id, version: view.version },
      {
        onSuccess: finish,
        onError: (e) => {
          // state 先判，理由同上
          if (isStateConflict(e)) {
            setConflict('state');
            return;
          }
          if (isVersionConflict(e)) {
            setConflict('version');
            return;
          }
          if (!retried && isDuplicateCaseCode(e) && doc.module_id) {
            nextCode.mutate(doc.module_id, {
              onSuccess: (r) => {
                const retryDoc = { ...doc, case_code: r.caseCode };
                setDoc(retryDoc);
                save.mutate(
                  { caseId: id, draftJson: serializeDraft(retryDoc), version: view.version },
                  {
                    onSuccess: (v2) => {
                      setView(v2);
                      commit.mutate(
                        { caseId: id, version: v2.version },
                        { onSuccess: finish },
                      );
                    },
                  },
                );
              },
            });
          }
        },
      },
    );
  };

  const reload = () => {
    if (!id) return;
    void remote.refetch().then((r) => r.data && adopt(r.data));
  };

  const commitFindings = findingsOf(commit.error);
  const missingFields = missingFieldsOf(commit.error);
  const duplicateCode = isDuplicateCaseCode(commit.error);
  // ⚠️ 只有 ERROR 拦人。warnCount > 0 且 passed: true 是正常状态，不是数据错乱 ——
  // ERROR 清零不代表写得好，但用户看不到 WARN 就不会去改，所以 WARN 照显示、不阻断
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
          {view?.editUpdatedAt && (
            <span className="font-mono text-[10.5px] text-ink-4">{view.editUpdatedAt}</span>
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
          409 分两种，按 RFC 7807 的 type 判，不匹配 detail 文案。
          version：别人在你之后改过了 —— 给「重新载入」而不是自动重试，
                   重试会拿你手上这份把别人的改动整个盖掉。
          state  ：案例已经提交了 —— **不给重试按钮**。重新载入之后状态还是已提交的，
                   给了只会让人在一个永远失败的循环里打转。
        */}
        {conflict === 'version' && (
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

        {conflict === 'state' && (
          <div className="shrink-0 border-b border-line bg-shu-soft px-6 py-3">
            <div className="text-[12.5px] text-shu">{t('editor.stateConflict')}</div>
            <div className="mt-1 text-[11.5px] leading-[1.8] text-ink-2">{t('editor.stateConflictHint')}</div>
          </div>
        )}

        {!editable && view && (
          <div className="shrink-0 border-b border-line bg-surface-2 px-6 py-2.5 text-[11.5px] leading-[1.8] text-ink-3">
            {committed ? t('editor.committed') : t('editor.committedReadonly')}
          </div>
        )}

        {/*
          422：六个 snake_case 表头键少了哪几个。后端把名字直接给出来了，
          照抄比让人去比对文档快
        */}
        {missingFields.length > 0 && (
          <div className="shrink-0 border-b border-line bg-yamabuki-soft px-6 py-2.5">
            <span className="text-[11.5px] text-ink-2">{t('editor.missingFields')}</span>
            {missingFields.map((f) => (
              <span key={f} className="ml-2 font-mono text-[11px] text-yamabuki">{f}</span>
            ))}
          </div>
        )}

        {duplicateCode && (
          <div className="shrink-0 border-b border-line bg-yamabuki-soft px-6 py-2.5 text-[11.5px] leading-[1.8] text-ink-2">
            {t('editor.duplicateCode')}
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
            <span className="text-[11.5px] text-matsu">
              {t('editor.saved', { version: view.version })}
              {view.editUpdatedAt && <span className="ml-1.5 font-mono text-ink-4">{view.editUpdatedAt}</span>}
            </span>
          )}
          {/*
            replayed = 这次调用是一次幂等重放：对应的写入上一次其实就成功了。
            不提示「保存成功」而是说清楚发生了什么 —— 否则用户会以为自己多存了一份。
          */}
          {view?.replayed && (
            <span className="text-[11.5px] text-ai">{t('editor.replayed')}</span>
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
            onClick={() => doCommit()}
            className="rounded-md bg-shu px-4 py-[7px] text-[12.5px] text-white hover:bg-shu-hover disabled:cursor-not-allowed disabled:opacity-45"
          >
            {commit.isPending ? t('editor.committing') : t('editor.commit')}
          </button>
        </div>
      </div>
    </div>
  );
}
