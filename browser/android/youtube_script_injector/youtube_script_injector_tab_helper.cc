/* Copyright (c) 2019 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "brave/browser/android/youtube_script_injector/youtube_script_injector_tab_helper.h"

#include <memory>
#include <string>

#include "base/feature_list.h"
#include "base/supports_user_data.h"
#include "brave/browser/android/youtube_script_injector/brave_youtube_script_injector_native_helper.h"
#include "brave/browser/android/youtube_script_injector/features.h"
#include "brave/components/brave_shields/content/browser/brave_shields_util.h"
#include "brave/components/constants/pref_names.h"
#include "brave/content/public/browser/fullscreen_page_data.h"
#include "chrome/browser/profiles/profile.h"
#include "chrome/common/chrome_isolated_world_ids.h"
#include "components/prefs/pref_service.h"
#include "content/public/browser/navigation_controller.h"
#include "content/public/browser/navigation_entry.h"
#include "content/public/browser/navigation_handle.h"
#include "content/public/browser/web_contents.h"
#include "net/base/registry_controlled_domains/registry_controlled_domain.h"
#include "third_party/blink/public/common/associated_interfaces/associated_interface_provider.h"
#include "url/gurl.h"
#include "url/url_util.h"

namespace {
  constexpr char16_t kYoutubeBackgroundPlayback2[] =
    uR"(
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
            Object.defineProperties(document,
                { 'hidden': {value: false}, 'visibilityState': {value: 'visible'} });
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
    )";

constexpr char16_t kYoutubeInAppPIP[] = 
    uR"(
    (function() {
        let videoElement = null;
        let videoData = null;
        let updateInterval = null;
        
        function isBridgeAvailable() {
            return typeof window.BraveYouTube !== 'undefined';
        }
        
        function extractVideoData() {
            const video = document.querySelector('video');
            if (!video) return null;
            
            const titleElement = document.querySelector('h1.ytd-watch-metadata yt-formatted-string') ||
                                document.querySelector('.watch-main-col h1') ||
                                document.querySelector('#eow-title');
            
            const channelElement = document.querySelector('#owner-name a') ||
                                 document.querySelector('.ytd-channel-name a') ||
                                 document.querySelector('#watch-header .yt-user-info a');
            
            const thumbnailElement = document.querySelector('link[rel="image_src"]') ||
                                   document.querySelector('meta[property="og:image"]');
            
            return {
                url: window.location.href,
                title: titleElement ? titleElement.textContent.trim() : 'YouTube Video',
                channel: channelElement ? channelElement.textContent.trim() : 'Unknown Channel',
                thumbnailUrl: thumbnailElement ? (thumbnailElement.href || thumbnailElement.content) : null,
                currentTime: video.currentTime,
                duration: video.duration || 0,
                isPlaying: !video.paused,
                tabId: Date.now()
            };
        }
        
        function startMiniPlayer() {
            if (!isBridgeAvailable()) {
                console.log('BraveYouTube bridge not available');
                return;
            }
            
            videoData = extractVideoData();
            if (!videoData) {
                console.log('Could not extract video data');
                return;
            }
            
            try {
                window.BraveYouTube.startMiniPlayer(JSON.stringify(videoData));
                startVideoStateMonitoring();
                console.log('Mini-player started via bridge');
            } catch (error) {
                console.error('Failed to start mini-player:', error);
            }
        }
        
        function startVideoStateMonitoring() {
            if (updateInterval) {
                clearInterval(updateInterval);
            }
            
            updateInterval = setInterval(() => {
                const video = document.querySelector('video');
                if (video && isBridgeAvailable()) {
                    const state = {
                        currentTime: video.currentTime,
                        isPlaying: !video.paused,
                        duration: video.duration || 0
                    };
                    
                    try {
                        window.BraveYouTube.updateVideoState(JSON.stringify(state));
                    } catch (error) {
                        console.error('Failed to update video state:', error);
                    }
                } else {
                    clearInterval(updateInterval);
                    updateInterval = null;
                }
            }, 1000);
        }
        
        function createMiniPlayerButton() {
            const button = document.createElement('button');
            button.className = 'yt-mini-player-btn';
            button.innerHTML = '📱';
            button.title = 'Open in Mini Player';
            button.setAttribute('aria-label', 'Open video in mini player');
            
            button.style.cssText = `
                position: relative;
                width: 40px;
                height: 40px;
                border: none;
                border-radius: 50%;
                background: #1976d2;
                color: white;
                font-size: 16px;
                cursor: pointer;
                margin: 0 8px;
                transition: all 0.2s ease;
                z-index: 1000;
            `;
            
            button.addEventListener('mouseenter', () => {
                button.style.transform = 'scale(1.1)';
                button.style.background = '#1565c0';
            });
            
            button.addEventListener('mouseleave', () => {
                button.style.transform = 'scale(1)';
                button.style.background = '#1976d2';
            });
            
            button.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                startMiniPlayer();
            });
            
            return button;
        }
        
        function addButtonToPage() {
            const containers = [
                '.mobile-topbar-header-content',
                '#top-level-buttons-computed',
                '.ytd-watch-metadata #top-row',
                '#watch-header .yt-uix-button-group'
            ];
            
            for (const selector of containers) {
                const container = document.querySelector(selector);
                if (container && !container.querySelector('.yt-mini-player-btn')) {
                    const button = createMiniPlayerButton();
                    container.appendChild(button);
                    console.log('Mini-player button added to:', selector);
                    return true;
                }
            }
            return false;
        }
        
        const observer = new MutationObserver(() => {
            if (window.location.pathname === '/watch') {
                addButtonToPage();
            }
        });
        
        observer.observe(document.documentElement, { 
            subtree: true, 
            childList: true 
        });
        
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', addButtonToPage);
        } else {
            addButtonToPage();
        }
        
        window.BraveYouTubeHelper = {
            extractVideoData,
            startMiniPlayer,
            isBridgeAvailable
        };
        
        window.addEventListener('beforeunload', () => {
            if (updateInterval) {
                clearInterval(updateInterval);
            }
        });
        
    })();
)";

const char16_t kYoutubePIP[] =
    uR"(
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
    )";

const char16_t kYoutubePipButton[] = 
    uR"(
    (function() {
        // Store tab reference for proper restoration
        let originalTabId = null;
        let videoElement = null;
        let wasPlaying = false;

        const buttonElement = document.createElement('button');
        buttonElement.className = 'yt-pip-gold';
        buttonElement.setAttribute('aria-label', 'Enter Picture-in-Picture mode');
        buttonElement.title = 'Picture-in-Picture';

        // Enhanced CSS (keeping your existing styles)
        if (!document.getElementById('yt-pip-gold-styles')) {
            const css = `
            .yt-pip-gold {
                position: fixed;
                bottom: 20px; right: 20px;
                z-index: 2147483647 !important;
                pointer-events: auto !important;
                width: 60px; height: 60px; border-radius: 50%;
                background: #D4AF37;
                border: none; cursor: pointer; overflow: hidden;
                box-shadow: 0 4px 12px rgba(0,0,0,.30);
                background-image: url("https://raw.githubusercontent.com/DeepakDahiya/DeepakDahiya.github.io/refs/heads/master/youtube-icon.svg");
                background-repeat: no-repeat;
                background-position: center;
                background-size: 55%;
                transition: transform .2s, box-shadow .2s, filter .2s;
                animation: scalePulse 2.4s ease-in-out infinite;
            }
            .yt-pip-gold:hover { transform: scale(1.10); box-shadow: 0 6px 16px rgba(0,0,0,.40); }
            .yt-pip-gold:active { transform: scale(0.95); }
            .yt-pip-gold:focus { outline: 2px solid #000; outline-offset: 2px; }
            .yt-pip-gold::before {
                content: '';
                position: absolute; top: 0; left: -75%;
                width: 50%; height: 100%;
                background: linear-gradient(120deg,
                            rgba(255,255,255,0) 0%,
                            rgba(255,255,255,.70) 50%,
                            rgba(255,255,255,0) 100%);
                transform: skewX(-25deg);
                animation: shine 2.8s infinite;
                pointer-events: none;
            }
            @keyframes shine {
                0% { left: -75%; }
                100% { left: 125%; }
            }
            @keyframes scalePulse {
                0%, 100% { transform: scale(1); }
                50% { transform: scale(1.1); }
            }
        `;
            const styleTag = document.createElement('style');
            styleTag.id = 'yt-pip-gold-styles';
            styleTag.textContent = css;
            document.head.appendChild(styleTag);
        }

        // Enhanced PiP handling
        buttonElement.addEventListener('click', () => {
            videoElement = document.querySelector('video');
            if (videoElement) {
                // Store current state
                wasPlaying = !videoElement.paused;
                originalTabId = window.location.href;
                
                videoElement.removeAttribute('disablePictureInPicture');
                videoElement.requestPictureInPicture().catch(console.error);
            }
        });

        // Enhanced PiP event listeners
        if (document.pictureInPictureEnabled) {
            document.addEventListener('enterpictureinpicture', (event) => {
                console.log('Entered PiP mode');
                // Ensure video continues playing
                if (event.target && wasPlaying) {
                    setTimeout(() => {
                        if (event.target.paused) {
                            event.target.play().catch(console.error);
                        }
                    }, 100);
                }
            });

            document.addEventListener('leavepictureinpicture', (event) => {
                console.log('Left PiP mode');
                // Force focus back to this tab
                if (window.focus) {
                    window.focus();
                }
                
                // Ensure video continues playing after PiP exit
                setTimeout(() => {
                    const video = document.querySelector('video');
                    if (video && wasPlaying && video.paused) {
                        video.play().catch(console.error);
                    }
                    
                    // Force page visibility to visible
                    Object.defineProperty(document, 'hidden', {
                        value: false,
                        writable: false,
                        configurable: true
                    });
                    Object.defineProperty(document, 'visibilityState', {
                        value: 'visible',
                        writable: false,
                        configurable: true
                    });
                }, 200);
            });
        }

        const observer = new MutationObserver(() => {
            const buttonContainerElement = document.querySelector('.mobile-topbar-header-content');
            if (window.location.pathname !== '/watch' || !buttonContainerElement || buttonContainerElement.contains(buttonElement)) return;
            buttonContainerElement.prepend(buttonElement);
        });
        observer.observe(document.documentElement, { subtree: true, childList: true });

        // Additional visibility override for problematic devices
        const originalAddEventListener = document.addEventListener;
        document.addEventListener = function(type, listener, options) {
            if (type === 'visibilitychange') {
                return; // Block visibility change events
            }
            return originalAddEventListener.call(this, type, listener, options);
        };

    })();
)";

constexpr char16_t kYoutubeBackgroundPlayback[] =
    uR"(
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
)";

constexpr char16_t kYoutubePictureInPictureSupport[] =
    uR"(
(function() {
  // Function to modify the flags if the target object exists.
  function modifyYtcfgFlags() {
    const config = window.ytcfg.get("WEB_PLAYER_CONTEXT_CONFIGS")
      ?.WEB_PLAYER_CONTEXT_CONFIG_ID_MWEB_WATCH
    if (config && config.serializedExperimentFlags && typeof config
      .serializedExperimentFlags === 'string') {
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
        // Check and modify flags when a new script is added.
        modifyYtcfgFlags();
      }
    }, true);
  }
}());
)";

constexpr char16_t kYoutubeFullscreen[] =
    uR"(
(function() {
  return new Promise((resolve) => {
    const videoPlaySelector = "video.html5-main-video";
    const fullscreenSelector = "button.fullscreen-icon";
    function triggerFullscreen() {
      // Check if the video is not in fullscreen mode already.
      if (!document.fullscreenElement) {
        var fullscreenBtn = document.querySelector(fullscreenSelector);
        var videoPlayer = document.querySelector(videoPlaySelector);
        // Check if fullscreen button and video are available.
        if (fullscreenBtn && videoPlayer) {
         requestFullscreen(fullscreenBtn, resolve, videoPlayer);
        } else {
          // When fullscreen button is not available
          // clicking the movie player resume the UI.
          var playerContainer = document.getElementById("player-container-id");
          if (videoPlayer && playerContainer) {
            let observerTimeout;
            // Create a MutationObserver to watch for changes in the DOM.
            const observer = new MutationObserver(
            (_mutationsList, observer) => {
              var fullscreenBtn = document.querySelector(fullscreenSelector);
              var videoPlayer = document.querySelector(videoPlaySelector);
              if (fullscreenBtn && videoPlayer) {
                clearTimeout(observerTimeout);
                observer.disconnect()
                requestFullscreen(fullscreenBtn, resolve, videoPlayer);
              }
            });
            // Auto-disconnect the observer after 30 seconds,
            // a reasonable duration picked after some testing.
            observerTimeout = setTimeout(() => {
              observer.disconnect();
              resolve('timeout');
            }, 30000);
            // Start observing the DOM.
            observer.observe(playerContainer, {
              childList: true, subtree: true
            });
            // Make sure the player is in focus or responsive.
            videoPlayer.click();
          } else {
            // No fullscreen elements found, resolve immediately
            resolve('no_elements');
          }
        }
      } else {
        // Already in fullscreen, resolve immediately
        resolve('already_fullscreen');
      }
    }
    // Attempts to request fullscreen mode for the given movie player element.
    // Resolves with 'fullscreen_triggered' if successful, or
    // 'requestFullscreen_failed' if the request fails.
    function requestFullscreen(fullscreenBtn, resolve, videoPlayer) {
      if (videoPlayer.readyState >= 3) {
        videoPlayer.click();
        clickFullscreenButton(fullscreenBtn, resolve);
      } else {
        videoPlayer.addEventListener("canplay", () => {
          videoPlayer.click();
          clickFullscreenButton(fullscreenBtn, resolve);
        }, { once: true });
      }
    }
    function clickFullscreenButton(fullscreenBtn, resolve) {
      if (fullscreenBtn && !document.hidden) {
        fullscreenBtn.click();
        resolve('fullscreen_triggered');
      } else {
        resolve('requestFullscreen_failed');
      }
    }
    if (document.readyState === "loading") {
      // Loading hasn't finished yet.
      document.addEventListener("DOMContentLoaded",
      triggerFullscreen, { once: true });
    } else {
      // `DOMContentLoaded` has already fired.
      triggerFullscreen();
    }
  });
}());
)";

bool IsBackgroundVideoPlaybackEnabled(content::WebContents* contents) {
  PrefService* prefs =
      static_cast<Profile*>(contents->GetBrowserContext())->GetPrefs();

  return (base::FeatureList::IsEnabled(
              ::preferences::features::kBraveBackgroundVideoPlayback) &&
          prefs->GetBoolean(kBackgroundVideoPlaybackEnabled));
}

bool IsYouTubeDomain(const GURL& url) {
  if (net::registry_controlled_domains::SameDomainOrHost(
          url, GURL("https://www.youtube.com"),
          net::registry_controlled_domains::INCLUDE_PRIVATE_REGISTRIES)) {
    return true;
  }

  return false;
}

}  // namespace

YouTubeScriptInjectorTabHelper::YouTubeScriptInjectorTabHelper(
    content::WebContents* contents)
    : WebContentsObserver(contents),
      content::WebContentsUserData<YouTubeScriptInjectorTabHelper>(*contents) {}

YouTubeScriptInjectorTabHelper::~YouTubeScriptInjectorTabHelper() {}

void YouTubeScriptInjectorTabHelper::PrimaryPageChanged(content::Page& page) {
  script_injector_remote_.reset();
  bound_rfh_id_ = {};
  SetFullscreenRequested(false);
}

void YouTubeScriptInjectorTabHelper::RenderFrameDeleted(
    content::RenderFrameHost* rfh) {
  if (rfh->GetGlobalId() == bound_rfh_id_) {
    script_injector_remote_.reset();
    bound_rfh_id_ = {};
    SetFullscreenRequested(false);
  }
}

void YouTubeScriptInjectorTabHelper::DidFinishNavigation(
    content::NavigationHandle* navigation_handle) {
  if (navigation_handle->IsSameDocument() &&
      navigation_handle->IsInMainFrame() && navigation_handle->HasCommitted()) {
    SetFullscreenRequested(false);
  }
}

void YouTubeScriptInjectorTabHelper::PrimaryMainDocumentElementAvailable() {
  SetFullscreenRequested(false);
  content::WebContents* contents = web_contents();
  // Filter only YouTube videos.
  // if (!IsYouTubeVideo()) {
  //   return;
  // }
  if (!IsYouTubeDomain(contents->GetLastCommittedURL())) {
    return;
  }
  content::RenderFrameHost::AllowInjectingJavaScript();
  contents->GetPrimaryMainFrame()->ExecuteJavaScript(
      kYoutubeInAppPIP, base::NullCallback());

  contents->GetPrimaryMainFrame()->ExecuteJavaScript(
      kYoutubeBackgroundPlayback2, base::NullCallback());
  
  base::SequencedTaskRunner::GetCurrentDefault()->PostDelayedTask(
      FROM_HERE,
      base::BindOnce([](content::WebContents* contents) {
        contents->GetPrimaryMainFrame()->ExecuteJavaScript(
            kYoutubePIP, base::NullCallback());
      }, contents),
      base::Milliseconds(100));
      
  base::SequencedTaskRunner::GetCurrentDefault()->PostDelayedTask(
      FROM_HERE,
      base::BindOnce([](content::WebContents* contents) {
        contents->GetPrimaryMainFrame()->ExecuteJavaScript(
            kYoutubePipButton, base::NullCallback());
      }, contents),
      base::Milliseconds(200));

  if (IsBackgroundVideoPlaybackEnabled(contents)) {
    contents->GetPrimaryMainFrame()->ExecuteJavaScript(
        kYoutubeBackgroundPlayback, base::NullCallback());
  }
  if (base::FeatureList::IsEnabled(
          ::preferences::features::kBravePictureInPictureForYouTubeVideos)) {
    contents->GetPrimaryMainFrame()->ExecuteJavaScript(
        kYoutubePictureInPictureSupport, base::NullCallback());
  }
}

void YouTubeScriptInjectorTabHelper::MediaEffectivelyFullscreenChanged(
    bool is_fullscreen) {
  if (is_fullscreen && HasFullscreenBeenRequested()) {
    SetFullscreenRequested(false);
    if (web_contents()->GetVisibility() == content::Visibility::VISIBLE) {
      ::youtube_script_injector::EnterPictureInPicture(web_contents());
    }
  }
}

void YouTubeScriptInjectorTabHelper::MaybeSetFullscreen() {
  content::RenderFrameHost* rfh = web_contents()->GetPrimaryMainFrame();
  // Check if fullscreen has already been requested for this page.
  if (!rfh || !rfh->IsRenderFrameLive() || HasFullscreenBeenRequested()) {
    return;
  }

  // Mark fullscreen as requested for this page
  SetFullscreenRequested(true);
  EnsureBound(rfh);
  script_injector_remote_->RequestAsyncExecuteScript(
      ISOLATED_WORLD_ID_BRAVE_INTERNAL, kYoutubeFullscreen,
      blink::mojom::UserActivationOption::kActivate,
      blink::mojom::PromiseResultOption::kAwait,
      base::BindOnce(
          &YouTubeScriptInjectorTabHelper::OnFullscreenScriptComplete,
          weak_factory_.GetWeakPtr(), rfh->GetGlobalFrameToken()));
}

bool YouTubeScriptInjectorTabHelper::IsYouTubeVideo(bool mobileOnly) const {
  const GURL& url = web_contents()->GetLastCommittedURL();
  if (!url.is_valid() || url.is_empty()) {
    return false;
  }

  // Check if domain is youtube.com (including subdomains).
  if (!net::registry_controlled_domains::SameDomainOrHost(
          url, GURL("https://www.youtube.com"),
          net::registry_controlled_domains::INCLUDE_PRIVATE_REGISTRIES)) {
    return false;
  }

  // If mobileOnly is true, require host to be exactly "m.youtube.com"
  // (case-insensitive).
  if (mobileOnly) {
    const std::string& host = url.host();
    if (!base::EqualsCaseInsensitiveASCII(host, "m.youtube.com")) {
      return false;
    }
  }

  // Check if path is exactly "/watch" (case sensitive).
  const auto path = url.path_piece();
  constexpr std::string_view watch_path = "/watch";
  if (path != watch_path) {
    return false;
  }

  // Check if query exists and contains a non-empty "v" parameter.
  const auto query = url.query_piece();
  if (query.empty()) {
    return false;
  }

  // Key-value pairs are '&' delimited and the keys/values are '=' delimited.
  // Example: "https://www.youtube.com/watch?v=abcdefg&somethingElse=12345".
  std::string video_id;
  url::Component query_component(0, static_cast<int>(query.size()));
  url::Component key, value;
  while (url::ExtractQueryKeyValue(query, &query_component, &key, &value)) {
    if (query.substr(key.begin, key.len) == "v") {
      video_id = std::string(query.substr(value.begin, value.len));
      base::TrimWhitespaceASCII(video_id, base::TRIM_ALL, &video_id);
      break;
    }
  }

  return !video_id.empty();
}

bool YouTubeScriptInjectorTabHelper::HasFullscreenBeenRequested() const {
  content::NavigationEntry* entry =
      web_contents()->GetController().GetLastCommittedEntry();
  if (!entry) {
    return false;
  }

  auto* data = static_cast<content::FullscreenPageData*>(
      entry->GetUserData(content::kFullscreenPageDataKey));
  return data && data->fullscreen_requested();
}

void YouTubeScriptInjectorTabHelper::SetFullscreenRequested(bool requested) {
  content::NavigationEntry* entry =
      web_contents()->GetController().GetLastCommittedEntry();
  if (!entry) {
    return;
  }

  auto* data = static_cast<content::FullscreenPageData*>(
      entry->GetUserData(content::kFullscreenPageDataKey));
  if (data) {
    data->set_fullscreen_requested(requested);
  } else {
    entry->SetUserData(
        content::kFullscreenPageDataKey,
        std::make_unique<content::FullscreenPageData>(requested));
  }
}

void YouTubeScriptInjectorTabHelper::OnFullscreenScriptComplete(
    content::GlobalRenderFrameHostToken token,
    base::Value value) {
  // If the tab is visible, the script result indicates fullscreen was
  // triggered, and the callback is for the current main frame, return early
  // without resetting the fullscreen state. This prevents unnecessary state
  // changes when fullscreen was successfully entered.
  if (web_contents()->GetVisibility() == content::Visibility::VISIBLE &&
      value.is_string() && value.GetString() == "fullscreen_triggered" &&
      token == web_contents()->GetPrimaryMainFrame()->GetGlobalFrameToken()) {
    return;
  }

  SetFullscreenRequested(false);
}

bool YouTubeScriptInjectorTabHelper::IsPictureInPictureAvailable() const {
  return base::FeatureList::IsEnabled(
             preferences::features::kBravePictureInPictureForYouTubeVideos) &&
         IsYouTubeVideo(true) && web_contents() &&
         web_contents()->IsDocumentOnLoadCompletedInPrimaryMainFrame();
}

void YouTubeScriptInjectorTabHelper::EnsureBound(
    content::RenderFrameHost* rfh) {
  DCHECK(rfh);
  DCHECK(rfh->IsRenderFrameLive());

  if (!script_injector_remote_.is_bound() ||
      !script_injector_remote_.is_connected() ||
      bound_rfh_id_ != rfh->GetGlobalId()) {
    script_injector_remote_.reset();
    bound_rfh_id_ = rfh->GetGlobalId();
    rfh->GetRemoteAssociatedInterfaces()->GetInterface(
        &script_injector_remote_);
    script_injector_remote_.reset_on_disconnect();
  }
}

WEB_CONTENTS_USER_DATA_KEY_IMPL(YouTubeScriptInjectorTabHelper);
