import type { SVGProps } from 'react';

/**
 * 稿子里的图标全是同一套：无填充、1.6~2 线宽、square 端点。
 * 直接内联，不引图标库 —— 一共就十来个，装一个包不划算。
 */
type P = SVGProps<SVGSVGElement> & { size?: number };

function Svg({ size = 16, children, ...rest }: P) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="square"
      {...rest}
    >
      {children}
    </svg>
  );
}

export const LogoMark = (p: P) => (
  <Svg {...p} strokeWidth={1.7}>
    <rect x="3" y="3" width="18" height="18" />
    <path d="M3 9h18" />
    <path d="M8 14.5l2.6 2.6L16.5 11" />
  </Svg>
);

export const IconCases = (p: P) => (
  <Svg {...p}>
    <path d="M3 5h7l2 2h9v12H3z" />
    <path d="M3 11h18" />
  </Svg>
);

export const IconRuns = (p: P) => (
  <Svg {...p}>
    <path d="M4 19V9" />
    <path d="M10 19V5" />
    <path d="M16 19v-7" />
    <path d="M22 19v-4" />
  </Svg>
);

export const IconAgent = (p: P) => (
  <Svg {...p}>
    <path d="M4 4h16v11H9l-5 4z" />
    <path d="M8 9h8" />
  </Svg>
);

export const IconDatasets = (p: P) => (
  <Svg {...p}>
    <ellipse cx="12" cy="6" rx="8" ry="3" />
    <path d="M4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6" />
    <path d="M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3" />
  </Svg>
);

export const IconApprovals = (p: P) => (
  <Svg {...p}>
    <path d="M5 3h14v18l-7-4-7 4z" />
    <path d="M9 10l2 2 4-4" />
  </Svg>
);

export const IconSearch = (p: P) => (
  <Svg {...p} strokeWidth={2}>
    <circle cx="11" cy="11" r="7" />
    <path d="M16.5 16.5L21 21" />
  </Svg>
);

export const IconBell = (p: P) => (
  <Svg {...p} strokeWidth={1.8}>
    <path d="M18 8a6 6 0 10-12 0c0 7-3 8-3 8h18s-3-1-3-8z" />
    <path d="M10.5 20a2 2 0 003 0" />
  </Svg>
);

export const IconPlus = (p: P) => (
  <Svg {...p} strokeWidth={2.2}>
    <path d="M12 5v14M5 12h14" />
  </Svg>
);

export const IconArrowRight = (p: P) => (
  <Svg {...p} strokeWidth={2}>
    <path d="M4 12h15" />
    <path d="M13 6l6 6-6 6" />
  </Svg>
);

export const IconChevron = (p: P) => (
  <Svg {...p} strokeWidth={2.6}>
    <path d="M9 5l7 7-7 7" />
  </Svg>
);

export const IconCheck = (p: P) => (
  <Svg {...p} strokeWidth={3}>
    <path d="M5 12.5l4.5 4.5L19 7" />
  </Svg>
);

export const IconPlay = ({ size = 12, ...rest }: P) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" {...rest}>
    <path d="M6 4l14 8-14 8z" />
  </svg>
);

export const IconVideo = (p: P) => (
  <Svg {...p} strokeWidth={1.8}>
    <rect x="3" y="6" width="12" height="12" rx="1" />
    <path d="M15 10l6-3v10l-6-3z" />
  </Svg>
);

/**
 * 退出登录 —— 门框 + 向外的箭头。
 *
 * 这里原本用的是设计稿上那个圆心加八条放射线的图标，名字叫 IconGear
 * 但画的其实是太阳：稿子里那个位置本来是深色模式开关。本项目不做深色模式，
 * 这个位置的行为是退出/换身份，图标要跟着改，不然点之前完全猜不到会发生什么。
 */
export const IconLogout = (p: P) => (
  <Svg {...p} strokeWidth={1.7}>
    <path d="M15 4h4v16h-4" />
    <path d="M14 12H3" />
    <path d="M7 8l-4 4 4 4" />
  </Svg>
);

export const IconLock = (p: P) => (
  <Svg {...p} strokeWidth={1.8}>
    <rect x="4" y="10" width="16" height="10" />
    <path d="M8 10V7a4 4 0 018 0v3" />
  </Svg>
);

export const IconShield = (p: P) => (
  <Svg {...p} strokeWidth={1.5}>
    <path d="M12 3l8 4v6c0 4.2-3.2 7.4-8 8.4C7.2 20.4 4 17.2 4 13V7z" />
    <path d="M9 12l2.2 2.2L15.5 10" />
  </Svg>
);

export const IconTable = (p: P) => (
  <Svg {...p} strokeWidth={1.5}>
    <rect x="3" y="4" width="18" height="16" />
    <path d="M3 9h18" />
    <path d="M9 9v11" />
  </Svg>
);

export const IconSplit = (p: P) => (
  <Svg {...p} strokeWidth={1.5}>
    <path d="M12 3v6" />
    <path d="M5 9h14v11H5z" />
    <path d="M9 14h6" />
  </Svg>
);
