import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ColLabel } from '../../components/ui';
import { useDispatch, useNodes, useProjects, useProjectTree } from '../../lib/queries';
import { isRunnableModule } from '../../lib/runnable';
import { DEMO_USER } from '../../lib/api';
import type { Browser } from '../../lib/types';

const BROWSERS: Browser[] = ['CHROME', 'FIREFOX', 'EDGE'];

export default function DispatchDialog({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const { data: projects } = useProjects();
  const { data: nodes } = useNodes();
  const dispatch = useDispatch();

  const [projectId, setProjectId] = useState<string | null>(null);
  const activeProject = projectId ?? projects?.[0]?.projectId ?? null;
  const { data: modules } = useProjectTree(activeProject ?? undefined);

  const [browser, setBrowser] = useState<Browser>('CHROME');
  const [suiteName, setSuiteName] = useState(t('runs.dispatchSuiteDefault'));
  const [picked, setPicked] = useState<string[]>([]);

  const runnable = useMemo(
    () => (modules ?? []).filter((m) => isRunnableModule(m.moduleCode)),
    [modules],
  );

  // 换项目时重选。默认全选该项目下可跑的模块
  useEffect(() => setPicked(runnable.map((m) => m.moduleId)), [runnable]);

  const caseIds = useMemo(
    () => runnable.filter((m) => picked.includes(m.moduleId)).flatMap((m) => m.cases.map((c) => c.caseId)),
    [runnable, picked],
  );

  const onlineCount = nodes?.filter((n) => n.online).length ?? 0;

  const submit = () => {
    if (!activeProject || caseIds.length === 0) return;
    dispatch.mutate(
      {
        projectId: activeProject,
        caseIds,
        browser,
        suiteName: suiteName.trim() || t('runs.dispatchSuiteDefault'),
        trigger: 'MANUAL',
        createdBy: DEMO_USER,
      },
      { onSuccess: onClose },
    );
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink/25 p-6" onClick={onClose}>
      <div
        className="flex max-h-full w-[520px] flex-col overflow-hidden rounded-lg border border-line bg-card"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="shrink-0 border-b border-line px-6 py-4">
          <span className="font-jp text-[14px] font-bold">{t('runs.dispatchTitle')}</span>
        </div>

        <div className="scrollable min-h-0 grow px-6 py-5">
          {/*
            没有节点在线时任务会挂在队列里 —— 任务不丢，但现在派发出去看不到进度。
            这一句要在派发前说，不能等人点完了发现进度条不动。
          */}
          {onlineCount === 0 && (
            <div className="mb-4 rounded-md border border-yamabuki/35 bg-yamabuki-soft px-3.5 py-2.5 text-[11.5px] leading-[1.8] text-ink-2">
              {t('runs.noNodeOnline')}
            </div>
          )}

          <ColLabel className="mb-2 block tracking-[.14em]">{t('runs.dispatchProject')}</ColLabel>
          <div className="mb-5 flex flex-wrap gap-1.5">
            {projects?.map((p) => (
              <button
                key={p.projectId}
                type="button"
                onClick={() => setProjectId(p.projectId)}
                className={`rounded-sm border px-[11px] py-1.5 text-[11.5px] transition-colors ${
                  p.projectId === activeProject
                    ? 'border-ink bg-ink text-paper'
                    : 'border-line bg-card text-ink-2 hover:bg-line-4'
                }`}
              >
                {p.projectName}
              </button>
            ))}
          </div>

          <ColLabel className="mb-2 block tracking-[.14em]">{t('runs.dispatchModules')}</ColLabel>
          {runnable.length === 0 ? (
            <div className="mb-2 rounded-md border border-line bg-surface-2 px-3.5 py-3 text-[12px] text-ink-3">
              {t('runs.noRunnable')}
            </div>
          ) : (
            <div className="mb-2 flex flex-col gap-1.5">
              {runnable.map((m) => (
                <label
                  key={m.moduleId}
                  className="flex cursor-pointer items-center gap-2.5 rounded-sm px-2 py-1.5 hover:bg-line-4"
                >
                  <input
                    type="checkbox"
                    checked={picked.includes(m.moduleId)}
                    onChange={(e) =>
                      setPicked((v) =>
                        e.target.checked ? [...v, m.moduleId] : v.filter((x) => x !== m.moduleId),
                      )
                    }
                    className="accent-shu"
                  />
                  <span className="font-mono text-[10.5px] text-ink-5">{m.moduleCode}</span>
                  <span className="grow text-[12.5px]">{m.moduleName}</span>
                  <span className="font-mono text-[11px] text-ink-4">{m.caseCount}</span>
                </label>
              ))}
            </div>
          )}
          <div className="mb-5 text-[11px] leading-[1.8] text-ink-4">{t('runs.runnableOnly')}</div>

          <ColLabel className="mb-2 block tracking-[.14em]">{t('runs.dispatchBrowser')}</ColLabel>
          <div className="mb-5 flex gap-1.5">
            {BROWSERS.map((b) => (
              <button
                key={b}
                type="button"
                onClick={() => setBrowser(b)}
                className={`rounded-sm border px-[11px] py-1.5 font-mono text-[11px] transition-colors ${
                  b === browser
                    ? 'border-ink bg-ink text-paper'
                    : 'border-line bg-card text-ink-2 hover:bg-line-4'
                }`}
              >
                {b}
              </button>
            ))}
          </div>

          <ColLabel className="mb-2 block tracking-[.14em]">{t('runs.dispatchSuite')}</ColLabel>
          <input
            value={suiteName}
            onChange={(e) => setSuiteName(e.target.value)}
            className="w-full rounded-md border border-line bg-paper px-3 py-2 text-[12.5px] outline-none focus:border-line-2"
          />

          {dispatch.error && (
            <div className="mt-4 rounded-md border border-shu/30 bg-shu-soft px-3 py-2 text-[11.5px] text-shu">
              {(dispatch.error as Error).message}
            </div>
          )}
        </div>

        <div className="flex shrink-0 items-center gap-3 border-t border-line px-6 py-4">
          <span className="text-[11.5px] text-ink-4">
            {caseIds.length > 0 ? t('runs.dispatchCount', { count: caseIds.length }) : t('runs.dispatchNone')}
          </span>
          <div className="grow" />
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-line bg-card px-3.5 py-[7px] text-[12.5px] text-ink-2 hover:bg-line-4"
          >
            {t('runs.dispatchCancel')}
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={caseIds.length === 0 || dispatch.isPending}
            className="rounded-md bg-shu px-4 py-[7px] text-[12.5px] text-white transition-colors hover:bg-shu-hover disabled:cursor-not-allowed disabled:opacity-45"
          >
            {dispatch.isPending ? t('runs.dispatchSending') : t('runs.dispatchSubmit')}
          </button>
        </div>
      </div>
    </div>
  );
}
