import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import LangSwitcher from '../components/LangSwitcher';
import { IconArrowRight, LogoMark } from '../components/icons';
import { isBadCredentials, login } from '../lib/api';

/**
 * 三个演示账号。
 *
 * 口令从 `VITE_DEMO_PASSWORD` 读并预填 —— 演示时点一下就能进，不用每次敲。
 * ⚠️ 走环境变量而不是写死在代码里：值在 `.env.example`（与后端 `.env` 的
 * `ATP_DEMO_PASSWORD` 保持一致），部署到别处时改环境变量即可，
 * 不设就留空手动输入。这是虚构演示数据，不是任何真实系统的凭据。
 */
const DEMO_ACCOUNTS = [
  { username: 'kaneshiro', display: '金城 悠人', initials: 'KY' },
  { username: 'sato', display: '佐藤 美咲', initials: 'SM' },
  { username: 'tanaka', display: '田中 直樹', initials: 'TN' },
];

export default function Login() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();

  const prefill = new URLSearchParams(location.search).get('u') ?? '';
  const from = (location.state as { from?: string } | null)?.from ?? '/dashboard/cases';

  const [username, setUsername] = useState(prefill || DEMO_ACCOUNTS[0].username);
  const [password, setPassword] = useState(import.meta.env.VITE_DEMO_PASSWORD ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy || !username.trim() || !password) return;
    setBusy(true);
    setError(null);
    try {
      await login(username.trim(), password);
      navigate(from, { replace: true });
    } catch (err) {
      setError(isBadCredentials(err) ? t('login.badCredentials') : (err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="relative flex min-h-screen items-center justify-center bg-paper px-6">
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
        className="pointer-events-none absolute -top-[200px] -right-[180px] h-[680px] w-[680px] rounded-full"
        style={{
          background:
            'radial-gradient(circle at 38% 38%, rgba(200,64,46,.10), rgba(200,64,46,.02) 58%, transparent 70%)',
        }}
      />

      <div className="absolute top-6 right-6">
        <LangSwitcher />
      </div>

      <form onSubmit={submit} className="card-surface relative w-[400px] max-w-full px-9 py-10">
        <div className="mb-8 flex items-center gap-3">
          <LogoMark size={24} className="text-shu" />
          <span className="font-mono text-[16px] font-medium tracking-[.16em]">ATP</span>
          <span className="h-4 w-px bg-line-2" />
          <span className="text-[12.5px] tracking-[.06em] text-ink-3">{t('login.subtitle')}</span>
        </div>

        <label className="mb-4 flex flex-col gap-1.5">
          <span className="font-mono text-[9.5px] tracking-[.14em] text-ink-4">
            {t('login.username')}
          </span>
          <input
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            className="w-full rounded-md border border-line bg-paper px-3 py-2.5 text-[13px] outline-none focus:border-line-2"
          />
        </label>

        <label className="mb-5 flex flex-col gap-1.5">
          <span className="font-mono text-[9.5px] tracking-[.14em] text-ink-4">
            {t('login.password')}
          </span>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            className="w-full rounded-md border border-line bg-paper px-3 py-2.5 text-[13px] outline-none focus:border-line-2"
          />
        </label>

        {error && (
          <div className="mb-4 rounded-md border border-shu/30 bg-shu-soft px-3 py-2 text-[11.5px] leading-[1.7] text-shu">
            {error}
          </div>
        )}

        <button
          type="submit"
          disabled={busy || !username.trim() || !password}
          className="flex w-full items-center justify-center gap-2 rounded-md bg-shu px-4 py-3 text-[13.5px] font-medium tracking-[.04em] text-white transition-colors hover:bg-shu-hover disabled:cursor-not-allowed disabled:opacity-45"
        >
          {busy ? t('login.signingIn') : t('login.signIn')}
          {!busy && <IconArrowRight size={15} strokeWidth={2} />}
        </button>

        <div className="mt-7 border-t border-line pt-5">
          <div className="mb-2.5 font-mono text-[9.5px] tracking-[.14em] text-ink-4">
            {t('login.demoAccounts')}
          </div>
          <div className="flex gap-2">
            {DEMO_ACCOUNTS.map((a) => (
              <button
                key={a.username}
                type="button"
                onClick={() => setUsername(a.username)}
                className={`flex flex-1 items-center gap-2 rounded-sm border px-2.5 py-2 text-left transition-colors ${
                  username === a.username
                    ? 'border-ink bg-ink text-paper'
                    : 'border-line bg-card text-ink-2 hover:bg-line-4'
                }`}
              >
                <span
                  className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-full font-mono text-[9.5px] ${
                    username === a.username ? 'bg-paper text-ink' : 'bg-ai text-white'
                  }`}
                >
                  {a.initials}
                </span>
                <span className="min-w-0 truncate text-[11px]">{a.display}</span>
              </button>
            ))}
          </div>
          <div className="mt-2.5 text-[10.5px] leading-[1.8] text-ink-4">{t('login.passwordHint')}</div>
        </div>
      </form>
    </div>
  );
}
