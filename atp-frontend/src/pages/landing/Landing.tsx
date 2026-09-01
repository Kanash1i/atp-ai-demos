import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import LangSwitcher from '../../components/LangSwitcher';
import {
  IconArrowRight, IconMail, IconPhone, IconShield, IconSplit, IconTable, LogoMark,
} from '../../components/icons';
import Typewriter from './Typewriter';
import Reveal from './Reveal';
import './landing.css';

/** 八条规范的严重度 —— 与后端校验器一致：只有 ERROR 拦人 */
const STANDARDS = [
  { id: '001', level: 'ERROR', tone: 'text-shu' },
  { id: '002', level: 'WARN', tone: 'text-yamabuki' },
  { id: '003', level: 'INFO', tone: 'text-ai' },
  { id: '004', level: 'ERROR', tone: 'text-shu' },
  { id: '005', level: 'AUTO', tone: 'text-matsu' },
  { id: '006', level: 'AUTO', tone: 'text-matsu' },
  { id: '007', level: 'AUTO', tone: 'text-matsu' },
  { id: '008', level: 'ERROR', tone: 'text-shu' },
] as const;

const STATS = [
  { value: '12', key: 'statModules' },
  { value: '13', key: 'statActions' },
  { value: '08', key: 'statStandards' },
  { value: 'P0–P3', key: 'statPriority' },
] as const;

function CtaButton({ size = 'md', className = '' }: { size?: 'sm' | 'md' | 'lg'; className?: string }) {
  const { t } = useTranslation();
  const pad = size === 'sm' ? 'px-[18px] py-[9px] text-[13.5px]' : size === 'lg' ? 'px-10 py-[18px] text-[16px]' : 'px-[34px] py-4 text-[15.5px]';
  return (
    <Link
      to="/dashboard"
      className={`inline-flex items-center gap-[10px] bg-shu font-medium tracking-[.05em] text-white transition-all duration-200 hover:-translate-y-0.5 hover:bg-shu-hover hover:shadow-[0_10px_24px_-12px_rgba(200,64,46,.55)] hover:text-white active:translate-y-0 active:shadow-none ${pad} ${className}`}
    >
      {t('landing.cta')}
      <IconArrowRight size={size === 'sm' ? 14 : size === 'lg' ? 18 : 17} strokeWidth={2} />
    </Link>
  );
}

export default function Landing() {
  const { t } = useTranslation();
  const ctaTitle = t('landing.ctaTitle', { returnObjects: true }) as unknown as string[];

  return (
    <div className="relative w-full overflow-x-hidden bg-paper">
      {/* 竖向发丝格线，和风精密的底纹 */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0"
        style={{
          backgroundImage:
            'repeating-linear-gradient(to right, rgba(27,23,20,.045) 0 1px, transparent 1px 120px)',
        }}
      />
      <div
        aria-hidden
        className="pointer-events-none absolute -top-[180px] -right-[160px] h-[720px] w-[720px] rounded-full"
        style={{
          background:
            'radial-gradient(circle at 38% 38%, rgba(200,64,46,.10), rgba(200,64,46,.02) 58%, transparent 70%)',
        }}
      />

      {/* ============ 顶栏 ============ */}
      <header className="relative flex h-[76px] items-center justify-between border-b border-line px-18">
        <div className="flex items-center gap-3">
          <LogoMark size={26} className="text-shu" />
          <span className="font-mono text-[17px] font-medium tracking-[.16em]">ATP</span>
          <span className="h-4 w-px bg-line-2" />
          <span className="text-[13px] tracking-[.08em] text-ink-3">{t('landing.brand')}</span>
        </div>
        <nav className="flex items-center gap-[30px] text-[13.5px] tracking-[.04em] text-ink-2">
          <span className="hidden cursor-pointer transition-colors hover:text-shu lg:inline">{t('landing.navPlatform')}</span>
          <span className="hidden cursor-pointer transition-colors hover:text-shu lg:inline">{t('landing.navAi')}</span>
          <span className="hidden cursor-pointer transition-colors hover:text-shu lg:inline">{t('landing.navDocs')}</span>
          <LangSwitcher />
          <CtaButton size="sm" />
        </nav>
      </header>

      {/* ============ HERO ============ */}
      <section className="relative px-18 pt-26 pb-23">
        <div className="animate-up mb-8 flex items-center gap-[14px]" style={{ animationDelay: '.10s' }}>
          <span className="h-px w-11 bg-shu" />
          <span className="font-mono text-[11.5px] tracking-[.28em] text-shu">{t('landing.eyebrow')}</span>
        </div>

        <Typewriter />

        <p
          className="animate-up m-0 mb-[42px] max-w-[660px] text-[16px] leading-[2] text-ink-2"
          style={{ animationDelay: '.55s' }}
        >
          {t('landing.sub')}
        </p>

        <div className="animate-up mb-20 flex items-center gap-[18px]" style={{ animationDelay: '.75s' }}>
          <CtaButton />
          <a
            href="#standards"
            className="inline-flex items-center gap-[10px] border border-line-2 px-7 py-[15px] text-[15px] tracking-[.04em] text-ink-2 transition-colors hover:border-ink hover:text-ink"
          >
            {t('landing.ctaGhost')}
          </a>
        </div>

        <div
          className="animate-up grid max-w-[900px] grid-cols-2 border-t border-line sm:grid-cols-4"
          style={{ animationDelay: '.95s' }}
        >
          {STATS.map((s, i) => (
            <div
              key={s.key}
              className={`pt-6 pr-7 pb-1 ${i === 0 ? '' : 'pl-7'} ${i < STATS.length - 1 ? 'border-r border-line' : ''}`}
            >
              <div className="font-mono text-[30px]">{s.value}</div>
              <div className="mt-1.5 text-[12.5px] tracking-[.06em] text-ink-3">{t(`landing.${s.key}`)}</div>
            </div>
          ))}
        </div>
      </section>

      {/* ============ 01 案例，不是脚本 ============ */}
      <section className="relative border-t border-line px-18 py-24">
        <Reveal className="mb-14 flex items-baseline gap-5">
          <span className="font-mono text-[12px] tracking-[.2em] text-shu">01</span>
          <h2 className="font-jp m-0 text-[34px] font-bold tracking-[.02em]">{t('landing.s1Title')}</h2>
        </Reveal>

        <div className="grid gap-px border border-line bg-line md:grid-cols-3">
          {[
            { Icon: IconTable, title: 's1c1Title', body: 's1c1Body' },
            { Icon: IconSplit, title: 's1c2Title', body: 's1c2Body' },
            { Icon: IconShield, title: 's1c3Title', body: 's1c3Body' },
          ].map(({ Icon, title, body }) => (
            <Reveal
              key={title}
              className="bg-card px-[34px] pt-10 pb-11 transition-all duration-300 hover:-translate-y-[3px] hover:shadow-[0_18px_40px_-28px_rgba(27,23,20,.35)]"
            >
              <Icon size={24} className="text-shu" />
              <h3 className="font-jp mt-[22px] mb-[14px] text-[19px] font-bold">{t(`landing.${title}`)}</h3>
              <p className="m-0 text-[14px] leading-[1.95] text-ink-2">{t(`landing.${body}`)}</p>
            </Reveal>
          ))}
        </div>
      </section>

      {/* ============ 02 AI 从两个方向进入 ============ */}
      <section className="relative border-t border-line bg-surface px-18 py-24">
        <Reveal className="mb-[18px] flex items-baseline gap-5">
          <span className="font-mono text-[12px] tracking-[.2em] text-shu">02</span>
          <h2 className="font-jp m-0 text-[34px] font-bold tracking-[.02em]">{t('landing.s2Title')}</h2>
        </Reveal>
        <Reveal className="mb-14 ml-11 max-w-[580px] text-[14.5px] leading-[1.95] text-ink-2">
          {t('landing.s2Sub')}
        </Reveal>

        <div className="grid gap-7 md:grid-cols-2">
          <Reveal className="card-surface px-10 pt-11 pb-[46px] transition-all duration-300 hover:-translate-y-[3px] hover:shadow-[0_18px_40px_-28px_rgba(27,23,20,.35)]">
            <div className="mb-[26px] flex items-center gap-3">
              <span className="bg-ai-soft px-[11px] py-[5px] font-mono text-[11px] tracking-[.12em] text-ai">READ</span>
              <span className="text-[12.5px] tracking-[.06em] text-ink-3">{t('landing.s2ReadTag')}</span>
            </div>
            <h3 className="font-jp m-0 mb-4 text-[26px] font-bold">{t('landing.s2ReadTitle')}</h3>
            <p className="m-0 mb-[30px] text-[14px] leading-[1.95] text-ink-2">{t('landing.s2ReadBody')}</p>
            <div className="flex flex-wrap gap-2">
              {['bge-m3', 'bge-reranker-v2-m3', 'pgvector', 'AgentScope'].map((chip) => (
                <span key={chip} className="border border-line px-3 py-1.5 font-mono text-[11.5px] text-ink-2">
                  {chip}
                </span>
              ))}
            </div>
          </Reveal>

          <Reveal className="card-surface px-10 pt-11 pb-[46px] transition-all duration-300 hover:-translate-y-[3px] hover:shadow-[0_18px_40px_-28px_rgba(27,23,20,.35)]">
            <div className="mb-[26px] flex items-center gap-3">
              <span className="bg-shu-soft px-[11px] py-[5px] font-mono text-[11px] tracking-[.12em] text-shu">WRITE</span>
              <span className="text-[12.5px] tracking-[.06em] text-ink-3">{t('landing.s2WriteTag')}</span>
            </div>
            <h3 className="font-jp m-0 mb-4 text-[26px] font-bold">{t('landing.s2WriteTitle')}</h3>
            <p className="m-0 mb-[30px] text-[14px] leading-[1.95] text-ink-2">{t('landing.s2WriteBody')}</p>
            <div className="flex flex-wrap gap-2">
              {['Go', 'PostgreSQL', 'CAS', 'exit-code contract'].map((chip) => (
                <span key={chip} className="border border-line px-3 py-1.5 font-mono text-[11.5px] text-ink-2">
                  {chip}
                </span>
              ))}
            </div>
          </Reveal>
        </div>
      </section>

      {/* ============ 03 八条规范 ============ */}
      <section id="standards" className="relative border-t border-line px-18 py-24">
        <Reveal className="mb-12 flex items-baseline gap-5">
          <span className="font-mono text-[12px] tracking-[.2em] text-shu">03</span>
          <h2 className="font-jp m-0 text-[34px] font-bold tracking-[.02em]">{t('landing.s3Title')}</h2>
        </Reveal>
        <Reveal className="grid max-w-[1180px] gap-x-16 md:grid-cols-2">
          {STANDARDS.map((std) => (
            <div key={std.id} className="flex gap-5 border-b border-line py-5">
              <span className={`w-[62px] shrink-0 font-mono text-[12.5px] ${std.tone}`}>STD-{std.id}</span>
              <span className="grow text-[14px]">{t(`landing.std.${std.id}`)}</span>
              <span className={`shrink-0 font-mono text-[11px] ${std.tone}`}>{std.level}</span>
            </div>
          ))}
        </Reveal>
      </section>

      {/* ============ 04 收尾 ============ */}
      <Reveal as="section" className="relative border-t border-line bg-ink px-18 pt-30 pb-31 text-paper">
        <div
          aria-hidden
          className="pointer-events-none absolute top-0 right-0 h-full w-[420px]"
          style={{ background: 'radial-gradient(circle at 78% 42%, rgba(200,64,46,.28), transparent 62%)' }}
        />
        <div className="relative max-w-[800px]">
          <h2 className="font-jp m-0 mb-[22px] text-[46px] leading-[1.32] font-black tracking-[.02em]">
            {ctaTitle[0]}
            <span
              style={{
                backgroundImage: 'linear-gradient(96deg, #FAF8F5 0%, #E8836F 60%, #C8402E 100%)',
                WebkitBackgroundClip: 'text',
                backgroundClip: 'text',
                WebkitTextFillColor: 'transparent',
              }}
            >
              {ctaTitle[1]}
            </span>
            {ctaTitle[2]}
          </h2>
          <p className="m-0 mb-[42px] max-w-[580px] text-[15.5px] leading-[2] text-ink-inv">
            {t('landing.ctaBody')}
          </p>
          <CtaButton size="lg" />
        </div>
      </Reveal>

      {/* ============ 联系方式 ============
          面试官第一眼会看 landing，所以这块要一眼看全 ——
          不折叠、不塞进页脚小字，邮箱与电话都可直接点。 */}
      <Reveal as="section" className="relative border-t border-line-dark bg-ink px-18 py-16">
        <div className="flex flex-col gap-8 md:flex-row md:items-end md:justify-between">
          <div>
            <div className="mb-4 flex items-center gap-[14px]">
              <span className="h-px w-11 bg-shu" />
              <span className="font-mono text-[11.5px] tracking-[.28em] text-shu">{t('landing.contact')}</span>
            </div>
            {/* 姓名不翻译 */}
            <div className="font-jp text-[32px] leading-[1.3] font-bold text-paper">刘 嘉龙</div>
            <div className="mt-1.5 text-[13px] tracking-[.06em] text-ink-inv">{t('landing.contactRole')}</div>
          </div>

          <div className="flex flex-col gap-3 md:items-end">
            <a
              href="mailto:kkaibulisi@gmail.com"
              className="group flex items-center gap-3 text-paper hover:text-paper"
            >
              <IconMail size={17} className="shrink-0 text-shu" />
              <span className="font-mono text-[17px] tracking-[.02em] underline decoration-[#5A4A42] decoration-1 underline-offset-[6px] transition-colors group-hover:decoration-shu">
                kkaibulisi@gmail.com
              </span>
            </a>
            <a href="tel:+8619002663292" className="group flex items-center gap-3 text-paper hover:text-paper">
              <IconPhone size={17} className="shrink-0 text-shu" />
              <span className="font-mono text-[17px] tracking-[.04em] underline decoration-[#5A4A42] decoration-1 underline-offset-[6px] transition-colors group-hover:decoration-shu">
                190 0266 3292
              </span>
            </a>
          </div>
        </div>
      </Reveal>

      <footer className="relative flex items-center justify-between border-t border-line-dark bg-ink px-18 py-[30px] text-[12px] tracking-[.06em] text-ink-dim">
        <span className="font-mono">ATP — Automation Test Platform</span>
        <span>{t('landing.footerNote')}</span>
      </footer>
    </div>
  );
}
