import { NavLink, Outlet, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import LangSwitcher from '../../components/LangSwitcher';
import { LiveDot } from '../../components/ui';
import {
  IconAgent, IconApprovals, IconBell, IconCases, IconDatasets,
  IconGear, IconRuns, IconSearch, LogoMark,
} from '../../components/icons';
import { useApprovalStats, useNodes } from '../../lib/queries';
import { DEMO_USER } from '../../lib/api';

const NAV = [
  { to: 'cases', key: 'cases', Icon: IconCases },
  { to: 'runs', key: 'runs', Icon: IconRuns },
  { to: 'agent', key: 'agent', Icon: IconAgent },
  { to: 'datasets', key: 'datasets', Icon: IconDatasets },
  { to: 'approvals', key: 'approvals', Icon: IconApprovals },
] as const;

/** 三个演示账号，M1 用 ?user= 带；接上 Sa-Token 之后这个参数保留用于切身份 */
const USERS: Record<string, { name: string; initials: string }> = {
  kaneshiro: { name: '金城 悠人', initials: 'KY' },
  sato: { name: '佐藤 美咲', initials: 'SM' },
  tanaka: { name: '田中 直樹', initials: 'TN' },
};

function EngineCard() {
  const { t } = useTranslation();
  const { data: nodes } = useNodes();

  // ⚠️ 在线与否看 online（心跳算出来的），不要看 status ——
  // 节点是独立进程，崩了不会回来把自己改成 OFFLINE
  const total = nodes?.length ?? 0;
  const online = nodes?.filter((n) => n.online).length ?? 0;
  const percent = total ? (online / total) * 100 : 0;

  return (
    <>
      <div className="px-5 pt-[26px] pb-2 font-mono text-[10px] tracking-[.2em] text-ink-4">
        {t('nav.engine')}
      </div>
      <div className="mx-4 rounded-md border border-line bg-card px-[14px] py-[13px]">
        <div className="mb-[9px] flex items-center justify-between">
          <span className="text-[11.5px] text-ink-2">Playwright Workers</span>
          <span className={`flex items-center gap-[5px] font-mono text-[10.5px] ${online ? 'text-matsu' : 'text-ink-4'}`}>
            {/* 一个都没在线时不要还在那儿绿着呼吸 —— 心跳过期就是过期 */}
            {online > 0 ? <LiveDot size={5} /> : <span className="inline-block h-[5px] w-[5px] rounded-full bg-ink-5" />}
            {total ? `${online}/${total}` : '—'}
          </span>
        </div>
        <div className="h-[3px] overflow-hidden rounded-[2px] bg-line-5">
          <div
            className={`animate-bar h-full ${online ? 'bg-matsu' : 'bg-ink-5'}`}
            style={{ width: `${online ? percent : 100}%` }}
          />
        </div>
        <div className="mt-[9px] font-mono text-[10px] text-ink-4">
          {total ? `${nodes![0].nodeName} … ${nodes![total - 1].nodeName}` : 'node pool'}
        </div>
      </div>
    </>
  );
}

export default function DashboardLayout() {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  const { data: stats } = useApprovalStats();
  const user = USERS[DEMO_USER] ?? { name: DEMO_USER, initials: DEMO_USER.slice(0, 2).toUpperCase() };

  const activeKey = NAV.find((n) => pathname.includes(`/dashboard/${n.to}`))?.key ?? 'cases';

  return (
    <div className="flex h-screen w-full overflow-hidden bg-paper">
      {/* ================= 左侧导航 ================= */}
      <aside className="flex h-full w-[236px] shrink-0 flex-col border-r border-line bg-rail">
        <div className="flex h-[58px] items-center gap-[11px] border-b border-line px-[18px]">
          <LogoMark size={21} className="text-shu" />
          <span className="font-mono text-[14.5px] font-medium tracking-[.16em]">ATP</span>
        </div>

        <div className="px-5 pt-[18px] pb-2 font-mono text-[10px] tracking-[.2em] text-ink-4">
          {t('nav.workspace')}
        </div>

        <nav className="flex flex-col gap-0.5">
          {NAV.map(({ to, key, Icon }) => (
            <NavLink
              key={to}
              to={to}
              className={({ isActive }) =>
                [
                  'mx-2.5 flex items-center gap-[11px] rounded-md border-l-2 px-3 py-2.5 text-[13.5px] transition-colors',
                  isActive
                    ? 'border-l-shu bg-card text-ink'
                    : 'border-l-transparent text-ink-2 hover:bg-[#EAE5DC]',
                ].join(' ')
              }
            >
              <Icon size={16} />
              <span className="grow">{t(`nav.${key}`)}</span>
              {key === 'runs' && <LiveDot />}
              {key === 'approvals' && stats && stats.awaitingMe > 0 && (
                <span className="min-w-[19px] rounded-[9px] bg-shu px-[5px] py-px text-center font-mono text-[10.5px] text-white">
                  {stats.awaitingMe}
                </span>
              )}
            </NavLink>
          ))}
        </nav>

        <EngineCard />

        <div className="grow" />

        <div className="flex items-center gap-2.5 border-t border-line px-[18px] py-[14px]">
          <span className="flex h-[30px] w-[30px] items-center justify-center rounded-full bg-ai font-mono text-[11.5px] text-white">
            {user.initials}
          </span>
          <div className="min-w-0 grow">
            <div className="text-[12.5px] text-ink">{user.name}</div>
            <div className="text-[10.5px] text-ink-4">{t('nav.role')}</div>
          </div>
          <IconGear size={15} className="text-ink-4" />
        </div>
      </aside>

      {/* ================= 主区 ================= */}
      <main className="flex min-w-0 grow flex-col">
        <header className="flex h-[58px] shrink-0 items-center gap-4 border-b border-line bg-card px-6">
          <div className="flex items-center gap-[9px] text-[13px] text-ink-3">
            <span>ATP</span>
            <span className="text-ink-6">/</span>
            <span className="text-ink">{t(`nav.${activeKey}`)}</span>
          </div>
          <div className="grow" />

          <div className="hidden w-[290px] items-center gap-[9px] rounded-md border border-line bg-paper px-3 py-[7px] xl:flex">
            <IconSearch size={14} className="text-ink-4" />
            <span className="grow truncate text-[12.5px] text-ink-4">{t('common.search')}</span>
            <span className="rounded-xs border border-line px-[5px] py-px font-mono text-[10px] text-ink-5">⌘K</span>
          </div>

          <LangSwitcher compact />

          <button
            type="button"
            className="flex items-center gap-[7px] rounded-md border border-line bg-card px-[13px] py-[7px] text-[12.5px] text-ink-2 transition-colors hover:bg-line-4"
          >
            <IconBell size={14} />
            <span className="hidden sm:inline">{t('common.alerts')}</span>
          </button>
        </header>

        <div className="min-h-0 grow overflow-hidden">
          <Outlet />
        </div>
      </main>
    </div>
  );
}
