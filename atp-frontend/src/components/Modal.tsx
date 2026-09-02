import { useEffect, useId, useRef } from 'react';
import type { ReactNode } from 'react';

/**
 * 模态层。
 *
 * ⚠️ 三个弹层（派发、失败详情、案例编辑器）原来都是裸的 `<div>` 覆盖层：
 * 既不是 `<dialog>`，也没有 `role="dialog"`。
 * `document.querySelectorAll('dialog,[role=dialog]')` 返回 0 ——
 * 对读屏用户等于**不存在**，键盘 Tab 也会直接跑到底下那层去。
 * 截图上看着好好的，可访问树里是空的。
 *
 * 这里统一收口：role + aria-modal + 标题关联 + 焦点陷阱 + Esc 关闭 +
 * 关闭后把焦点还给来时那个元素。三个弹层共用一份，不各写各的。
 */
export default function Modal({
  onClose,
  labelledBy,
  label,
  align = 'center',
  className = '',
  children,
}: {
  onClose: () => void;
  /** 标题元素的 id。有标题就用它，读屏会念出来 */
  labelledBy?: string;
  /** 没有可见标题时用它兜底 */
  label?: string;
  /** center = 居中对话框，right = 右侧抽屉 */
  align?: 'center' | 'right';
  className?: string;
  children: ReactNode;
}) {
  const panel = useRef<HTMLDivElement>(null);
  const fallbackId = useId();

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;

    // 打开就把焦点收进来，否则 Tab 第一下会落在底层内容上
    const first = panel.current?.querySelector<HTMLElement>(
      'input:not([disabled]), select:not([disabled]), textarea:not([disabled]), button:not([disabled]), [href], [tabindex]:not([tabindex="-1"])',
    );
    (first ?? panel.current)?.focus();

    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        onClose();
        return;
      }
      if (e.key !== 'Tab') return;

      // 焦点陷阱：Tab 到尾回到头，Shift+Tab 到头回到尾
      const focusables = panel.current?.querySelectorAll<HTMLElement>(
        'input:not([disabled]), select:not([disabled]), textarea:not([disabled]), button:not([disabled]), [href], [tabindex]:not([tabindex="-1"])',
      );
      if (!focusables || focusables.length === 0) return;

      const list = Array.from(focusables);
      const head = list[0];
      const tail = list[list.length - 1];
      const active = document.activeElement;

      if (!e.shiftKey && active === tail) {
        e.preventDefault();
        head.focus();
      } else if (e.shiftKey && active === head) {
        e.preventDefault();
        tail.focus();
      }
    };

    document.addEventListener('keydown', onKey, true);
    return () => {
      document.removeEventListener('keydown', onKey, true);
      // 关闭后把焦点还给来时那个元素，不然会掉回 <body>
      opener?.focus?.();
    };
  }, [onClose]);

  return (
    <div
      className={`fixed inset-0 z-50 flex bg-ink/25 ${
        align === 'right' ? 'justify-end' : 'items-center justify-center p-6'
      }`}
      onClick={onClose}
    >
      <div
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby={labelledBy}
        aria-label={labelledBy ? undefined : (label ?? fallbackId)}
        tabIndex={-1}
        onClick={(e) => e.stopPropagation()}
        className={`outline-none ${className}`}
      >
        {children}
      </div>
    </div>
  );
}
