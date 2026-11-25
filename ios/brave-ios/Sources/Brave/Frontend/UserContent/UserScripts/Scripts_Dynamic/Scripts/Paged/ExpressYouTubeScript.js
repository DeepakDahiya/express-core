// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

// Express Browser's YouTube changes, ported from the Android app's
// youtube_script_injector_tab_helper.cc. They run in the page on youtube.com only, top frame
// only, once per document, and tell the app (ExpressYouTubeScriptHandler) when YouTube pages
// show and when picture in picture starts or ends. The app does the rest of Android's flow:
// YouTube home while the video floats, the watch page back when it's expanded.

(function() {
  'use strict';
  if (window.top !== window) return;
  if (!/(^|\.)youtube(-nocookie)?\.com$/.test(window.location.hostname)) return;
  if (window.__expressYouTubeActive) return;
  window.__expressYouTubeActive = true;

  // Messages to the app. postMessage returns a promise of the app's reply.
  function post(event, extra) {
    var message = { securityToken: SECURITY_TOKEN, event: event };
    if (extra) { for (var key in extra) { message[key] = extra[key]; } }
    return window.webkit.messageHandlers['$<message_handler>'].postMessage(message);
  }
  function notify(event, extra) {
    try {
      var reply = post(event, extra);
      if (reply && reply.catch) { reply.catch(function() {}); }
    } catch (e) {}
  }
  // A question for the app; resolves with its answer, or undefined when it can't be asked.
  function ask(event, extra) {
    try {
      return Promise.resolve(post(event, extra)).catch(function() { return undefined; });
    } catch (e) {
      return Promise.resolve(undefined);
    }
  }

  // YouTube's in-page navigation finished: www sends `yt-navigate-finish`, m.youtube.com sends
  // `state-navigateend` instead (Android's scripts only listen for the first).
  function onYouTubeNavigation(handler) {
    window.addEventListener('yt-navigate-finish', handler);
    window.addEventListener('state-navigateend', handler);
  }

  // Page views, including YouTube's in-page navigation (usage, and the background-play tip
  // closing when the reader moves on to another page). Once per address.
  var lastPageViewURL = null;
  function notifyPageView() {
    if (window.location.href === lastPageViewURL) return;
    lastPageViewURL = window.location.href;
    notify('pageView', { path: window.location.pathname, url: window.location.href });
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', notifyPageView);
  } else {
    notifyPageView();
  }
  onYouTubeNavigation(notifyPageView);

  // Picture in picture starting and ending. WebKit sends both the standard events and its own
  // presentation-mode event, so keep one state.
  var inPictureInPicture = false;
  function pictureInPictureChanged(active, video) {
    if (active === inPictureInPicture) return;
    inPictureInPicture = active;
    updatePipButton();
    if (active) {
      notify('pipEnter');
    } else {
      // Expanding keeps the video playing; closing pauses it.
      setTimeout(function() {
        notify('pipLeave', { playing: !!(video && !video.paused && !video.ended) });
      }, 400);
    }
  }
  document.addEventListener('enterpictureinpicture', function(e) { pictureInPictureChanged(true, e.target); }, true);
  document.addEventListener('leavepictureinpicture', function(e) { pictureInPictureChanged(false, e.target); }, true);
  document.addEventListener('webkitpresentationmodechanged', function(e) {
    var video = e.target;
    if (!video || video.tagName !== 'VIDEO') return;
    pictureInPictureChanged(video.webkitPresentationMode === 'picture-in-picture', video);
  }, true);

  // A picture-in-picture button on watch pages. It's in the page so the tap counts as the
  // reader's own, which WebKit needs before it starts picture in picture. When it comes and
  // goes the app hears it (`pipAvailable`, Android's isPictureInPictureAvailable): the
  // background-play tip and Back wait for it. Each document tells the app its first state.
  // Android injects the same button but keeps it hidden (`kYoutubePipButton`): picture in
  // picture starts from the gold button in the address bar, or from Back. So is it here; the
  // availability messages still go out.
  var SHOW_IN_PAGE_PIP_BUTTON = false;
  var pipButton = null;
  var lastPipAvailable = null;
  function startPictureInPicture() {
    var video = document.querySelector('video');
    if (!video) return;
    video.removeAttribute('disablepictureinpicture');
    if (video.paused) { try { video.play(); } catch (e) {} }
    function fallback() {
      if (video.webkitSupportsPresentationMode && video.webkitSupportsPresentationMode('picture-in-picture')) {
        video.webkitSetPresentationMode('picture-in-picture');
      }
    }
    if (video.requestPictureInPicture) {
      video.requestPictureInPicture().catch(fallback);
    } else {
      fallback();
    }
  }
  function updatePipButton() {
    var show = window.location.pathname === '/watch' && !inPictureInPicture && !!document.querySelector('video');
    if (show !== lastPipAvailable) {
      lastPipAvailable = show;
      notify('pipAvailable', { available: show, url: window.location.href });
    }
    if (SHOW_IN_PAGE_PIP_BUTTON && show && !pipButton && document.body) {
      pipButton = document.createElement('button');
      pipButton.setAttribute('aria-label', 'Picture in picture');
      pipButton.title = 'Picture in picture';
      pipButton.style.cssText = [
        'position:fixed', 'right:16px', 'bottom:calc(env(safe-area-inset-bottom, 0px) + 96px)',
        'z-index:2147483647', 'width:52px', 'height:52px', 'border-radius:26px', 'border:none',
        'background:#D4AF37', 'box-shadow:0 4px 12px rgba(0,0,0,.30)', 'display:flex',
        'align-items:center', 'justify-content:center', 'padding:0', 'cursor:pointer',
        'touch-action:manipulation'
      ].join(';');
      // Built node by node: YouTube's Trusted Types policy rejects HTML strings.
      var svgNS = 'http://www.w3.org/2000/svg';
      var svg = document.createElementNS(svgNS, 'svg');
      var svgAttributes = {
        width: '26', height: '26', viewBox: '0 0 24 24', fill: 'none', stroke: '#1A1A2E',
        'stroke-width': '2', 'stroke-linecap': 'round', 'stroke-linejoin': 'round'
      };
      for (var name in svgAttributes) { svg.setAttribute(name, svgAttributes[name]); }
      var frame = document.createElementNS(svgNS, 'rect');
      [['x', '2.5'], ['y', '4.5'], ['width', '19'], ['height', '15'], ['rx', '2.5']].forEach(function(a) {
        frame.setAttribute(a[0], a[1]);
      });
      var inset = document.createElementNS(svgNS, 'rect');
      [['x', '12'], ['y', '11.5'], ['width', '7'], ['height', '5.5'], ['rx', '1.2'], ['fill', '#1A1A2E'],
       ['stroke', 'none']].forEach(function(a) { inset.setAttribute(a[0], a[1]); });
      svg.appendChild(frame);
      svg.appendChild(inset);
      pipButton.appendChild(svg);
      pipButton.addEventListener('click', function(e) {
        e.preventDefault();
        e.stopPropagation();
        startPictureInPicture();
      });
      document.body.appendChild(pipButton);
    }
    if (pipButton) { pipButton.style.display = show ? 'flex' : 'none'; }
  }
  onYouTubeNavigation(updatePipButton);
  setInterval(updatePipButton, 1000);

  // Background play: YouTube pauses when the page looks hidden, and stops after a while without
  // input. Ported from Android's kYoutubeBackgroundPlayback2 and kYoutubeBackgroundPlayback.
  (function() {
      'use strict';

      const IS_YOUTUBE = window.location.hostname.search(/(?:^|.+\.)youtube\.com/) > -1 ||
                      window.location.hostname.search(/(?:^|.+\.)youtube-nocookie\.com/) > -1;
      const IS_MOBILE_YOUTUBE = window.location.hostname == 'm.youtube.com';
      const IS_DESKTOP_YOUTUBE = IS_YOUTUBE && !IS_MOBILE_YOUTUBE;
      const IS_VIMEO = window.location.hostname.search(/(?:^|.+\.)vimeo\.com/) > -1;

      const IS_ANDROID = window.navigator.userAgent.indexOf('Android') > -1;

      // Page Visibility API
      if (IS_ANDROID || !IS_DESKTOP_YOUTUBE) {
      try {
        Object.defineProperties(document,
            { 'hidden': {value: false}, 'visibilityState': {value: 'visible'} });
      } catch (e) {
        // Newer Chromium builds may expose non-configurable visibility
        // properties. Background playback still works through the
        // visibilitychange listener guard below.
      }
      }

      window.addEventListener(
      'visibilitychange', evt => evt.stopImmediatePropagation(), true);

      // Fullscreen API
      if (IS_VIMEO) {
      window.addEventListener(
          'fullscreenchange', evt => evt.stopImmediatePropagation(), true);
      }

      // User activity tracking
      if (IS_YOUTUBE) {
      loop(pressKey, 60 * 1000, 10 * 1000); // every minute +/- 10 seconds
      }

      function pressKey() {
      const key = 18;
      sendKeyEvent("keydown", key);
      sendKeyEvent("keyup", key);
      }

      function sendKeyEvent (aEvent, aKey) {
      document.dispatchEvent(new KeyboardEvent(aEvent, {
          bubbles: true,
          cancelable: true,
          keyCode: aKey,
          which: aKey,
      }));
      }

      function loop(aCallback, aDelay, aJitter) {
      let jitter = getRandomInt(-aJitter/2, aJitter/2);
      let delay = Math.max(aDelay + jitter, 0);

      window.setTimeout(() => {
                          aCallback();
                          loop(aCallback, aDelay, aJitter);
                          }, delay);
      }

      function getRandomInt(aMin, aMax) {
      let min = Math.ceil(aMin);
      let max = Math.floor(aMax);
      return Math.floor(Math.random() * (max - min)) + min;
      }

  })();
  (function() {
    if (document._addEventListener === undefined) {
      document._addEventListener = document.addEventListener;
      document.addEventListener = function(a, b, c) {
        if (a != 'visibilitychange') {
          document._addEventListener(a, b, c);
        }
      };
    }
  }());

  // No autoplay on the home feed, search and other lists; watch pages and Shorts still play.
  // Android's kYoutubeDisableHomeAutoplay.
  (function() {
      'use strict';

      function isWatchPage() {
          return window.location.pathname === '/watch' ||
                 window.location.pathname.startsWith('/shorts/');
      }

      // On non-watch pages (home feed, search, etc.), prevent inline video autoplay.
      // Installed on every page, not only one that starts on a list: YouTube is a single-page
      // app, so a tab that opened on a video and then went back (or home) to the feed would
      // otherwise autoplay there. Both hooks check the page each time.
      {
          // Override play() to block autoplay on feed pages
          const originalPlay = HTMLMediaElement.prototype.play;
          HTMLMediaElement.prototype.play = function() {
              if (!isWatchPage()) {
                  this.pause();
                  return Promise.resolve();
              }
              // On a watch page, undo the feed's preload='none' that is still
              // stuck on this (reused) player element -- otherwise the video
              // stalls for a few seconds before it even starts buffering.
              if (this.preload === 'none') {
                  this.preload = 'auto';
              }
              return originalPlay.call(this);
          };

          // Observe for dynamically added video elements and pause them
          const observer = new MutationObserver((mutations) => {
              if (isWatchPage()) return;
              mutations.forEach(mutation => {
                  mutation.addedNodes.forEach(node => {
                      if (node.nodeType === 1) {
                          const videos = node.tagName === 'VIDEO'
                              ? [node]
                              : (node.querySelectorAll ? node.querySelectorAll('video') : []);
                          videos.forEach(v => {
                              v.autoplay = false;
                              v.preload = 'none';
                              if (!v.paused) v.pause();
                          });
                      }
                  });
              });
          });

          if (document.body) {
              observer.observe(document.body, { childList: true, subtree: true });
          } else {
              document.addEventListener('DOMContentLoaded', () => {
                  observer.observe(document.body, { childList: true, subtree: true });
              });
          }

          // Also catch any existing videos on the page
          if (!isWatchPage()) {
              document.querySelectorAll('video').forEach(v => {
                  v.autoplay = false;
                  v.preload = 'none';
                  if (!v.paused) v.pause();
              });
          }
      }

      // Re-check on YouTube SPA navigation: `yt-navigate-finish` on www, `state-navigateend` on
      // m.youtube.com (the mobile site doesn't send the www event).
      const onNavigated = () => {
          if (!isWatchPage()) {
              document.querySelectorAll('video').forEach(v => {
                  v.autoplay = false;
                  v.preload = 'none';
                  if (!v.paused) v.pause();
              });
          } else {
              // Landing on a watch page: restore the (reused) player so it
              // buffers and starts right away instead of inheriting the feed's
              // preload='none' / autoplay=false, which delayed playback by a
              // few seconds when opening a video from the feed.
              document.querySelectorAll('video').forEach(v => {
                  if (v.preload === 'none') {
                      v.preload = 'auto';
                  }
                  v.autoplay = true;
              });
          }
      };
      onYouTubeNavigation(onNavigated);
  })();

  // Picture in picture: switch off YouTube's flags that block it (Android's kYoutubePIP), and
  // drop the attribute WebKit honours to refuse it.
  (function() {
      // Function to modify the flags if the target object exists.
      function modifyYtcfgFlags() {
          const config = window.ytcfg.get("WEB_PLAYER_CONTEXT_CONFIGS")?.WEB_PLAYER_CONTEXT_CONFIG_ID_MWEB_WATCH
          if (config && config.serializedExperimentFlags && typeof config.serializedExperimentFlags === 'string') {
              let flags = config.serializedExperimentFlags;

              // Replace target flags.
              flags = flags
                  .replace(
                  "html5_picture_in_picture_blocking_ontimeupdate=true",
                  "html5_picture_in_picture_blocking_ontimeupdate=false")
                  .replace("html5_picture_in_picture_blocking_onresize=true",
                  "html5_picture_in_picture_blocking_onresize=false")
                  .replace(
                  "html5_picture_in_picture_blocking_document_fullscreen=true",
                  "html5_picture_in_picture_blocking_document_fullscreen=false"
                  )
                  .replace(
                  "html5_picture_in_picture_blocking_standard_api=true",
                  "html5_picture_in_picture_blocking_standard_api=false")
                  .replace("html5_picture_in_picture_logging_onresize=true",
                  "html5_picture_in_picture_logging_onresize=false");

              // Assign updated flags back to config.
              config.serializedExperimentFlags = flags;
          }
      }

      if (window.ytcfg) {
          modifyYtcfgFlags();
      } else {
          document.addEventListener('load', (event) => {
              const target = event.target;
              if (target.tagName === 'SCRIPT' && window.ytcfg) {
                  modifyYtcfgFlags();
              }
          }, true);
      }
  }());
  (function() {
    function allowPictureInPicture(root) {
      (root.querySelectorAll ? root.querySelectorAll('video[disablepictureinpicture]') : []).forEach(function(v) {
        v.removeAttribute('disablepictureinpicture');
      });
    }
    function start() {
      allowPictureInPicture(document);
      new MutationObserver(function(mutations) {
        mutations.forEach(function(m) {
          if (m.type === 'attributes' && m.target.tagName === 'VIDEO') {
            m.target.removeAttribute('disablepictureinpicture');
          }
          m.addedNodes && m.addedNodes.forEach(function(n) {
            if (n.nodeType === 1) {
              if (n.tagName === 'VIDEO') n.removeAttribute('disablepictureinpicture');
              allowPictureInPicture(n);
            }
          });
        });
      }).observe(document.documentElement, {
        subtree: true, childList: true, attributes: true, attributeFilter: ['disablepictureinpicture']
      });
    }
    if (document.documentElement) { start(); } else { document.addEventListener('DOMContentLoaded', start); }
  }());

  // Resume where the reader was after a reload within a minute (Android's kYoutubePipResumePosition).
  (function() {
      if (window.__bravePipResumeActive) return;
      window.__bravePipResumeActive = true;

      function videoId() {
          try { return new URLSearchParams(location.search).get('v'); }
          catch (e) { return null; }
      }
      function storageKey(id) { return 'brave_pip_resume_' + id; }

      function savePosition() {
          if (location.pathname !== '/watch') return;
          var v = document.querySelector('video');
          var id = videoId();
          if (!v || !id) return;
          var t = v.currentTime;
          if (!(t > 0) || !isFinite(t)) return;
          try {
              localStorage.setItem(
                  storageKey(id), JSON.stringify({ t: t, at: Date.now() }));
          } catch (e) {}
      }

      function restorePosition() {
          if (location.pathname !== '/watch') return;
          var id = videoId();
          if (!id) return;
          var raw;
          try { raw = localStorage.getItem(storageKey(id)); } catch (e) { return; }
          if (!raw) return;
          var data;
          try { data = JSON.parse(raw); } catch (e) { return; }
          if (!data || !(data.t > 0)) return;
          // Only resume for a reload that happened right after we were last
          // playing (PiP exit / accidental refresh), not a later revisit.
          if (Date.now() - data.at > 60000) {
              try { localStorage.removeItem(storageKey(id)); } catch (e) {}
              return;
          }
          var tries = 0;
          var iv = setInterval(function() {
              tries++;
              var v = document.querySelector('video');
              if (v && v.readyState >= 1 && isFinite(v.duration)
                      && data.t < v.duration - 1) {
                  if (Math.abs(v.currentTime - data.t) > 1.5) {
                      try { v.currentTime = data.t; } catch (e) {}
                  }
                  try { localStorage.removeItem(storageKey(id)); } catch (e) {}
                  clearInterval(iv);
              } else if (tries > 40) {
                  clearInterval(iv);
              }
          }, 250);
      }

      // Persist while playing (timeupdate is throttled when backgrounded, so
      // also poll) so a reload right after has the freshest position; capture
      // the exact time at PiP exit too.
      document.addEventListener('timeupdate', savePosition, true);
      document.addEventListener('leavepictureinpicture', savePosition, true);
      setInterval(savePosition, 1000);

      // Restore on this load and on YouTube SPA navigations to a watch page.
      restorePosition();
      onYouTubeNavigation(restorePosition);
  })();

  // Hide YouTube's own comments; the Express comments bar takes their place (Android's
  // kRemoveYoutubeComment).
  (function() {
      'use strict';

      if (window.__braveCommentHiderActive) return;
      window.__braveCommentHiderActive = true;

      // All comment-related CSS selectors covering both mobile (ytm-) and
      // desktop (ytd-) YouTube layouts, including the newer engagement-panel
      // based comment sections and bottom-sheet comments.
      var COMMENT_SELECTORS = [
          // Mobile YouTube (ytm-) — entry point, sections, threads
          'ytm-comments-entry-point-header-renderer',
          'ytm-comments-entry-point-teaser-renderer',
          'ytm-comment-section-renderer',
          'ytm-comment-section-header-renderer',
          'ytm-comment-thread-renderer',
          'ytm-comment-replies-renderer',
          'ytm-comments-simplebox-renderer',
          'ytm-comments-entry-point-metadata-renderer',
          'ytm-item-section-renderer[section-identifier="comments"]',
          'ytm-item-section-renderer[section-identifier="comment-item-section"]',
          'ytm-engagement-panel-section-list-renderer[target-id="comments-section"]',
          'ytm-engagement-panel-section-list-renderer[target-id="engagement-panel-comments-section"]',
          // Desktop YouTube (ytd-)
          '#comments',
          '#comment-section-renderer',
          '#comment-teaser',
          'ytd-comments',
          'ytd-comments-header-renderer',
          'ytd-engagement-panel-section-list-renderer[target-id="engagement-panel-comments-section"]',
          // Generic attribute-based selectors
          '[data-target-id="comments"]',
          '[data-target-id="comments-section"]',
          '[data-target-id="engagement-panel-comments-section"]',
          // Aria-label based — catches the "Comments" button/link regardless
          // of which custom element YouTube wraps it in.
          '[aria-label="Comments"]',
          '[aria-label="comments"]',
          'button[aria-label*="comment" i]'
      ];

      var CSS_SELECTOR = COMMENT_SELECTORS.join(',');
      // Hide matched elements AND any section-level wrapper that contains them.
      // :has() is supported in Chromium 105+ so it works in our WebView.
      var WRAPPER_RULE =
          'ytm-item-section-renderer:has(' + CSS_SELECTOR + '),' +
          'ytd-item-section-renderer:has(' + CSS_SELECTOR + ')';
      var CSS_RULE = CSS_SELECTOR + ',' + WRAPPER_RULE +
          '{ display: none !important; visibility: hidden !important;' +
          '  height: 0 !important; overflow: hidden !important; }';

      // ── CSS injection ────────────────────────────────────────────────────
      function injectHideStyles(root) {
          var isDoc = (root === document);
          var parent = isDoc ? (document.head || document.documentElement) : root;

          try {
              var existing = isDoc
                  ? document.getElementById('brave-hide-yt-comments')
                  : (root.querySelector ? root.querySelector('#brave-hide-yt-comments') : null);
              if (existing) return;
          } catch(e) {}

          try {
              var ll = (isDoc ? document : root).getElementsByTagName('lazy-list');
              if (ll && ll[0]) ll[0].textContent = '';
          } catch(e) {}

          var style = document.createElement('style');
          style.id = 'brave-hide-yt-comments';
          style.textContent = CSS_RULE;
          parent.appendChild(style);
      }

      // ── DOM removal ──────────────────────────────────────────────────────
      // Section-level tag names that act as containers on YouTube.
      // When we find a comment element, we walk up and remove the nearest
      // container so no empty wrapper is left behind.
      var SECTION_TAGS = [
          'YTM-ITEM-SECTION-RENDERER',
          'YTD-ITEM-SECTION-RENDERER',
          'YTM-ENGAGEMENT-PANEL-SECTION-LIST-RENDERER',
          'YTD-ENGAGEMENT-PANEL-SECTION-LIST-RENDERER',
          'YTM-COMMENTS-ENTRY-POINT-HEADER-RENDERER',
          'YTM-COMMENTS-ENTRY-POINT-TEASER-RENDERER',
          'YTM-COMMENT-SECTION-RENDERER',
          'YTD-COMMENTS'
      ];

      function removeWithParent(el) {
          // Walk up to the nearest section-level wrapper and remove it
          // so the whole block disappears, not just the inner content.
          var node = el.parentElement;
          while (node && node !== document.body) {
              if (SECTION_TAGS.indexOf(node.tagName) !== -1) {
                  node.remove();
                  return;
              }
              node = node.parentElement;
          }
          // No section wrapper found — remove the element itself.
          el.remove();
      }

      function removeCommentNodes(root) {
          var searchRoot = root || document;
          try {
              searchRoot.querySelectorAll(CSS_SELECTOR).forEach(function(el) {
                  removeWithParent(el);
              });
          } catch(e) {}
      }

      // ── Shadow DOM support ───────────────────────────────────────────────
      // YouTube mobile increasingly renders components inside shadow roots.
      // document.querySelectorAll and <style> in <head> cannot pierce shadow
      // boundaries, so we must walk into open shadow roots explicitly.
      var observedRoots = new WeakSet();

      function processShadowRoots(root) {
          var els;
          try { els = root.querySelectorAll('*'); } catch(e) { return; }
          els.forEach(function(el) {
              if (el.shadowRoot && !observedRoots.has(el.shadowRoot)) {
                  observedRoots.add(el.shadowRoot);
                  injectHideStyles(el.shadowRoot);
                  removeCommentNodes(el.shadowRoot);
                  observeRoot(el.shadowRoot);
                  processShadowRoots(el.shadowRoot);
              }
          });
      }

      // ── MutationObserver ─────────────────────────────────────────────────
      var pendingRemoval = false;

      function observeRoot(root) {
          var obs = new MutationObserver(function() {
              if (pendingRemoval) return;
              pendingRemoval = true;
              requestAnimationFrame(function() {
                  removeCommentNodes(root);
                  processShadowRoots(root);
                  pendingRemoval = false;
              });
          });
          obs.observe(root, { childList: true, subtree: true });
      }

      function startObserver() {
          if (!observedRoots.has(document.documentElement)) {
              observedRoots.add(document.documentElement);
              observeRoot(document.documentElement);
          }
      }

      // ── Intercept attachShadow ───────────────────────────────────────────
      // Catch future shadow roots the moment they are created so we can
      // inject styles and observe them immediately.
      try {
          var origAttachShadow = Element.prototype.attachShadow;
          Element.prototype.attachShadow = function(init) {
              var shadowRoot = origAttachShadow.call(this, init);
              if (init.mode === 'open' && !observedRoots.has(shadowRoot)) {
                  observedRoots.add(shadowRoot);
                  // Defer slightly so the shadow root is populated.
                  setTimeout(function() {
                      injectHideStyles(shadowRoot);
                      removeCommentNodes(shadowRoot);
                      observeRoot(shadowRoot);
                      processShadowRoots(shadowRoot);
                  }, 0);
              }
              return shadowRoot;
          };
      } catch(e) {}

      // ── SPA navigation handling ──────────────────────────────────────────
      function fullCleanup() {
          injectHideStyles(document);
          removeCommentNodes();
          processShadowRoots(document);
          startObserver();
      }

      onYouTubeNavigation(fullCleanup);
      window.addEventListener('yt-page-data-updated', function() {
          removeCommentNodes();
          processShadowRoots(document);
      });
      window.addEventListener('yt-page-type-changed', function() {
          removeCommentNodes();
          processShadowRoots(document);
      });

      // ── Periodic safety net ──────────────────────────────────────────────
      // Catches any comments that slip through event-driven removal (e.g.
      // lazy-loaded via IntersectionObserver after scroll).
      setInterval(function() {
          removeCommentNodes();
          processShadowRoots(document);
      }, 2000);

      // ── Initial run ──────────────────────────────────────────────────────
      fullCleanup();
  })();

  // First visit to the YouTube home page: point at a video to watch (Android's
  // kYoutubeHomeIntroTutorial). Once per install, kept by the app rather than in localStorage
  // (Android keeps it in the profile), so www and m share it and clearing website data doesn't
  // reset it. Like Android, only a document that starts on the m.youtube.com home runs it, and
  // it starts 300 ms after the page is ready. The app is asked first (`homeTutorialState`) and
  // told once the overlay is on screen (`homeTutorialShown`).
  (function() {
      'use strict';

      const LOG = '[brave-yt-tutorial]';
      function log() {
          try {
              const a = Array.prototype.slice.call(arguments);
              a.unshift(LOG);
              console.log.apply(console, a);
          } catch (e) {}
      }

      if (window.__braveYtHomeTutorialActive) {
          log('already active in this document, skipping');
          return;
      }
      window.__braveYtHomeTutorialActive = true;
      log('script loaded on', window.location.href);

      const ROOT_ID        = 'brave-yt-home-tutorial-root';
      const STYLE_ID       = 'brave-yt-home-tutorial-style';
      const GOLD           = '#D4AF37';
      const NAVY           = '#1A1A2E';
      // 0x99000000 in PipCoachMarkView.java — the home tutorial and the
      // watch-page PiP coach mark must use the same dim level so the two
      // steps feel like one continuous flow.
      const DIM_RGBA       = 'rgba(0, 0, 0, 0.6)';
      const RING_PADDING   = 8;
      const RING_RADIUS    = 14;
      const POLL_MS        = 350;
      const MAX_WAIT_MS    = 10000;

      // The m.youtube.com home only (Android: the app's canonical YouTube entry, so a passing
      // www. document can't use up the one showing).
      function isYouTubeHome() {
          const host = window.location.hostname.toLowerCase();
          const path = window.location.pathname;
          return host === 'm.youtube.com' && (path === '/' || path === '');
      }

      if (!isYouTubeHome()) {
          log('document did not start on the m.youtube.com home, skipping');
          return;
      }

      // Unknown (null) until the app answers; only its "not shown" lets the tutorial run.
      let appSaysShown = null;
      let shownHere = false;
      function alreadyShown() {
          return shownHere || appSaysShown !== false;
      }
      function askApp() {
          return ask('homeTutorialState').then(function(reply) {
              appSaysShown = !(reply && reply.shown === false);
              return appSaysShown;
          });
      }
      function markShown() {
          shownHere = true;
          appSaysShown = true;
          notify('homeTutorialShown');
      }

      // "10:34" -> 634, "1:23:45" -> 5025, otherwise -1.
      function parseDuration(text) {
          if (!text) return -1;
          const m = String(text).trim().match(
              /^(\d{1,2}):(\d{2})(?::(\d{2}))?$/);
          if (!m) return -1;
          return m[3] !== undefined
              ? (+m[1]) * 3600 + (+m[2]) * 60 + (+m[3])
              : (+m[1]) * 60   + (+m[2]);
      }

      // Reads a tile's video duration (seconds) from its duration badge, NOT
      // from arbitrary tile text. The old whole-textContent scan matched any
      // "MM:SS"-looking run (post timestamps, "10:34 AM", nested numbers),
      // which is how a community photo post or a thumbnail could get picked.
      function readTileDurationSeconds(tile) {
          // 1) Dedicated duration-badge elements (the "10:34" overlay).
          const badges = tile.querySelectorAll(
              'ytm-thumbnail-overlay-time-status-renderer,' +
              '[class*="time-status"],[class*="timestamp"],' +
              '.badge-shape__text,.yt-badge-shape__text');
          for (let i = 0; i < badges.length; i++) {
              const d = parseDuration(badges[i].textContent);
              if (d >= 0) return d;
          }
          // 2) Resilient fallback that survives badge-class renames WITHOUT the
          // old false positives: a LEAF element whose ENTIRE text is a
          // duration (a post body or channel name is never exactly "MM:SS").
          const leaves = tile.querySelectorAll('span, div');
          for (let i = 0; i < leaves.length; i++) {
              const el = leaves[i];
              if (el.children.length !== 0) continue;
              const d = parseDuration(el.textContent);
              if (d >= 0) return d;
          }
          return -1;
      }

      // Picks the first main-feed tile that is a real long-form (>= 60s)
      // SINGLE video. Deliberately strict: it must have a /watch?v= link (no
      // Shorts, no playlist/mix), must not sit inside a horizontal carousel or
      // a community post ("scrolling photos"/thumbnails), and its duration is
      // read from the duration badge. This stops the spotlight from landing on
      // a photo post or a scrolling image row instead of the main video.
      function findLongFormTile() {
          const anchors = Array.from(
              document.querySelectorAll('a[href*="/watch?v="]'));
          const seen = new Set();
          for (let i = 0; i < anchors.length; i++) {
              const a = anchors[i];
              const href = a.getAttribute('href') || '';
              // Single video only: exclude Shorts and playlist/mix radios.
              if (href.indexOf('/shorts/') !== -1) continue;
              if (/[?&]list=/.test(href)) continue;

              const tile = a.closest(
                  'ytm-video-with-context-renderer, ytm-compact-video-renderer, ' +
                  'ytm-media-item, ytm-rich-item-renderer');
              if (!tile || seen.has(tile)) continue;
              seen.add(tile);

              // Skip carousels ("scrolling photos"), Shorts shelves, community
              // posts and playlists — anything that isn't a standalone video.
              if (tile.closest(
                      'ytm-reel-shelf-renderer, ytm-horizontal-list-renderer, ' +
                      'ytm-post-renderer, ytm-backstage-post-renderer, ' +
                      'ytm-post-base-renderer, ytm-compact-playlist-renderer, ' +
                      'ytm-playlist-renderer')) {
                  continue;
              }

              const dur = readTileDurationSeconds(tile);
              if (dur < 60) continue;

              // Prefer the innermost single-video element so the spotlight
              // ring hugs one tile, never a wide shelf wrapper.
              const chosen = a.closest(
                  'ytm-video-with-context-renderer, ytm-compact-video-renderer, ' +
                  'ytm-media-item') || tile;
              const rect = chosen.getBoundingClientRect();
              if (!rect.width || !rect.height) continue;  // not laid out yet
              log('picked tile', i, 'duration=' + dur + 's');
              return chosen;
          }
          log('no qualifying long-form video tile yet, will retry');
          return null;
      }

      function injectStyles() {
          if (document.getElementById(STYLE_ID)) return;
          const style = document.createElement('style');
          style.id = STYLE_ID;
          // Four solid-color rectangles (not box-shadow, not SVG mask) tile
          // the viewport around the cutout. Each is just a plain colored
          // div — guaranteed to render in any WebView.
          style.textContent = `
              #${ROOT_ID} {
                  position: fixed !important; inset: 0 !important;
                  z-index: 2147483646 !important;
                  pointer-events: auto !important;
                  touch-action: none !important;
                  overscroll-behavior: contain !important;
                  font-family: 'Roboto', 'Helvetica Neue', Helvetica, Arial, sans-serif !important;
                  animation: braveTutFadeIn 220ms ease-out both;
              }
              @keyframes braveTutFadeIn {
                  from { opacity: 0; }
                  to   { opacity: 1; }
              }
              #${ROOT_ID} .brave-tut-dim {
                  position: fixed !important;
                  background: ${DIM_RGBA} !important;
                  pointer-events: none !important;
                  box-sizing: border-box !important;
              }
              #${ROOT_ID} .brave-tut-ring {
                  position: fixed !important;
                  border: 3px solid ${GOLD} !important;
                  border-radius: ${RING_RADIUS}px !important;
                  pointer-events: none !important;
                  box-sizing: border-box !important;
                  animation: braveTutGlow 1500ms ease-in-out infinite;
              }
              @keyframes braveTutGlow {
                  0%   { box-shadow: 0 0 8px  rgba(212, 175, 55, 0.6); }
                  50%  { box-shadow: 0 0 28px rgba(212, 175, 55, 1.0),
                                     0 0 0 8px rgba(212, 175, 55, 0.20); }
                  100% { box-shadow: 0 0 8px  rgba(212, 175, 55, 0.6); }
              }
              #${ROOT_ID} .brave-tut-banner {
                  position: fixed !important;
                  left: 16px !important; right: 16px !important;
                  background: ${NAVY} !important;
                  border: 1px solid rgba(212, 175, 55, 0.5) !important;
                  border-radius: 16px !important;
                  padding: 18px 20px 16px !important;
                  box-shadow: 0 14px 40px rgba(0, 0, 0, 0.7) !important;
                  pointer-events: none !important;
                  animation: braveTutBannerIn 360ms cubic-bezier(.2,.7,.2,1) both;
              }
              @keyframes braveTutBannerIn {
                  from { opacity: 0; transform: translateY(10px); }
                  to   { opacity: 1; transform: translateY(0); }
              }
              #${ROOT_ID} .brave-tut-title {
                  color: ${GOLD} !important;
                  font-size: 17px !important;
                  font-weight: 700 !important;
                  line-height: 1.3 !important;
                  letter-spacing: 0.2px !important;
                  margin: 0 0 8px 0 !important;
                  display: flex !important;
                  align-items: center !important;
                  gap: 8px !important;
              }
              #${ROOT_ID} .brave-tut-pulse-dot {
                  width: 9px !important; height: 9px !important;
                  border-radius: 50% !important;
                  background: ${GOLD} !important;
                  box-shadow: 0 0 12px rgba(212, 175, 55, 1.0) !important;
                  animation: braveTutDot 1.05s ease-in-out infinite;
                  flex: 0 0 auto !important;
              }
              @keyframes braveTutDot {
                  0%, 100% { transform: scale(1);   opacity: 1;    }
                  50%      { transform: scale(1.6); opacity: 0.55; }
              }
              #${ROOT_ID} .brave-tut-body {
                  color: #FFFFFF !important;
                  font-size: 14px !important;
                  line-height: 1.45 !important;
                  margin: 0 !important;
                  opacity: 0.95 !important;
              }
              #${ROOT_ID} .brave-tut-cta {
                  position: fixed !important;
                  background: ${GOLD} !important;
                  color: ${NAVY} !important;
                  font-size: 12px !important;
                  font-weight: 800 !important;
                  letter-spacing: 0.8px !important;
                  padding: 8px 14px !important;
                  border-radius: 999px !important;
                  pointer-events: none !important;
                  box-shadow: 0 6px 20px rgba(212, 175, 55, 0.7) !important;
                  text-transform: uppercase !important;
                  white-space: nowrap !important;
                  animation: braveTutCta 1.1s ease-in-out infinite;
              }
              @keyframes braveTutCta {
                  0%, 100% { transform: translateY(0)    scale(1);    }
                  50%      { transform: translateY(-3px) scale(1.05); }
              }
          `;
          (document.head || document.documentElement).appendChild(style);
      }

      function makeDimDiv(left, top, width, height) {
          const d = document.createElement('div');
          d.className = 'brave-tut-dim';
          d.style.left   = left   + 'px';
          d.style.top    = top    + 'px';
          d.style.width  = Math.max(0, width)  + 'px';
          d.style.height = Math.max(0, height) + 'px';
          return d;
      }

      function buildOverlay(rect) {
          injectStyles();
          const vw = window.innerWidth;
          const vh = window.innerHeight;
          const pos = {
              x: Math.max(0, rect.left - RING_PADDING),
              y: Math.max(0, rect.top  - RING_PADDING),
              w: Math.min(vw, rect.width  + RING_PADDING * 2),
              h: Math.min(vh, rect.height + RING_PADDING * 2)
          };

          const root = document.createElement('div');
          root.id = ROOT_ID;

          // Four solid dim rectangles around the cutout. Plain background-
          // color divs — no SVG masks, no box-shadow tricks.
          root.appendChild(makeDimDiv(0, 0, vw, pos.y));                       // top
          root.appendChild(makeDimDiv(0, pos.y + pos.h, vw, vh - (pos.y + pos.h))); // bottom
          root.appendChild(makeDimDiv(0, pos.y, pos.x, pos.h));                // left
          root.appendChild(makeDimDiv(pos.x + pos.w, pos.y, vw - (pos.x + pos.w), pos.h)); // right

          // Gold animated ring around the cutout.
          const ring = document.createElement('div');
          ring.className = 'brave-tut-ring';
          ring.style.left   = pos.x + 'px';
          ring.style.top    = pos.y + 'px';
          ring.style.width  = pos.w + 'px';
          ring.style.height = pos.h + 'px';
          root.appendChild(ring);

          // Banner: gold pulsing dot + gold title + white body.
          const banner = document.createElement('div');
          banner.className = 'brave-tut-banner';
          const title = document.createElement('div');
          title.className = 'brave-tut-title';
          const dot = document.createElement('span');
          dot.className = 'brave-tut-pulse-dot';
          const titleText = document.createElement('span');
          titleText.textContent = 'Claim Premium Youtube \uD83D\uDC40';
          title.appendChild(dot);
          title.appendChild(titleText);
          const body = document.createElement('div');
          body.className = 'brave-tut-body';
          body.textContent = '';
          banner.appendChild(title);
          banner.appendChild(body);
          root.appendChild(banner);

          const gap = 14;
          const bannerEstHeight = 120;
          const cutoutBottom = pos.y + pos.h;
          if (cutoutBottom + gap + bannerEstHeight < vh - 16) {
              banner.style.top = (cutoutBottom + gap) + 'px';
          } else if (pos.y - gap - bannerEstHeight > 16) {
              banner.style.bottom = (vh - pos.y + gap) + 'px';
          } else {
              banner.style.bottom = '24px';
          }

          // Gold "TAP TO WATCH" pill — measured then centered horizontally
          // over the cutout's bottom edge.
          const cta = document.createElement('div');
          cta.className = 'brave-tut-cta';
          cta.textContent = 'TAP TO WATCH';
          cta.style.visibility = 'hidden';
          root.appendChild(cta);
          requestAnimationFrame(() => {
              const cw = cta.offsetWidth;
              const ch = cta.offsetHeight;
              const cx = pos.x + (pos.w - cw) / 2;
              const cy = pos.y + pos.h - ch / 2;
              cta.style.left = Math.max(8, Math.min(vw - cw - 8, cx)) + 'px';
              cta.style.top  = cy + 'px';
              cta.style.visibility = 'visible';
          });

          return root;
      }

      function dismiss(root) {
          try {
              if (root && root.parentElement) {
                  root.parentElement.removeChild(root);
              }
              const style = document.getElementById(STYLE_ID);
              if (style && style.parentElement) {
                  style.parentElement.removeChild(style);
              }
          } catch (e) {}
      }

      // Scrolls the tile so its center sits ~38% from the top of the
      // viewport. Uses scrollIntoView which respects whatever scroll
      // container the page actually uses, then nudges the result if the
      // tile didn't end up where we wanted.
      function scrollTileIntoUpperView(tile) {
          try {
              tile.scrollIntoView({ block: 'center', inline: 'nearest' });
          } catch (e) {
              try { tile.scrollIntoView(); } catch (e2) {}
          }
          const rect = tile.getBoundingClientRect();
          const vh = window.innerHeight;
          const desiredCenter = vh * 0.38;
          const currentCenter = rect.top + rect.height / 2;
          const delta = currentCenter - desiredCenter;
          if (Math.abs(delta) > 8) {
              try { window.scrollBy(0, delta); } catch (e) {}
              const sc = document.scrollingElement;
              if (sc) {
                  try { sc.scrollTop += delta; } catch (e) {}
              }
          }
      }

      function spotlightTile(tile) {
          // Synchronous single-document guard: claim the slot the instant we
          // commit to spotlighting, BEFORE the async scroll/measure/build
          // below. The yt-navigate-finish re-arm (which resets `attempted`)
          // can otherwise re-enter and stack a second overlay in the same
          // document while these rAFs are still pending. The app's flag
          // handles the cross-document/cross-visit one-shot; this flag
          // handles the in-document race.
          if (window.__braveYtHomeTutorialBuilt ||
              document.getElementById(ROOT_ID)) {
              log('overlay already built in this document, skipping');
              return;
          }
          window.__braveYtHomeTutorialBuilt = true;
          log('spotlighting tile');
          scrollTileIntoUpperView(tile);
          // Two rAFs let layout settle after the scroll before we measure.
          requestAnimationFrame(() => requestAnimationFrame(() => {
              if (!isYouTubeHome() || alreadyShown()) return;
              const rect = tile.getBoundingClientRect();
              if (!rect.width || !rect.height) {
                  log('tile has no size after scroll, aborting');
                  return;
              }
              log('overlay rect', rect.left|0, rect.top|0,
                  rect.width|0, rect.height|0);

              const root = buildOverlay(rect);
              document.body.appendChild(root);

              // Mark shown only after we've successfully attached and
              // confirmed it has on-screen dimensions, so a render
              // failure doesn't permanently lock the user out.
              requestAnimationFrame(() => {
                  const r = root.getBoundingClientRect();
                  if (r.width > 0 && r.height > 0) {
                      markShown();
                      log('overlay attached, marked shown');
                  } else {
                      log('overlay attached but has zero size');
                  }
              });

              // Only the tile itself advances the flow. Taps on the
              // dimmed area are swallowed so the user can't skip the
              // tutorial — they must tap the highlighted video.
              root.addEventListener('click', (event) => {
                  const px = event.clientX, py = event.clientY;
                  const cur = tile.getBoundingClientRect();
                  const inside =
                      px >= (cur.left   - RING_PADDING) &&
                      px <= (cur.right  + RING_PADDING) &&
                      py >= (cur.top    - RING_PADDING) &&
                      py <= (cur.bottom + RING_PADDING);
                  log('overlay clicked, inside=' + inside);
                  if (inside) {
                      dismiss(root);
                      const link = tile.querySelector('a[href]');
                      if (link) link.click();
                      else tile.click();
                  } else {
                      // Eat the event — no dismiss, no pass-through.
                      event.preventDefault();
                      event.stopPropagation();
                  }
              });

              const onNav = () => {
                  dismiss(root);
                  window.removeEventListener('yt-navigate-start', onNav);
              };
              window.addEventListener('yt-navigate-start', onNav);
          }));
      }

      let attempted = false;
      function attempt() {
          if (attempted) return true;
          if (alreadyShown()) {
              log('already shown previously, bailing');
              return true;
          }
          if (!isYouTubeHome()) {
              log('not on home, bailing (path=' + window.location.pathname + ')');
              return true;
          }
          const tile = findLongFormTile();
          if (!tile) return false;
          attempted = true;
          // Asked again just before showing: another tab may have shown it meanwhile.
          askApp().then(function(shown) {
              if (shown || shownHere || !isYouTubeHome()) {
                  log('shown elsewhere meanwhile, bailing');
                  return;
              }
              spotlightTile(tile);
          });
          return true;
      }

      let startedAt = Date.now();
      function poll() {
          if (alreadyShown() || !isYouTubeHome()) return;
          if (attempt()) return;
          if (Date.now() - startedAt > MAX_WAIT_MS) {
              log('poll timed out without finding a tile');
              return;
          }
          setTimeout(poll, POLL_MS);
      }

      // Android injects the tutorial 300 ms after the page is ready.
      function begin() {
          askApp().then(function(shown) {
              if (shown) {
                  log('the app has it as shown, bailing');
                  return;
              }
              log('arming poll');
              setTimeout(function() {
                  startedAt = Date.now();
                  poll();
              }, 300);
          });
      }
      if (document.readyState === 'loading') {
          document.addEventListener('DOMContentLoaded', begin);
      } else {
          begin();
      }

      window.addEventListener('yt-navigate-finish', () => {
          attempted = false;
          startedAt = Date.now();
          setTimeout(poll, 200);
      });
  })();
})();
