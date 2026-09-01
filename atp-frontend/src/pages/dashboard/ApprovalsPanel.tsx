import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { AsyncBlock, Tag } from '../../components/ui';
import { ApiError, useApprovalStats, useDecide, useMyApprovals, usePendingApprovals } from '../../lib/queries';
import { isForbidden } from '../../lib/api';
import { useSession } from '../../lib/useSession';
import { initials } from '../../lib/format';
import type {
  Approval, ApprovalType, CaseChangePayload, DatasetReleasePayload, Decision, RuleExceptionPayload,
} from '../../lib/types';

const TYPE_META: Record<ApprovalType, { key: string; tone: string; accent: boolean }> = {
  RULE_EXCEPTION: { key: 'approvals.typeRule', tone: 'bg-shu-soft text-shu', accent: true },
  CASE_CHANGE: { key: 'approvals.typeChange', tone: 'bg-ai-soft text-ai', accent: false },
  DATASET_RELEASE: { key: 'approvals.typeDataset', tone: 'bg-line-4 text-ink-2', accent: false },
};

/** before / after 是整包快照，diff 在前端算 —— 待审期间案例可能又被改了，只存变更字段会对不上 */
function diffKeys(before: Record<string, unknown>, after: Record<string, unknown>): string[] {
  const keys = new Set([...Object.keys(before ?? {}), ...Object.keys(after ?? {})]);
  return [...keys].filter((k) => JSON.stringify(before?.[k]) !== JSON.stringify(after?.[k]));
}

function show(v: unknown): string {
  if (v === null || v === undefined) return '—';
  return typeof v === 'object' ? JSON.stringify(v) : String(v);
}

function PayloadChips({ item }: { item: Approval }) {
  const { t } = useTranslation();

  if (item.type === 'RULE_EXCEPTION') {
    const p = item.payload as RuleExceptionPayload;
    return (
      <>
        <Tag tone="bg-shu-soft text-shu">{p.violated_std}</Tag>
        <Tag>{`${t('approvals.step')} ${p.step_seq}`}</Tag>
        <Tag>{`${t('approvals.until')} ${p.expire_at}`}</Tag>
      </>
    );
  }

  if (item.type === 'CASE_CHANGE') {
    const p = item.payload as CaseChangePayload;
    const pass = p.standards_check === 'PASS';
    return (
      <>
        {p.diff_summary?.map((d) => (
          <Tag key={d}>{d}</Tag>
        ))}
        <Tag tone={pass ? 'bg-matsu-soft text-matsu' : 'bg-shu-soft text-shu'} mono={false}>
          {pass ? t('approvals.standardsPass') : t('approvals.standardsFail')}
        </Tag>
      </>
    );
  }

  const p = item.payload as DatasetReleasePayload;
  return (
    <>
      <Tag>{p.corpus_name}</Tag>
      <Tag>{t('approvals.docs', { count: p.docs_count })}</Tag>
      <Tag tone="bg-ai-soft text-ai">{t('approvals.indexing', { percent: p.index_progress })}</Tag>
      <Tag
        tone={p.evaluated ? 'bg-matsu-soft text-matsu' : 'bg-yamabuki-soft text-yamabuki'}
        mono={false}
      >
        {p.evaluated ? t('approvals.evaluated') : t('approvals.notEvaluated')}
      </Tag>
    </>
  );
}

function ApprovalCard({ item, readOnly }: { item: Approval; readOnly: boolean }) {
  const { t } = useTranslation();
  const session = useSession();
  // sys_user.role → REVIEWER / ADMIN 为 true，QA_ENGINEER 为 false
  const canApprove = session?.user.canApprove ?? false;
  const meta = TYPE_META[item.type];
  const decide = useDecide();
  const [note, setNote] = useState('');
  const [showDiff, setShowDiff] = useState(false);
  const [noteError, setNoteError] = useState(false);

  const isChange = item.type === 'CASE_CHANGE';
  const payload = item.payload as CaseChangePayload;
  const changed = isChange && payload.before ? diffKeys(payload.before, payload.after) : [];

  /*
   * ⚠️ decidedBy 后端不再从请求体取，改成从 token 解 —— 请求体里那个字段保留但被忽略。
   * 所以「谁批的」一定是当前登录者：用 ?user=sato 看佐藤的待办然后点批准，
   * 记录上仍会是当前登录的人。要演示两个视角就真的换登录（侧栏齿轮）。
   */
  const submit = (decision: Decision) => {
    // 退回时 note 必填 —— 提交人只看得到这一句
    if (decision === 'REJECTED' && !note.trim()) {
      setNoteError(true);
      return;
    }
    setNoteError(false);
    decide.mutate({ requestId: item.requestId, decision, note: note.trim() });
  };

  const conflict = decide.error instanceof ApiError && decide.error.isConflict;
  // 403 = token 有效但缺 approval:decide。**不是**没登录，重新登录也拿不到这个权限
  const forbidden = isForbidden(decide.error);

  return (
    <div
      className={`card-surface px-[22px] py-[18px] ${
        item.overdue ? 'border-l-[3px] border-l-shu' : meta.accent ? 'border-l-[3px] border-l-shu/40' : ''
      }`}
    >
      <div className="mb-3 flex flex-wrap items-center gap-2.5">
        <Tag tone={meta.tone} mono={false}>
          {t(meta.key)}
        </Tag>
        <span className="font-mono text-[12px] text-shu">{item.targetId}</span>
        {/* 标题来自库，不翻译 */}
        <span className="text-[13.5px]">{item.title}</span>
        <div className="grow" />
        {item.slaRemaining && (
          <span className={`text-[11px] ${item.overdue ? 'text-shu' : 'text-ink-4'}`}>
            {item.slaRemaining}
          </span>
        )}
      </div>

      <div className="mb-3.5 flex flex-wrap gap-2">
        <PayloadChips item={item} />
      </div>

      {item.summary && <div className="mb-3 text-[12px] leading-[1.8] text-ink-2">{item.summary}</div>}

      {item.type === 'RULE_EXCEPTION' && (
        <div className="mb-3 text-[12px] leading-[1.8] text-ink-2">
          {t('approvals.reason')}：{(item.payload as RuleExceptionPayload).reason}
        </div>
      )}

      {showDiff && isChange && (
        <div className="mb-3.5 overflow-hidden rounded-md border border-line">
          <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)_minmax(0,1fr)] bg-surface-2 px-3 py-2 font-mono text-[9.5px] tracking-[.12em] text-ink-4">
            <span>FIELD</span>
            <span>{t('approvals.before')}</span>
            <span>{t('approvals.after')}</span>
          </div>
          {changed.map((k) => (
            <div
              key={k}
              className="grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)_minmax(0,1fr)] border-t border-line-3 px-3 py-2 text-[11.5px]"
            >
              <span className="font-mono text-ink-2">{k}</span>
              <span className="font-mono text-ink-3 line-through">{show(payload.before?.[k])}</span>
              <span className="font-mono text-shu">{show(payload.after?.[k])}</span>
            </div>
          ))}
          {changed.length === 0 && (
            <div className="px-3 py-3 text-center text-[11.5px] text-ink-4">{t('common.empty')}</div>
          )}
        </div>
      )}

      <div className="flex flex-wrap items-center gap-3">
        <span className="flex h-6 w-6 items-center justify-center rounded-full bg-line-4 font-mono text-[10px] text-ink-2">
          {initials(item.submitter)}
        </span>
        <span className="text-[12px] text-ink-3">
          {t('approvals.submittedBy', { who: item.submitter, when: item.submittedAt })}
        </span>
        <div className="grow" />

        {readOnly ? (
          <Tag mono={false}>{item.status}</Tag>
        ) : (
          <>
            {isChange && (
              <button
                type="button"
                onClick={() => setShowDiff((v) => !v)}
                className="rounded-md border border-line bg-card px-3.5 py-[7px] text-[12.5px] text-ink-2 transition-colors hover:bg-line-4"
              >
                {showDiff ? t('approvals.hideDiff') : t('approvals.viewDiff')}
              </button>
            )}
            <button
              type="button"
              disabled={decide.isPending || !canApprove}
              title={canApprove ? undefined : t('login.noPermission')}
              onClick={() => submit(item.type === 'DATASET_RELEASE' ? 'HOLD' : 'REJECTED')}
              className="rounded-md border border-line bg-card px-3.5 py-[7px] text-[12.5px] text-ink-2 transition-colors hover:bg-line-4 disabled:cursor-not-allowed disabled:opacity-45"
            >
              {item.type === 'DATASET_RELEASE' ? t('approvals.hold') : t('approvals.reject')}
            </button>
            <button
              type="button"
              disabled={decide.isPending || !canApprove}
              title={canApprove ? undefined : t('login.noPermission')}
              onClick={() => submit('APPROVED')}
              className="rounded-md bg-shu px-4 py-[7px] text-[12.5px] text-white transition-colors hover:bg-shu-hover disabled:cursor-not-allowed disabled:opacity-45"
            >
              {decide.isPending ? t('approvals.submitting') : t('approvals.approve')}
            </button>
          </>
        )}
      </div>

      {!readOnly && !canApprove && (
        <div className="mt-3 rounded-md border border-line bg-surface-2 px-3 py-2 text-[11.5px] text-ink-3">
          {t('login.noPermission')}
        </div>
      )}

      {!readOnly && canApprove && (
        <div className="mt-3">
          <input
            value={note}
            onChange={(e) => {
              setNote(e.target.value);
              setNoteError(false);
            }}
            placeholder={t('approvals.notePlaceholder')}
            aria-label={t('approvals.note')}
            className={`w-full rounded-md border bg-paper px-3 py-2 text-[12px] outline-none placeholder:text-ink-5 ${
              noteError ? 'border-shu' : 'border-line focus:border-line-2'
            }`}
          />
          {noteError && <div className="mt-1.5 text-[11px] text-shu">{t('approvals.noteRequired')}</div>}
        </div>
      )}

      {/*
        409 = 两个审批人同时点，后点的会拿到「已经被 X 处理为 Y」。
        提示刷新，**不要自动重试** —— 重试只会再撞一次，还可能盖掉别人的决策。
      */}
      {decide.error && (
        <div className="mt-3 rounded-md border border-shu/30 bg-shu-soft px-3 py-2 text-[11.5px] leading-[1.7] text-shu">
          {conflict
            ? t('approvals.conflict')
            : forbidden
              ? t('login.noPermission')
              : (decide.error as Error).message}
          {conflict && decide.error instanceof ApiError && decide.error.problem && (
            <div className="mt-1 font-mono text-[10.5px] text-ink-2">{decide.error.problem.detail}</div>
          )}
        </div>
      )}
    </div>
  );
}

type TabKey = 'pending' | 'mine';

export default function ApprovalsPanel() {
  const { t } = useTranslation();
  const [tab, setTab] = useState<TabKey>('pending');
  const { data: stats } = useApprovalStats();
  const pending = usePendingApprovals();
  const mine = useMyApprovals();

  const active = tab === 'pending' ? pending : mine;

  const tabs: { key: TabKey; label: string; count: number | undefined }[] = [
    { key: 'pending', label: t('approvals.awaitingMe'), count: stats?.awaitingMe },
    { key: 'mine', label: t('approvals.submittedByMe'), count: stats?.submittedByMe },
  ];

  return (
    <div className="scrollable h-full px-6 pt-5 pb-6">
      <div className="mb-[18px] flex flex-wrap items-center gap-2.5">
        {tabs.map((tb) => (
          <button
            key={tb.key}
            type="button"
            onClick={() => setTab(tb.key)}
            className={`rounded-md px-3.5 py-[7px] text-[12.5px] transition-colors ${
              tab === tb.key
                ? 'bg-ink text-paper'
                : 'border border-line bg-card text-ink-2 hover:bg-line-4'
            }`}
          >
            {tb.label}
            {tb.count !== undefined && ` ${tb.count}`}
          </button>
        ))}
        {stats && (
          <span className="rounded-md border border-line bg-card px-3.5 py-[7px] text-[12.5px] text-ink-2">
            {t('approvals.completed')} {stats.completed}
          </span>
        )}
        <div className="grow" />
        {stats && stats.overdue > 0 && (
          <span className="text-[12px] text-ink-4">
            {t('approvals.overdue')} <span className="font-mono text-shu">{stats.overdue}</span>
          </span>
        )}
      </div>

      <AsyncBlock
        isLoading={active.isLoading}
        error={active.error}
        isEmpty={!active.data?.length}
        emptyText={tab === 'pending' ? t('approvals.emptyPending') : t('approvals.emptyMine')}
        onRetry={() => void active.refetch()}
      >
        <div className="flex flex-col gap-3">
          {active.data?.map((item) => (
            <ApprovalCard key={item.requestId} item={item} readOnly={tab === 'mine'} />
          ))}
        </div>
      </AsyncBlock>
    </div>
  );
}
