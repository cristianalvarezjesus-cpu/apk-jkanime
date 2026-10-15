/*
 * Script anti-anuncios. Se ejecuta al inicio de cada documento (y dentro de iframes).
 * Hace tres cosas:
 *   1) neutraliza window.open, alert, confirm y prompt (popups),
 *   2) bloquea clics hacia otros sitios y los "overlays" invisibles que roban el primer toque,
 *   3) oculta banners típicos y corrige el viewport para que el zoom con pellizco funcione.
 */
(function () {
  'use strict';
  if (window.__visorAdblock) return;
  try {
    Object.defineProperty(window, '__visorAdblock', { value: true });
  } catch (e) {
    window.__visorAdblock = true;
  }

  // ---------- 1) Popups ----------
  function lock(obj, name, value) {
    try {
      Object.defineProperty(obj, name, { value: value, writable: false, configurable: false });
    } catch (e) {
      try { obj[name] = value; } catch (e2) { /* nada */ }
    }
  }
  lock(window, 'open', function () { return null; });
  lock(window, 'alert', function () {});
  lock(window, 'confirm', function () { return false; });
  lock(window, 'prompt', function () { return null; });

  // ---------- 2) Clics hacia otros sitios ----------
  function sameSite(hostname) {
    var a = location.hostname;
    return !hostname || hostname === a ||
      hostname.endsWith('.' + a) || a.endsWith('.' + hostname);
  }

  function isOffsite(href) {
    try {
      var u = new URL(href, location.href);
      if (u.protocol !== 'http:' && u.protocol !== 'https:') return true;
      return !sameSite(u.hostname);
    } catch (e) {
      return false;
    }
  }

  document.addEventListener('click', function (ev) {
    var t = ev.target;
    var a = t && t.closest ? t.closest('a') : null;
    if (a && a.getAttribute('href') && isOffsite(a.href)) {
      ev.preventDefault();
      ev.stopImmediatePropagation();
    }
  }, true);

  try {
    var origClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
      if (isOffsite(this.href)) return;
      return origClick.apply(this, arguments);
    };
  } catch (e) { /* nada */ }

  // ---------- 3) Ocultar anuncios ----------
  var AD_SELECTORS = [
    'ins.adsbygoogle',
    'iframe[src*="doubleclick"]',
    'iframe[src*="googlesyndication"]',
    'iframe[src*="adsterra"]',
    'iframe[src*="exoclick"]',
    'iframe[src*="popads"]',
    'iframe[src*="propeller"]',
    'iframe[src*="/ads/"]',
    '[id^="ad-"]',
    '[id^="ads-"]',
    '[id*="google_ads"]',
    '[id*="popunder"]',
    '[class*="popunder"]',
    '[class*="adsbygoogle"]',
    '[class*="banner-ad"]',
    '[class*="ad-banner"]',
    '[class~="ad"]',
    '[class~="ads"]',
    '[class~="advert"]',
    '[class~="advertisement"]',
    'a[href*="doubleclick.net"]',
    'a[href*="popads"]',
    'a[href*="adsterra"]'
  ];
  var SELECTOR = AD_SELECTORS.join(',');

  function addStyle() {
    if (document.getElementById('__visor_css')) return;
    var s = document.createElement('style');
    s.id = '__visor_css';
    s.textContent = SELECTOR + '{display:none!important;visibility:hidden!important;' +
      'width:0!important;height:0!important;pointer-events:none!important;}';
    (document.head || document.documentElement).appendChild(s);
  }

  // Capa invisible a pantalla completa que roba el primer toque para abrir un popup
  function isClickjackOverlay(el) {
    if (!el || el.nodeType !== 1) return false;
    var tag = el.tagName;
    if (tag === 'VIDEO' || tag === 'IFRAME' || tag === 'INPUT' || tag === 'BODY' || tag === 'HTML') return false;
    var cs = getComputedStyle(el);
    if (cs.position !== 'fixed' && cs.position !== 'absolute') return false;
    var z = parseInt(cs.zIndex, 10);
    if (isNaN(z) || z < 1000) return false;
    var r = el.getBoundingClientRect();
    if (r.width < innerWidth * 0.9 || r.height < innerHeight * 0.9) return false;
    if ((el.textContent || '').trim().length > 0) return false;
    if (el.querySelector('video,iframe,input,button,img,canvas')) return false;
    var bg = cs.backgroundColor;
    return parseFloat(cs.opacity) < 0.1 || bg === 'rgba(0, 0, 0, 0)' || bg === 'transparent';
  }

  // Permite el zoom con pellizco aunque la página lo desactive
  var VIEWPORT = 'width=device-width, initial-scale=1, minimum-scale=0.5, maximum-scale=5, user-scalable=yes';
  function fixViewport() {
    if (window.top !== window || !document.head) return;
    var m = document.querySelector('meta[name="viewport"]');
    if (!m) {
      m = document.createElement('meta');
      m.name = 'viewport';
      document.head.appendChild(m);
    }
    if (m.getAttribute('content') !== VIEWPORT) m.setAttribute('content', VIEWPORT);
  }

  function cleanup() {
    try {
      addStyle();
      fixViewport();
      document.querySelectorAll('iframe').forEach(function (f) {
        if (f.matches(SELECTOR)) f.remove();
      });
      if (document.body) {
        Array.prototype.forEach.call(document.body.children, function (el) {
          if (isClickjackOverlay(el)) el.remove();
        });
      }
    } catch (e) { /* nunca romper la página */ }
  }

  addStyle();

  var timer = null;
  function schedule() {
    if (timer) return;
    timer = setTimeout(function () { timer = null; cleanup(); }, 250);
  }

  try {
    new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
  } catch (e) { /* nada */ }

  document.addEventListener('DOMContentLoaded', cleanup);
  window.addEventListener('load', cleanup);
  [500, 1500, 3000, 6000].forEach(function (ms) { setTimeout(cleanup, ms); });
})();
