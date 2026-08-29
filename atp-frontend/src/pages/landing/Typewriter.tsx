import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

/**
 * 三语循环打字机。
 *
 * ⚠️ 它**不跟随顶栏的语言切换器**（设计要求）：三种语言轮流打一遍，
 * 是给第一次进来的人看的门面，而不是当前界面语言的体现。
 */
const PHRASES = ['企业级自动化测试平台', '企業向け自動テスト基盤', 'Enterprise Test Automation'];

/** 每个字符的耗时；比初版快了将近一倍 */
const MS_PER_CHAR = 62;
/** 打完之后停留多久再换下一句 */
const HOLD_MS = 2400;
/** 擦掉重来之间的空档 */
const GAP_MS = 320;

export default function Typewriter() {
  const { t } = useTranslation();
  const [index, setIndex] = useState(0);
  const [typed, setTyped] = useState(false);
  const timers = useRef<number[]>([]);

  useEffect(() => {
    const clear = () => {
      timers.current.forEach(clearTimeout);
      timers.current = [];
    };

    const text = PHRASES[index];
    const duration = text.length * MS_PER_CHAR;

    // 下一帧才把 clip 打开，否则和重置写在同一帧里，transition 不会触发
    const raf = requestAnimationFrame(() => setTyped(true));

    timers.current.push(
      window.setTimeout(() => setTyped(false), duration + HOLD_MS),
      window.setTimeout(() => setIndex((i) => (i + 1) % PHRASES.length), duration + HOLD_MS + GAP_MS),
    );

    return () => {
      cancelAnimationFrame(raf);
      clear();
    };
  }, [index]);

  const text = PHRASES[index];
  const duration = text.length * MS_PER_CHAR;

  const vars = {
    '--tw-steps': String(text.length),
    '--tw-dur': typed ? `${duration}ms` : '0ms',
    '--tw-clip': typed ? '0%' : '100%',
    '--tw-left': typed ? '100%' : '0%',
  } as React.CSSProperties;

  return (
    // 视觉上三语轮播，但读屏拿到的是一个稳定的、当前界面语言的标题 ——
    // 让辅助技术跟着动画换语言毫无意义
    <h1
      className="font-jp m-0 mb-9 h-[1.3em] text-[78px] leading-[1.3] font-black"
      aria-label={t('landing.brand')}
    >
      <span className="tw-wrap" style={vars} aria-hidden>
        <span className="tw-text">{text}</span>
        <span className="tw-caret" />
      </span>
    </h1>
  );
}
