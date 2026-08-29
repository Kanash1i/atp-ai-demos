import { useTranslation } from 'react-i18next';
import type { ReactNode } from 'react';

/** 稿子里那种小方角标签：状态、优先级、STD 编号 */
export function Tag({
  tone = 'text-ink-2 bg-line-4',
  mono = true,
  children,
  className = '',
}: {
  tone?: string;
  mono?: boolean;
  children: ReactNode;
  className?: string;
}) {
  return (
    <span
      className={`inline-flex items-center gap-[5px] rounded-xs px-2 py-[3px] text-[10.5px] leading-none ${
        mono ? 'font-mono tracking-[.08em]' : ''
      } ${tone} ${className}`}
    >
      {children}
    </span>
  );
}

/** 会呼吸的小圆点 —— 「这件事正在发生」 */
export function LiveDot({ className = 'bg-matsu', size = 6 }: { className?: string; size?: number }) {
  return (
    <span
      className={`animate-live inline-block shrink-0 rounded-full ${className}`}
      style={{ width: size, height: size }}
    />
  );
}

export function SectionTitle({ children }: { children: ReactNode }) {
  return <span className="font-jp text-[14px] font-bold">{children}</span>;
}

export function ColLabel({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <span className={`font-mono text-[9.5px] tracking-[.12em] text-ink-4 ${className}`}>{children}</span>
  );
}

/**
 * 写侧按钮 —— 新建 / 编辑 / 申请审批。
 *
 * M1 后端没有这三个接口（02-前端契约.md §四）：人在 UI 上编辑和 agent 生成
 * 走同一条写入路径，分开做会变成两套语义，所以整体押后到 M3。
 * 这里按钮**照稿子做出来**，但置灰并说明原因，而不是先藏起来 ——
 * 藏起来会让演示时看不出平台的完整形状。
 */
export function NotReadyButton({
  children,
  primary = false,
  milestone = 'M3',
  className = '',
}: {
  children: ReactNode;
  primary?: boolean;
  /** 这个功能落在哪个里程碑 —— 直接标在按钮上，比一个笼统的「敬请期待」诚实 */
  milestone?: string;
  className?: string;
}) {
  const { t } = useTranslation();
  return (
    <button
      type="button"
      disabled
      title={`${t('common.notReady')} — ${t('common.notReadyHint')}`}
      className={[
        'flex cursor-not-allowed items-center gap-[6px] rounded-md px-[13px] py-[7px] text-[12.5px]',
        primary
          ? 'border-none bg-shu/35 text-white'
          : 'border border-dashed border-line-2 bg-card text-ink-5',
        className,
      ].join(' ')}
    >
      {children}
      <span className="font-mono text-[9px] tracking-[.1em] opacity-70">{milestone}</span>
    </button>
  );
}

/** 加载 / 出错 / 空 三态。出错时把「怎么把后端起起来」直接写在页面上 */
export function AsyncBlock({
  isLoading,
  error,
  isEmpty,
  emptyText,
  children,
  onRetry,
}: {
  isLoading: boolean;
  error: unknown;
  isEmpty?: boolean;
  emptyText?: string;
  children: ReactNode;
  onRetry?: () => void;
}) {
  const { t } = useTranslation();

  if (isLoading) {
    return <div className="p-10 text-center text-[12.5px] text-ink-4">{t('common.loading')}</div>;
  }

  if (error) {
    return (
      <div className="p-10 text-center">
        <div className="text-[13px] text-shu">{t('common.backendDown')}</div>
        <div className="mx-auto mt-3 max-w-[520px] font-mono text-[11px] leading-[1.9] text-ink-4">
          {t('common.backendDownHint')}
        </div>
        <div className="mt-2 font-mono text-[11px] text-ink-5">
          {error instanceof Error ? error.message : String(error)}
        </div>
        {onRetry && (
          <button
            type="button"
            onClick={onRetry}
            className="mt-4 cursor-pointer rounded-md border border-line bg-card px-3 py-[6px] text-[12px] text-ink-2 hover:bg-line-4"
          >
            {t('common.retry')}
          </button>
        )}
      </div>
    );
  }

  if (isEmpty) {
    return (
      <div className="p-10 text-center text-[12.5px] text-ink-4">{emptyText ?? t('common.empty')}</div>
    );
  }

  return <>{children}</>;
}
