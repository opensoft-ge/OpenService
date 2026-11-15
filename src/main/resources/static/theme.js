(function initializeTheme() {
  'use strict';
  const root = document.documentElement;
  const system = window.matchMedia('(prefers-color-scheme: dark)');
  let preference;
  try {
    preference = localStorage.getItem('openservice.theme');
  } catch {
    preference = null;
  }
  function apply() {
    const dark = preference === 'dark' || (preference !== 'light' && system.matches);
    root.dataset.theme = dark ? 'dark' : 'light';
    document.querySelectorAll('[data-theme-toggle]').forEach(function updateToggle(button) {
      button.hidden = false;
      button.setAttribute('aria-pressed', String(dark));
    });
  }
  // Run before the stylesheet loads, then update the controls once HTML is ready.
  apply();
  document.addEventListener('DOMContentLoaded', function ready() {
    apply();
    document.querySelectorAll('[data-theme-toggle]').forEach(function bindToggle(button) {
      button.addEventListener('click', function toggleTheme() {
        preference = root.dataset.theme === 'dark' ? 'light' : 'dark';
        try {
          localStorage.setItem('openservice.theme', preference);
        } catch {
          // The current page remains usable when browser storage is unavailable.
        }
        apply();
      });
    });
  });
  system.addEventListener('change', apply);
})();
