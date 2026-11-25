// Tab bar shared by every page: a bottom bar on a phone, a row of tabs at the top on a laptop.
// A page names its tab with <body data-tab="…">; a sub-page (Latest light test) names its parent's tab.
// The last tab used is remembered per device, and the home page (index.html) opens it.
(() => {
  const TABS = [
    { key: 'show', href: 'show.html', label: 'Show Control', short: 'Show',
      icon: '<circle cx="12" cy="12" r="9"/><path d="M10 8.5v7l5.5-3.5z" fill="currentColor"/>' },
    { key: 'controllers', href: 'controllers.html', label: 'Controllers', short: 'Controllers',
      icon: '<rect x="4" y="4" width="6.5" height="6.5" rx="1.5"/><rect x="13.5" y="4" width="6.5" height="6.5" rx="1.5"/><rect x="4" y="13.5" width="6.5" height="6.5" rx="1.5"/><rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.5"/>' },
    { key: 'preshow', href: 'preshow.html', label: 'Pre-show check', short: 'Pre-show',
      icon: '<path d="M9 5h10M9 12h10M9 19h10"/><path d="M3.5 5l1.3 1.3L7 4M3.5 12l1.3 1.3L7 11M3.5 19l1.3 1.3L7 18"/>' },
    { key: 'measure', href: 'measure.html', label: 'Boundary finder', short: 'Boundaries',
      icon: '<rect x="2.5" y="8" width="19" height="8" rx="1.5"/><path d="M6.5 8v3.5M10.5 8v2M14.5 8v3.5M18.5 8v2"/>' },
  ];
  const STORE_KEY = 'lsc.lastTab';

  const current = document.body.dataset.tab;
  const tab = TABS.find(t => t.key === current);
  if (tab) {
    try { localStorage.setItem(STORE_KEY, tab.href); } catch { /* private window: nothing to remember */ }
  }

  const nav = document.createElement('nav');
  nav.className = 'tabbar';
  nav.setAttribute('aria-label', 'Pages');
  nav.innerHTML = TABS.map(t => {
    const on = t.key === current;
    return `<a href="${t.href}" class="tab${on ? ' active' : ''}"${on ? ' aria-current="page"' : ''}>`
      + `<svg class="tab-icon" viewBox="0 0 24 24" aria-hidden="true">${t.icon}</svg>`
      + `<span class="tab-label tab-long">${t.label}</span><span class="tab-label tab-short">${t.short}</span></a>`;
  }).join('');
  document.body.prepend(nav);
  document.body.classList.add('has-tabbar');
})();
