import type { ElementType, ReactNode } from 'react';
import { useReveal } from '../../lib/useReveal';

/** 包一层，把 useReveal 的 ref 挂上去 —— 这样调用方可以在列表里重复使用而不违反 hooks 规则 */
export default function Reveal({
  as: Tag = 'div',
  className = '',
  children,
}: {
  as?: ElementType;
  className?: string;
  children: ReactNode;
}) {
  const ref = useReveal<HTMLDivElement>();
  return (
    <Tag ref={ref} className={`reveal ${className}`}>
      {children}
    </Tag>
  );
}
