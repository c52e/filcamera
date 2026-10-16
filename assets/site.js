(() => {
  'use strict';
  const key = 'filcamera.language';
  const pageLanguage = document.documentElement.lang.startsWith('zh') ? 'zh' : 'en';
  const valid = value => value === 'en' || value === 'zh';
  const query = new URLSearchParams(location.search).get('lang');
  let saved;
  try { saved = localStorage.getItem(key); } catch (_) { /* Storage may be disabled. */ }
  if (valid(query)) {
    saved = query;
    try { localStorage.setItem(key, query); } catch (_) {}
  }
  const firstLanguage = (navigator.languages && navigator.languages[0]) || navigator.language || 'en';
  const preferred = valid(saved) ? saved : (firstLanguage.toLowerCase().startsWith('zh') ? 'zh' : 'en');
  document.querySelectorAll('[data-language]').forEach(link => {
    link.addEventListener('click', () => {
      try { localStorage.setItem(key, link.dataset.language); } catch (_) {}
    });
  });
  // Automatic detection is an entry-page convenience; direct Chinese links stay Chinese.
  if (pageLanguage === 'en' && preferred === 'zh') {
    const target = new URL('zh/', location.href);
    target.search = location.search;
    target.hash = location.hash;
    location.replace(target.href);
  } else if (pageLanguage === 'zh' && query === 'en') {
    const target = new URL('../', location.href);
    target.search = '?lang=en';
    target.hash = location.hash;
    location.replace(target.href);
  }
})();
