/*!
 * SkillPulse - collapsible header / navigation
 * Drop-in for every page:  <script src="assets/js/collapsible-header.js"></script>
 *
 * Finds the page's main navigation (top nav bar on the landing page, sidebar on the app pages),
 * adds a small toggle tab and slides the navigation out of the way. The state is shared across
 * pages through localStorage, so it stays collapsed while you move around the app.
 */
(function () {
  'use strict';
  if (window.__spCollapsibleHeader) return;
  window.__spCollapsibleHeader = true;

  var STORAGE_KEY = 'sp-header-collapsed';
  var SELECTORS = [
    '#nav', 'aside.sidebar', 'nav.sidebar', '.sidebar', 'aside',
    '.dash-nav', '.topbar', '.top-bar', '.navbar', 'body > header'
  ];
  var EASE = 'cubic-bezier(.22,1,.36,1)';
  var EL_TRANSITION = 'transform .4s ' + EASE + ', margin .4s ' + EASE + ', opacity .3s, padding .4s ' + EASE;
  var PARENT_TRANSITION = 'grid-template-columns .4s ' + EASE;

  function findTarget() {
    for (var i = 0; i < SELECTORS.length; i++) {
      var list = document.querySelectorAll(SELECTORS[i]);
      for (var j = 0; j < list.length; j++) {
        var r = list[j].getBoundingClientRect();
        if (r.width > 0 && r.height > 0) return list[j];
      }
    }
    return null;
  }

  function readState() {
    try { return localStorage.getItem(STORAGE_KEY) === '1'; } catch (e) { return false; }
  }
  function writeState(v) {
    try { localStorage.setItem(STORAGE_KEY, v ? '1' : '0'); } catch (e) {}
  }

  function init() {
    var el = findTarget();
    if (!el) return;                         // login / forgot-password etc.: nothing to collapse
    var parent = el.parentElement;

    /* ---------- toggle button ---------- */
    var css = document.createElement('style');
    css.textContent =
      '.sp-hdr-toggle{position:fixed;z-index:100;display:flex;align-items:center;justify-content:center;' +
      'background:#fffaf7;color:#9b7464;border:1px solid #eadbd2;padding:0;' +
      'box-shadow:0 6px 16px rgba(140,100,80,.16);' +
      'transition:top .4s ' + EASE + ',left .4s ' + EASE + ',background .25s,color .25s}' +
      '.sp-hdr-toggle:hover,.sp-hdr-toggle:focus-visible{background:#9b7464;color:#fff;outline:none}' +
      '.sp-hdr-toggle svg{width:14px;height:14px;transition:transform .35s}' +
      '.sp-hdr-toggle.sp-top{width:54px;height:22px;transform:translateX(-50%);border-top:none;border-radius:0 0 14px 14px}' +
      '.sp-hdr-toggle.sp-top.sp-collapsed svg{transform:rotate(180deg)}' +
      '.sp-hdr-toggle.sp-side{width:22px;height:54px;border-left:none;border-radius:0 14px 14px 0}' +
      '.sp-hdr-toggle.sp-side svg{transform:rotate(-90deg)}' +
      '.sp-hdr-toggle.sp-side.sp-collapsed svg{transform:rotate(90deg)}';
    document.head.appendChild(css);

    var btn = document.createElement('button');
    btn.type = 'button';
    btn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" ' +
      'stroke-linecap="round" stroke-linejoin="round"><polyline points="6 15 12 9 18 15"/></svg>';
    btn.style.cursor = (getComputedStyle(document.body).cursor === 'none') ? 'none' : 'pointer';
    document.body.appendChild(btn);

    /* ---------- state ---------- */
    var collapsed = false;
    var mode = 'top';        // 'top' = bar across the top, 'side' = sidebar; decided while expanded
    var size = 0;            // height (top) or width (side) while expanded
    var savedEl = null, savedParent = null;

    function detectMode() {
      var r = el.getBoundingClientRect();
      mode = (r.height > r.width && r.width < window.innerWidth * 0.6) ? 'side' : 'top';
      size = (mode === 'side') ? r.width : r.height;
    }

    function placeButton() {
      btn.className = 'sp-hdr-toggle sp-' + mode + (collapsed ? ' sp-collapsed' : '');
      btn.setAttribute('aria-expanded', String(!collapsed));
      btn.setAttribute('aria-label', collapsed ? 'Show navigation' : 'Hide navigation');
      btn.title = collapsed ? 'Show navigation' : 'Hide navigation';
      if (mode === 'top') {
        btn.style.left = '50%';
        btn.style.top = collapsed ? '0px' : size + 'px';
      } else {
        btn.style.top = '14px';
        btn.style.left = collapsed ? '0px' : size + 'px';
      }
    }

    function restoreAttr(node, value) {
      if (!node) return;
      if (value === null || value === '') node.removeAttribute('style');
      else node.setAttribute('style', value);
    }

    function collapse(animate) {
      savedEl = el.getAttribute('style');
      savedParent = parent ? parent.getAttribute('style') : null;
      detectMode();
      collapsed = true;

      var pos = getComputedStyle(el).position;
      var isGridParent = parent && getComputedStyle(parent).display.indexOf('grid') !== -1;

      el.style.transition = animate ? EL_TRANSITION : 'none';
      if (parent) parent.style.transition = animate ? PARENT_TRANSITION : 'none';
      el.style.pointerEvents = 'none';

      if (mode === 'top') {
        el.style.transform = 'translateY(-100%)';
        if (pos !== 'fixed') {
          if (pos === 'sticky' || pos === 'static') el.style.position = 'relative';
          el.style.marginTop = (-size) + 'px';
        }
      } else if (pos === 'fixed') {
        el.style.transform = 'translateX(-100%)';
      } else if (isGridParent) {
        parent.style.gridTemplateColumns = '0px minmax(0,1fr)';
        el.style.minWidth = '0';
        el.style.overflow = 'hidden';
        el.style.padding = '0';
        el.style.borderWidth = '0';
        el.style.opacity = '0';
      } else {
        el.style.marginLeft = (-size) + 'px';
        el.style.opacity = '0';
      }

      if (!animate) {                        // no animation on page load: flush, then allow later transitions
        void el.offsetWidth;
        el.style.transition = EL_TRANSITION;
        if (parent) parent.style.transition = PARENT_TRANSITION;
      }
      placeButton();
      writeState(true);
    }

    function expand(animate) {
      collapsed = false;
      if (animate) {
        // switch back to the original inline styles but keep a transition so it slides out smoothly
        restoreAttr(el, (savedEl ? savedEl + ';' : '') + 'transition:' + EL_TRANSITION);
        if (parent) restoreAttr(parent, (savedParent ? savedParent + ';' : '') + 'transition:' + PARENT_TRANSITION);
        setTimeout(function () {
          if (collapsed) return;
          restoreAttr(el, savedEl);
          if (parent) restoreAttr(parent, savedParent);
        }, 480);
      } else {
        restoreAttr(el, savedEl);
        if (parent) restoreAttr(parent, savedParent);
      }
      placeButton();
      writeState(false);
    }

    /* ---------- wiring ---------- */
    detectMode();
    placeButton();

    btn.addEventListener('click', function () {
      if (collapsed) expand(true); else collapse(true);
    });

    // Breakpoints can turn a sidebar into a top row (and back), so re-evaluate on resize.
    var resizeTimer;
    window.addEventListener('resize', function () {
      clearTimeout(resizeTimer);
      resizeTimer = setTimeout(function () {
        if (collapsed) { expand(false); collapse(false); }
        else { detectMode(); placeButton(); }
      }, 150);
    });

    if (readState()) collapse(false);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
