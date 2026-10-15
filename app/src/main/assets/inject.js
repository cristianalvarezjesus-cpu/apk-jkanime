/*
 * Script anti-anuncios. Se ejecuta al inicio de cada documento (incluidos iframes).
 *
 * Versión corregida para no romper la búsqueda de jkanime:
 *  - Sección 3: beforeunload sólo bloquea si NO es navegación del propio sitio.
 *  - Sección 4: clics target=_blank del mismo host se dejan navegar en la pestaña actual.
 *  - Sección 5: ya NO se bloquea navigator.serviceWorker (jkanime lo usa para cacheo).
 *  - Sección 8: scriptlets más suaves, sin tocar location.assign.
 */
(function () {
  'use strict';
  if (window.__visorInjected) return;
  window.__visorInjected = true;

  const log = (...a) => { try { console.log('[visor]', ...a); } catch (e) {} };

  // ---------- 1) Bloquear ventanas emergentes ----------
  try {
    Object.defineProperty(window, 'open', {
      configurable: false, writable: false,
      value: function () { log('window.open bloqueado'); return null; }
    });
  } catch (e) { window.open = function () { return null; }; }

  // ---------- 2) Silenciar diálogos ----------
  window.alert   = function () {};
  window.confirm = function () { return false; };
  window.prompt  = function () { return null; };

  // ---------- 3) beforeunload: sólo bloquea si el target NO es del sitio ----------
  window.addEventListener('beforeunload', function (e) {
    try {
      const t = document.activeElement;
      if (t && t.tagName === 'A' && t.href) {
        const u = new URL(t.href, location.href);
        if (u.host === location.host) return;
      }
    } catch (_) {}
    e.stopImmediatePropagation();
    delete e.returnValue;
  }, true);

  // ---------- 4) Interceptar clicks sólo si van fuera del sitio ----------
  document.addEventListener('click', function (e) {
    let el = e.target;
    while (el && el.tagName !== 'A') el = el.parentElement;
    if (!el) return;

    const href = el.getAttribute('href') || '';
    const target = (el.getAttribute('target') || '').toLowerCase();

    if (/^javascript:/i.test(href)) {
      if (/\b(open|popunder|popup)\s*\(/i.test(href)) {
        e.preventDefault(); e.stopPropagation();
        log('click js: bloqueado', href);
        return false;
      }
      return;
    }

    if (target === '_blank' && href) {
      try {
        const url = new URL(href, location.href);
        if (url.host === location.host) {
          e.preventDefault();
          location.href = url.href;
          return false;
        }
        e.preventDefault(); e.stopPropagation();
        log('target=_blank externo bloqueado', url.href);
        return false;
      } catch (_) {}
    }
  }, true);

  // ---------- 5) Push sí, Service Worker no ----------
  try {
    if (window.Notification && Notification.requestPermission) {
      Notification.requestPermission = function () { return Promise.resolve('denied'); };
    }
    if (window.PushManager) window.PushManager = undefined;
  } catch (_) {}

  // ---------- 6) Overlays y ads inyectados (MutationObserver) ----------
  const AD_HINTS = [
    'popup', 'popunder', 'ads', 'advert', 'banner', 'overlay', 'interstitial',
    'preroll', 'pre-roll', 'midroll', 'vast', 'vpaid', 'sponsor', 'publicidad', 'publi'
  ];
  const SAFE_HOSTS = [
    'jkanime.net', 'mp4upload', 'streamtape', 'dood', 'fembed', 'ok.ru',
    'yourupload', 'mixdrop', 'videobin', 'filemoon', 'streamlare', 'voe',
    'vidhide', 'vidsrc', 'youtube', 'ytimg', 'googlevideo', 'dailymotion',
    'vimeo', 'jwplayer', 'jwpcdn', 'akamaized', 'cloudflare'
  ];

  function looksLikeAd(el) {
    if (!el || !el.tagName) return false;
    const src = ((el.src || el.getAttribute('data-src') || '') + '').toLowerCase();
    if (SAFE_HOSTS.some(h => src.includes(h))) return false;
    const idc = ((el.id || '') + ' ' + (el.className || '')).toLowerCase();
    return AD_HINTS.some(h => idc.includes(h));
  }

  function scrubRoot(root) {
    if (!root || !root.querySelectorAll) return;
    try {
      root.querySelectorAll(
        'iframe,ins,object,embed,[id*="ad" i],[class*="ad-" i],[class*="popup" i],[class*="banner" i]'
      ).forEach(el => {
        const src = ((el.src || el.getAttribute('data-src') || '') + '').toLowerCase();
        if (SAFE_HOSTS.some(h => src.includes(h))) return;
        if (looksLikeAd(el)) { el.remove(); log('removed', el.tagName, el.id || el.className); }
      });

      root.querySelectorAll('div,section,aside').forEach(el => {
        const st = getComputedStyle(el);
        if (st.position !== 'fixed' && st.position !== 'absolute') return;
        const z = parseInt(st.zIndex, 10);
        if (isNaN(z) || z < 1000) return;
        if (el.clientWidth < window.innerWidth * 0.5) return;
        if (el.clientHeight < window.innerHeight * 0.5) return;
        if (el.querySelector('video,iframe')) return;
        if (looksLikeAd(el)) { el.remove(); log('overlay removed', el.id || el.className); }
      });
    } catch (_) {}
  }

  function startScrub() {
    scrubRoot(document);
    const mo = new MutationObserver(muts => {
      for (const m of muts) {
        m.addedNodes.forEach(n => { if (n.nodeType === 1) scrubRoot(n); });
      }
    });
    mo.observe(document.documentElement, { childList: true, subtree: true });
    setInterval(() => scrubRoot(document), 2000);
  }

  // ---------- 7) Anti-video-ads ----------
  document.addEventListener('play', e => {
    const v = e.target;
    if (!(v instanceof HTMLVideoElement)) return;
    const parent = v.parentElement;
    if (!parent) return;
    parent.querySelectorAll('[class*="ad-" i],[id*="ad-" i],[class*="preroll" i]')
      .forEach(el => el.remove());
  }, true);

  // ---------- 8) API de scriptlets ----------
  const scriptlets = {
    nowebrtc: () => {
      const noop = function () {};
      window.RTCPeerConnection = noop;
      window.webkitRTCPeerConnection = noop;
    },
    'prevent-popunder': () => {
      window.open = function () { return null; };
    },
    'no-slow-setInterval': () => {
      const orig = window.setInterval;
      window.setInterval = function (fn, ms) {
        if (typeof ms === 'number' && ms < 30) {
          return orig.call(this, function () {}, 1000);
        }
        return orig.apply(this, arguments);
      };
    },
    'block-beforeunload': () => {
      window.addEventListener('beforeunload', function (e) {
        e.stopImmediatePropagation();
        delete e.returnValue;
      }, true);
    },
    'abort-on-property-read': (prop) => {
      try {
        Object.defineProperty(window, prop, {
          get() { throw new ReferenceError(prop + ' aborted'); },
          configurable: false,
        });
      } catch (_) {}
    },
    'set-constant': (prop, val) => {
      try {
        Object.defineProperty(window, prop, {
          get() { try { return JSON.parse(val); } catch (_) { return val; } },
          set() {},
          configurable: false,
        });
      } catch (_) {}
    },
  };

  window.__visorScriptlet = function (call) {
    try {
      const inner = call.substring(4, call.length - 1);
      const args = inner.split(',').map(s => s.trim().replace(/^['"]|['"]$/g, ''));
      const name = args.shift();
      const fn = scriptlets[name];
      if (fn) fn.apply(null, args);
    } catch (e) { log('scriptlet err', e); }
  };

  // ---------- 9) Aplicar cosmético + scriptlets ----------
  window.__visorApply = function (css, scriptletList) {
    try {
      if (css) {
        const s = document.createElement('style');
        s.textContent = css;
        (document.head || document.documentElement).appendChild(s);
      }
      (scriptletList || []).forEach(window.__visorScriptlet);
    } catch (e) { log('apply err', e); }
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', startScrub, { once: true });
  } else {
    startScrub();
  }

  log('inject.js listo');
})();
