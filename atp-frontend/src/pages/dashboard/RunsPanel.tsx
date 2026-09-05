import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { AsyncBlock, ColLabel, LiveDot, NotReadyButton, SectionTitle, Tag } from '../../components/ui';
import { IconPlay, IconVideo } from '../../components/icons';
import { isNoContent, useExecStats, useRecentRuns, useRunningBatch, useTaskDetail } from '../../lib/queries';
import { approxSec, dash, execStatusTone, humanSec, signed, timeOnly, toneOf } from '../../lib/format';
import { KNOWN_CONFLICT_CASE } from '../../lib/runnable';
import DispatchDialog from './DispatchDialog';
import Modal from '../../components/Modal';
import type { RunningBatch } from '../../lib/types';

const RUN_COLS = '168px minmax(0,1fr) 104px 104px 96px 92px 132px';

/** 环比上色。inverted 用于「越大越坏」的指标（平均耗时） */
function deltaTone(delta: number | null, inverted = false): string {
  if (delta === null || delta === 0) return 'text-ink-4';
  const good = inverted ? delta < 0 : delta > 0;
  return good ? 'text-matsu' : 'text-shu';
}

function StatCard({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="card-surface px-5 py-[18px]">
      <div className="text-[10.5px] tracking-[.1em] text-ink-4">{label}</div>
      {children}
    </div>
  );
}

function Stats({ live }: { live: boolean }) {
  const { t } = useTranslation();
  const { data, isLoading, error, refetch } = useExecStats(live);

  return (
    <AsyncBlock isLoading={isLoading} error={error} onRetry={() => void refetch()}>
      {data && (
        <div className="mb-[18px] grid grid-cols-2 gap-4 xl:grid-cols-4">
          <StatCard label={t('runs.today')}>
            <div className="mt-2.5 font-mono text-[30px]">{data.todayTotal.toLocaleString()}</div>
            {/* 环比可能为 null（没有昨日数据）；正负要分开上色，-100% 印成绿的就荒唐了 */}
            <div className={`mt-1.5 text-[11.5px] ${deltaTone(data.totalDeltaPercent)}`}>
              {data.totalDeltaPercent === null
                ? t('runs.noYesterday')
                : `${t('runs.vsYesterday')} ${signed(data.totalDeltaPercent, '%')}`}
            </div>
          </StatCard>

          <StatCard label={t('runs.passRate')}>
            <div className="mt-2.5 font-mono text-[30px]">
              {data.passRate.toFixed(1)}
              <span className="text-[17px] text-ink-4">%</span>
            </div>
            <div className="mt-2.5 h-[3px] overflow-hidden rounded-[2px] bg-line-5">
              <div className="animate-bar h-full bg-matsu" style={{ width: `${data.passRate}%` }} />
            </div>
          </StatCard>

          <StatCard label={t('runs.avgDuration')}>
            <div className="mt-2.5 font-mono text-[30px]">
              {data.avgDurationSec}
              <span className="text-[17px] text-ink-4">s</span>
            </div>
            {/* 耗时是反的：变长才是坏消息 */}
            <div className={`mt-1.5 text-[11.5px] ${deltaTone(data.avgDurationDelta, true)}`}>
              {data.avgDurationDelta === null
                ? t('runs.noYesterday')
                : data.avgDurationDelta === 0
                  ? '±0s'
                  : `${t('runs.vsYesterday')} ${signed(data.avgDurationDelta, 's')}`}
            </div>
          </StatCard>

          <StatCard label={t('runs.failures')}>
            <div className="mt-2.5 font-mono text-[30px] text-shu">{data.failedCount}</div>
            <div className="mt-1.5 text-[11.5px] text-ink-3">
              {t('runs.ofThemP0', { count: data.failedP0Count })}
            </div>
          </StatCard>
        </div>
      )}
    </AsyncBlock>
  );
}

function RunningCard({ batch }: { batch: RunningBatch }) {
  const { t } = useTranslation();
  const pct = (n: number) => (batch.totalCount ? (n / batch.totalCount) * 100 : 0);
  const eta = approxSec(batch.etaSec);

  /*
   * 「还在派发」和「执行卡住了」在看板上长得一模一样：都是 done=0 而 Elapsed 在涨。
   * 但它们是两种状态 —— 前者只要等，后者要去看节点。
   * 一条都还没跑完却已经过了几秒，先按「正在派发」讲，别显示一个像卡死的 0 / N。
   */
  const dispatching = batch.doneCount === 0 && batch.runningCount === 0 && batch.elapsedSec > 5;

  return (
    <div className="card-surface mb-[18px] px-[22px] py-5">
      <div className="mb-4 flex flex-wrap items-center gap-[11px]">
        <LiveDot size={7} />
        <SectionTitle>{t('runs.running')}</SectionTitle>
        <span className="font-mono text-[11.5px] text-shu">{batch.runCode}</span>
        <span className="text-[12px] text-ink-3">
          {[batch.projectName, batch.suiteName, batch.browser].filter(Boolean).join(' · ')}
        </span>
        <div className="grow" />
        {/* 没有中止接口，按钮先摆着 */}
        <NotReadyButton milestone="M2">{t('runs.abort')}</NotReadyButton>
      </div>

      <div className="mb-3 flex items-center gap-3.5">
        <div className="flex h-1.5 grow overflow-hidden rounded-[3px] bg-line-5">
          <div className="animate-bar h-full bg-matsu" style={{ width: `${pct(batch.passedCount)}%` }} />
          <div className="animate-bar h-full bg-shu" style={{ width: `${pct(batch.failedCount)}%` }} />
          <div className="animate-bar h-full bg-line-2" style={{ width: `${pct(batch.skippedCount)}%` }} />
        </div>
        <span className="font-mono text-[12.5px] whitespace-nowrap">
          {dispatching ? (
            <span className="text-ai">{t('runs.dispatching', { count: batch.totalCount })}</span>
          ) : (
            `${batch.doneCount} / ${batch.totalCount}`
          )}
        </span>
      </div>

      <div className="flex flex-wrap gap-[22px] text-[11.5px] text-ink-3">
        <span className="flex items-center gap-1.5">
          <span className="h-[7px] w-[7px] rounded-[2px] bg-matsu" />
          <span className="font-mono">PASSED {batch.passedCount}</span>
        </span>
        <span className="flex items-center gap-1.5">
          <span className="h-[7px] w-[7px] rounded-[2px] bg-shu" />
          <span className="font-mono">FAILED {batch.failedCount}</span>
        </span>
        <span className="flex items-center gap-1.5">
          <span className="h-[7px] w-[7px] rounded-[2px] bg-line-2" />
          <span className="font-mono">SKIPPED {batch.skippedCount}</span>
        </span>
        <span className="flex items-center gap-1.5">
          <LiveDot className="bg-ai" size={7} />
          <span className="font-mono">RUNNING {batch.runningCount}</span>
        </span>
        <div className="grow" />
        <span>
          {t('runs.elapsed')} <span className="font-mono">{humanSec(batch.elapsedSec)}</span>
          {' · '}
          {/*
            etaSec 可能为 null —— 一条都还没跑完时没有外推的基数。
            有值时也只给「≈」：它是按已完成任务的平均耗时推的，不是承诺。
          */}
          {eta ? (
            <>
              {t('runs.eta')} <span className="font-mono">{eta}</span>
            </>
          ) : (
            <span className="text-ink-4" title={t('runs.etaUnknown')}>
              {t('runs.eta')} <span className="font-mono">—</span>
            </span>
          )}
        </span>
      </div>
    </div>
  );
}

/**
 * 「执行中的批次」的空状态。
 * 后端在没有批次时返回 204 而不是 200 加空对象 —— 历史数据是种子，
 * 但「正在跑」这件事必须是真的，摆个不动的假进度条演示时一刷新就露馅。
 */
function RunningSection({
  data, isLoading, error, onDispatch,
}: {
  data: unknown;
  isLoading: boolean;
  error: unknown;
  onDispatch: () => void;
}) {
  const { t } = useTranslation();

  if (isLoading || error) return null;

  if (isNoContent(data)) {
    return (
      <div className="card-surface mb-[18px] flex flex-col items-center px-[22px] py-9">
        <div className="text-[13px] text-ink-3">{t('runs.noRunning')}</div>
        <div className="mt-2 max-w-[560px] text-center text-[11.5px] leading-[1.8] text-ink-4">
          {t('runs.noRunningHint')}
        </div>
        <button
          type="button"
          onClick={onDispatch}
          className="mt-4 flex items-center gap-1.5 rounded-md bg-shu px-4 py-[9px] text-[12.5px] text-white transition-colors hover:bg-shu-hover"
        >
          <IconPlay size={12} />
          {t('runs.dispatch')}
        </button>
      </div>
    );
  }

  return <RunningCard batch={data as RunningBatch} />;
}

/** 失败详情。failedSeq 直接定位到失败那一步 */
function TaskDrawer({ taskId, onClose }: { taskId: string; onClose: () => void }) {
  const { t } = useTranslation();
  const { data, isLoading, error } = useTaskDetail(taskId);

  return (
    <Modal
      onClose={onClose}
      align="right"
      labelledBy="task-drawer-title"
      className="flex h-full w-[620px] max-w-full flex-col border-l border-line bg-card"
    >
      <>
        <div className="flex shrink-0 items-center gap-3 border-b border-line px-6 py-4">
          <span id="task-drawer-title">
            <SectionTitle>{data?.caseCode ?? t('common.loading')}</SectionTitle>
          </span>
          <div className="grow" />
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-line px-3 py-1.5 text-[12px] text-ink-2 hover:bg-line-4"
          >
            {t('runs.close')}
          </button>
        </div>

        <div className="scrollable min-h-0 grow px-6 py-5">
          <AsyncBlock isLoading={isLoading} error={error}>
            {data && (
              <>
                <div className="mb-4 text-[14px] leading-[1.7]">{data.caseTitle}</div>
                <div className="mb-5 flex flex-wrap items-center gap-2">
                  <Tag tone={`${toneOf(execStatusTone, data.status).bg} ${toneOf(execStatusTone, data.status).fg}`}>
                    {data.status}
                  </Tag>
                  <Tag>{data.runCode}</Tag>
                  <Tag>{data.browser}</Tag>
                  <Tag>{data.nodeName}</Tag>
                  <Tag>{dash(data.duration)}</Tag>
                </div>

                {data.errorMsg && (
                  <div className="mb-5 rounded-md border border-shu/30 bg-shu-soft px-4 py-3">
                    {data.failedSeq !== null && (
                      <div className="mb-1.5 font-mono text-[10.5px] text-shu">
                        {t('runs.failedAtStep', { seq: data.failedSeq })}
                      </div>
                    )}
                    {/*
                      errorMsg 是 Playwright 原样抛的，多行 + 带绝对路径的堆栈。
                      不加 whitespace-pre-wrap 的话换行会被 HTML 折掉，
                      一整段堆栈压成一行长文本，等于没写。break-words 是给
                      /tmp/playwright-java-…/package/lib/server/progress.js 那种长路径的。
                    */}
                    <div className="max-h-[220px] overflow-y-auto font-mono text-[11.5px] leading-[1.8] break-words whitespace-pre-wrap text-ink-2">
                      {data.errorMsg}
                    </div>
                  </div>
                )}

                {/*
                  ATP-ORDER-0003 是刻意保留的红 —— 它和 ATP-CART-0007 对购物车初始状态的
                  要求相反。把「为什么它是红的」写在失败详情里，比让人对着一条红记录猜要好。

                  ⚠️ 必须同时判 FAILED。这条案例是**会通过的** —— 它跟 0007 的冲突只在
                  两条一起跑、且共用购物车初始状态时才触发，单跑就是绿的。
                  只看 caseCode 的话，绿行点进去也弹「这条红的是刻意保留的」，
                  说明卡和它上面那个绿色 PASSED 标签当场打架。
                */}
                {data.caseCode === KNOWN_CONFLICT_CASE && data.status === 'FAILED' && (
                  <div className="mb-5 rounded-md border border-ai/30 bg-ai-soft px-4 py-3">
                    <div className="mb-1.5 font-mono text-[10px] tracking-[.12em] text-ai">BY DESIGN</div>
                    <div className="text-[11.5px] leading-[1.85] text-ink-2">{t('runs.knownConflict')}</div>
                  </div>
                )}

                {/* Playwright 录的 webm，浏览器原生可播，不需要转码；后端带 Range 支持 */}
                {data.videoUrl ? (
                  <div className="mb-5">
                    <video
                      key={data.videoUrl}
                      src={data.videoUrl}
                      controls
                      preload="metadata"
                      poster={data.screenshotUrl ?? undefined}
                      aria-label={t('runs.videoOf', { code: data.caseCode })}
                      className="w-full rounded-md border border-line bg-ink"
                    >
                      {t('runs.videoUnsupported')}
                    </video>
                    <div className="mt-1.5 font-mono text-[10px] break-all text-ink-5">{data.videoUrl}</div>
                  </div>
                ) : (
                  // 只有失败的和约 1/10 抽样的成功用例有录像 —— 真实平台不会给每次执行都存视频
                  <div className="mb-5 flex h-[140px] flex-col items-center justify-center rounded-md border border-dashed border-line-2 bg-surface-2">
                    <IconVideo size={22} className="text-ink-5" />
                    <div className="mt-2.5 text-[11.5px] text-ink-4">{t('runs.artifactPending')}</div>
                  </div>
                )}

                <ColLabel className="mb-2 block">{t('runs.stepStatus')}</ColLabel>
                <div>
                  {data.steps.map((s) => {
                    const tone = toneOf(execStatusTone, s.status);
                    const failed = s.seq === data.failedSeq;
                    return (
                      <div
                        key={s.seq}
                        className={`flex items-center gap-3 border-b border-line-3 px-2 py-2.5 ${
                          failed ? 'bg-shu-soft' : ''
                        } ${s.status === 'SKIPPED' ? 'opacity-55' : ''}`}
                      >
                        <span className="w-6 shrink-0 font-mono text-[11.5px] text-ink-5">{s.seq}</span>
                        <span className="w-[130px] shrink-0 font-mono text-[10.5px] text-ink-2">{s.action}</span>
                        <Tag tone={`${tone.bg} ${tone.fg}`}>{s.status}</Tag>
                        <div className="grow" />
                        <span className="font-mono text-[11px] text-ink-4">{dash(s.duration)}</span>
                      </div>
                    );
                  })}
                </div>
              </>
            )}
          </AsyncBlock>
        </div>
      </>
    </Modal>
  );
}

export default function RunsPanel() {
  const { t } = useTranslation();
  const limit = 200;
  const [taskId, setTaskId] = useState<string | null>(null);
  const [dispatching, setDispatching] = useState(false);

  /*
   * useRunningBatch 提到这里调一次，再往下传 —— 它带副作用（批次收尾时作废
   * stats/recent 缓存），在多个子组件里各调一次会重复触发。
   * 同时它决定了下面两张表要不要跟着轮询。
   */
  const running = useRunningBatch();
  const live = Boolean(running.data) && !isNoContent(running.data);

  const { data: runs, isLoading, error, refetch } = useRecentRuns(limit, live);

  return (
    <div className="scrollable h-full px-6 pt-5 pb-6">
      <Stats live={live} />
      <RunningSection
        data={running.data}
        isLoading={running.isLoading}
        error={running.error}
        onDispatch={() => setDispatching(true)}
      />

      <div className="card-surface overflow-hidden">
        <div className="flex items-center border-b border-line px-[22px] py-[15px]">
          <SectionTitle>{t('runs.recent')}</SectionTitle>
          <div className="grow" />
          <span className="text-[12px] text-ink-4">{t('runs.showingLast', { count: limit })}</span>
        </div>

        <div
          className="grid border-b border-line bg-surface-2 px-[22px]"
          style={{ gridTemplateColumns: RUN_COLS }}
        >
          {[
            t('runs.colCase'), t('runs.colTitle'), t('runs.colBrowser'), t('runs.colNode'),
            t('runs.colStatus'), t('runs.colTime'), t('runs.colFinished'),
          ].map((h, i, arr) => (
            <div key={h} className={`py-2.5 ${i === 0 ? 'pr-2' : i === arr.length - 1 ? 'pl-2' : 'px-2'}`}>
              <ColLabel>{h}</ColLabel>
            </div>
          ))}
        </div>

        <AsyncBlock
          isLoading={isLoading}
          error={error}
          isEmpty={!runs?.length}
          onRetry={() => void refetch()}
        >
          {runs?.map((r) => {
            const tone = toneOf(execStatusTone, r.status);
            return (
              <button
                key={r.taskId}
                type="button"
                onClick={() => setTaskId(r.taskId)}
                className="grid w-full items-center border-b border-line-3 px-[22px] text-left transition-colors hover:bg-surface-2"
                style={{ gridTemplateColumns: RUN_COLS }}
              >
                <div className="py-[11px] pr-2 font-mono text-[11.5px] text-shu">{r.caseCode}</div>
                {/* 案例标题来自库，不翻译 */}
                <div className="min-w-0 truncate px-2 py-[11px] text-[12.5px]">{r.caseTitle}</div>
                <div className="px-2 py-[11px] font-mono text-[11px] text-ink-2">{dash(r.browser)}</div>
                <div className="px-2 py-[11px] font-mono text-[11px] text-ink-3">{dash(r.nodeName)}</div>
                <div className="px-2 py-[11px]">
                  <Tag tone={`${tone.bg} ${tone.fg}`}>
                    {r.status === 'RUNNING' && <LiveDot className="bg-ai" size={5} />}
                    {r.status}
                  </Tag>
                </div>
                <div className="px-2 py-[11px] font-mono text-[11.5px] text-ink-2">{dash(r.duration)}</div>
                <div className="flex items-center gap-1.5 py-[11px] pl-2 font-mono text-[11px] text-ink-4">
                  {timeOnly(r.finishedAt)}
                  {r.hasVideo && <IconVideo size={12} className="text-ink-5" />}
                </div>
              </button>
            );
          })}
        </AsyncBlock>
      </div>

      {taskId && <TaskDrawer taskId={taskId} onClose={() => setTaskId(null)} />}
      {dispatching && <DispatchDialog onClose={() => setDispatching(false)} />}
    </div>
  );
}
