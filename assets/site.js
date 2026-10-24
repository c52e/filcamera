(() => {
  'use strict';
  const key = 'filcamera.language';
  const routes = {en: '', zh: 'zh/', 'zh-hant': 'zh-hant/', ja: 'ja/'};
  const valid = value => Object.hasOwn(routes, value);
  const tag = document.documentElement.lang.toLowerCase();
  const pageLanguage = tag === 'zh-hant' ? 'zh-hant' : tag.startsWith('zh') ? 'zh' : tag.startsWith('ja') ? 'ja' : 'en';
  const query = new URLSearchParams(location.search).get('lang');
  let saved;
  try { saved = localStorage.getItem(key); } catch (_) {}
  if (valid(query)) {
    saved = query;
    try { localStorage.setItem(key, query); } catch (_) {}
  }
  const first = ((navigator.languages && navigator.languages[0]) || navigator.language || 'en').toLowerCase();
  const parts = first.split('-');
  const detected = parts[0] === 'ja' ? 'ja' : parts[0] !== 'zh' ? 'en' : parts.includes('hant') ? 'zh-hant' : parts.includes('hans') ? 'zh' : parts.some(p => ['tw','hk','mo'].includes(p)) ? 'zh-hant' : 'zh';
  const preferred = valid(saved) ? saved : detected;
  document.querySelectorAll('[data-language]').forEach(link => {
    link.addEventListener('click', () => {
      try { localStorage.setItem(key, link.dataset.language); } catch (_) {}
    });
  });
  // Only the English entry detects preferences; direct localized URLs stay put.
  if ((valid(query) || pageLanguage === 'en') && pageLanguage !== preferred) {
    const root = new URL(pageLanguage === 'en' ? './' : '../', location.href);
    const target = new URL(routes[preferred], root);
    target.search = location.search;
    target.hash = location.hash;
    location.replace(target.href);
  }
})();
