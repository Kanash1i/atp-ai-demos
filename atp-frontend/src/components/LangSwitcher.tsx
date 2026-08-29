import { useTranslation } from 'react-i18next';
import { LANGS, LANG_LABEL, setLang, type Lang } from '../i18n';

/** 顶栏的三语切换。Landing 与 Dashboard 共用，只有尺寸不同 */
export default function LangSwitcher({ compact = false }: { compact?: boolean }) {
  const { i18n } = useTranslation();
  const current = i18n.language as Lang;

  return (
    <div
      className={`flex items-center gap-0.5 rounded-md border border-line p-[3px] ${
        compact ? 'bg-paper' : 'bg-card'
      }`}
    >
      {LANGS.map((lang) => {
        const active = current === lang;
        return (
          <button
            key={lang}
            type="button"
            onClick={() => setLang(lang)}
            aria-pressed={active}
            className={[
              'cursor-pointer rounded-xs transition-colors',
              compact ? 'px-[9px] py-[5px] text-[11.5px]' : 'px-[10px] py-[5px] text-[12px]',
              lang === 'en' ? 'font-mono' : '',
              active ? 'bg-ink text-paper' : 'text-ink-3 hover:text-ink-2',
            ].join(' ')}
          >
            {LANG_LABEL[lang]}
          </button>
        );
      })}
    </div>
  );
}
