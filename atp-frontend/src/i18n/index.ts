import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import zh from './zh';
import ja from './ja';
import en from './en';

export const LANGS = ['zh', 'ja', 'en'] as const;
export type Lang = (typeof LANGS)[number];

export const LANG_LABEL: Record<Lang, string> = {
  zh: '简体',
  ja: '日本語',
  en: 'EN',
};

const STORAGE_KEY = 'atp.lang';

function initialLang(): Lang {
  const saved = typeof localStorage !== 'undefined' ? localStorage.getItem(STORAGE_KEY) : null;
  if (saved && (LANGS as readonly string[]).includes(saved)) return saved as Lang;
  const nav = typeof navigator !== 'undefined' ? navigator.language.toLowerCase() : 'zh';
  if (nav.startsWith('ja')) return 'ja';
  if (nav.startsWith('zh')) return 'zh';
  return 'en';
}

void i18n.use(initReactI18next).init({
  resources: {
    zh: { translation: zh },
    ja: { translation: ja },
    en: { translation: en },
  },
  lng: initialLang(),
  fallbackLng: 'zh',
  interpolation: { escapeValue: false },
  returnObjects: true,
});

export function setLang(lang: Lang): void {
  void i18n.changeLanguage(lang);
  try {
    localStorage.setItem(STORAGE_KEY, lang);
  } catch {
    /* 隐私模式下写不进去，不影响使用 */
  }
  document.documentElement.lang = lang === 'zh' ? 'zh-CN' : lang === 'ja' ? 'ja' : 'en';
}

export default i18n;
