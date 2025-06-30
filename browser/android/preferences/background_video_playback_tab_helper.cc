/* Copyright (c) 2019 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

#include "brave/browser/android/preferences/background_video_playback_tab_helper.h"

#include <string>

#include "base/strings/utf_string_conversions.h"
#include "brave/browser/android/preferences/features.h"
#include "brave/components/brave_shields/browser/brave_shields_util.h"
#include "brave/components/constants/pref_names.h"
#include "chrome/browser/content_settings/host_content_settings_map_factory.h"
#include "chrome/browser/profiles/profile.h"
#include "components/prefs/pref_service.h"
#include "content/browser/web_contents/web_contents_impl.h"
#include "content/public/browser/navigation_controller.h"
#include "content/public/browser/navigation_entry.h"
#include "content/public/browser/navigation_handle.h"
#include "content/public/browser/web_contents.h"
#include "net/base/registry_controlled_domains/registry_controlled_domain.h"
#include "url/gurl.h"

namespace {
constexpr char16_t kYoutubeBackgroundPlayback[] =
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
            function setupPIPProtection() {
                let currentPIPVideoId = null;
                let pipReplacementEnabled = true;
                let lastPlayingVideoElement = null;
                let isOriginalPIPTab = false;
                let pipOriginTabInfo = null; // Store complete tab info

                // Enhanced tab info storage
                function storeTabInfo() {
                    pipOriginTabInfo = {
                        tabId: getTabId(),
                        url: window.location.href,
                        videoId: getCurrentVideoId(),
                        timestamp: Date.now(),
                        userAgent: navigator.userAgent,
                        windowName: window.name || 'youtube_pip_tab'
                    };
                    
                    try {
                        localStorage.setItem('pip_origin_tab', JSON.stringify(pipOriginTabInfo));
                        // Also store in sessionStorage as backup
                        sessionStorage.setItem('pip_origin_tab', JSON.stringify(pipOriginTabInfo));
                    } catch (e) {
                        console.warn('Could not store tab info:', e);
                    }
                }

                function getStoredTabInfo() {
                    try {
                        let stored = localStorage.getItem('pip_origin_tab');
                        if (!stored) {
                            stored = sessionStorage.getItem('pip_origin_tab');
                        }
                        
                        if (stored) {
                            const info = JSON.parse(stored);
                            // Check if info is recent (within 1 hour)
                            if (Date.now() - info.timestamp < 3600000) {
                                return info;
                            }
                        }
                    } catch (e) {
                        console.warn('Could not get stored tab info:', e);
                    }
                    return null;
                }

                // Enhanced PiP window click handler
                function setupPiPClickHandler() {
                    if ('pictureInPictureElement' in document) {
                        document.addEventListener('enterpictureinpicture', (event) => {
                            currentPIPVideoId = getCurrentVideoId();
                            lastPlayingVideoElement = event.target;
                            isOriginalPIPTab = true;
                            
                            // Store comprehensive tab info
                            storeTabInfo();
                            setPIPStatus(currentPIPVideoId, true);
                            
                            console.log('PIP entered for video:', currentPIPVideoId);
                            
                            // Set window name for identification
                            if (!window.name) {
                                window.name = 'youtube_pip_origin_' + Date.now();
                            }
                        });

                        document.addEventListener('leavepictureinpicture', (event) => {
                            console.log('PIP exited - attempting tab restoration');
                            
                            // Force focus and visibility
                            setTimeout(() => {
                                try {
                                    // Multiple restoration attempts
                                    window.focus();
                                    
                                    // Try to bring tab to foreground
                                    if (window.parent !== window) {
                                        window.parent.focus();
                                    }
                                    
                                    // Force visibility state
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
                                    
                                    // Dispatch visibility change event
                                    document.dispatchEvent(new Event('visibilitychange'));
                                    
                                    // Ensure video continues playing
                                    const video = document.querySelector('video');
                                    if (video && !video.paused) {
                                        video.play().catch(console.warn);
                                    }
                                    
                                } catch (e) {
                                    console.warn('Error during tab restoration:', e);
                                }
                            }, 100);
                            
                            currentPIPVideoId = null;
                            lastPlayingVideoElement = null;
                            isOriginalPIPTab = false;
                            setPIPStatus(null, false);
                        });
                    }
                }

                // Rest of your existing functions...
                // (Keep all your existing functions but add the enhanced tab handling)

                function initialize() {
                    setupPiPClickHandler(); // Add this line
                    interceptYouTubeLogo();
                    interceptSearchSuggestions();
                    interceptSearchForms();
                    interceptNavigationMethods();
                    setupVideoChangeDetection();
                }

                // Enhanced initialization
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', initialize);
                } else {
                    initialize();
                }
                
                // Additional Android 15 specific handling
                if (navigator.userAgent.includes('Android')) {
                    // Override page visibility more aggressively
                    const originalHidden = Object.getOwnPropertyDescriptor(Document.prototype, 'hidden');
                    const originalVisibilityState = Object.getOwnPropertyDescriptor(Document.prototype, 'visibilityState');
                    
                    Object.defineProperty(document, 'hidden', {
                        get: function() {
                            if (isPIPActive() || currentPIPVideoId) {
                                return false;
                            }
                            return originalHidden ? originalHidden.get.call(this) : false;
                        },
                        configurable: true
                    });
                    
                    Object.defineProperty(document, 'visibilityState', {
                        get: function() {
                            if (isPIPActive() || currentPIPVideoId) {
                                return 'visible';
                            }
                            return originalVisibilityState ? originalVisibilityState.get.call(this) : 'visible';
                        },
                        configurable: true
                    });
                }
            }

            setupPIPProtection();
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

constexpr char16_t kYoutubePipButton[] = 
    uR"(
        (function() {
            let originalTabId = null;
            let videoElement = null;
            let wasPlaying = false;
            let pipWindow = null;

            const buttonElement = document.createElement('button');
            buttonElement.className = 'yt-pip-gold';
            buttonElement.setAttribute('aria-label', 'Enter Picture-in-Picture mode');
            buttonElement.title = 'Picture-in-Picture';

            // Your existing CSS...

            // Enhanced PiP handling with better restoration
            buttonElement.addEventListener('click', () => {
                videoElement = document.querySelector('video');
                if (videoElement) {
                    wasPlaying = !videoElement.paused;
                    originalTabId = window.BRAVE_TAB_ID || window.location.href;
                    
                    // Store restoration info
                    const restorationInfo = {
                        tabId: originalTabId,
                        url: window.location.href,
                        timestamp: Date.now(),
                        videoCurrentTime: videoElement.currentTime,
                        wasPlaying: wasPlaying
                    };
                    
                    try {
                        localStorage.setItem('pip_restoration_info', JSON.stringify(restorationInfo));
                    } catch (e) {
                        console.warn('Could not store restoration info:', e);
                    }
                    
                    videoElement.removeAttribute('disablePictureInPicture');
                    videoElement.requestPictureInPicture()
                        .then(pipWindow => {
                            console.log('PiP started successfully');
                            // Store PiP window reference if possible
                            window.currentPipWindow = pipWindow;
                        })
                        .catch(console.error);
                }
            });

            // Enhanced PiP event listeners for Android 15
            if (document.pictureInPictureEnabled) {
                document.addEventListener('enterpictureinpicture', (event) => {
                    console.log('Entered PiP mode');
                    
                    // Android 15 specific handling
                    if (navigator.userAgent.includes('Android')) {
                        // Set up periodic focus restoration
                        const focusInterval = setInterval(() => {
                            if (!document.pictureInPictureElement) {
                                clearInterval(focusInterval);
                                return;
                            }
                            
                            // Maintain tab visibility
                            Object.defineProperty(document, 'hidden', {
                                value: false,
                                writable: false,
                                configurable: true
                            });
                        }, 1000);
                    }
                    
                    if (event.target && wasPlaying) {
                        setTimeout(() => {
                            if (event.target.paused) {
                                event.target.play().catch(console.error);
                            }
                        }, 100);
                    }
                });

                document.addEventListener('leavepictureinpicture', (event) => {
                    console.log('Left PiP mode - Enhanced restoration for Android 15');
                    
                    // Multiple restoration strategies
                    const restoreTab = () => {
                        try {
                            // Strategy 1: Direct focus
                            if (window.focus) {
                                window.focus();
                            }
                            
                            // Strategy 2: Parent window focus
                            if (window.parent && window.parent !== window) {
                                window.parent.focus();
                            }
                            
                            // Strategy 3: Force visibility
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
                            
                            // Strategy 4: Dispatch events
                            window.dispatchEvent(new Event('focus'));
                            document.dispatchEvent(new Event('visibilitychange'));
                            
                            // Strategy 5: Try to bring to foreground (Android specific)
                            if (navigator.userAgent.includes('Android')) {
                                // Trigger user interaction to bring tab forward
                                document.body.click();
                            }
                            
                        } catch (e) {
                            console.warn('Error during tab restoration:', e);
                        }
                    };
                    
                    // Multiple restoration attempts with delays
                    setTimeout(restoreTab, 50);
                    setTimeout(restoreTab, 200);
                    setTimeout(restoreTab, 500);
                    
                    // Ensure video continues playing
                    setTimeout(() => {
                        const video = document.querySelector('video');
                        if (video && wasPlaying && video.paused) {
                            video.play().catch(console.error);
                        }
                    }, 300);
                });
            }

            // Rest of your existing code...
        })();
    )";

constexpr char16_t kAndroid15PipFix[] = 
    uR"(
        (function() {
            // Android 15 specific PiP restoration fix
            if (navigator.userAgent.includes('Android') && 
                navigator.userAgent.includes('Chrome/')) {
                
                // Override PiP behavior for Android 15
                const originalRequestPiP = HTMLVideoElement.prototype.requestPictureInPicture;
                
                HTMLVideoElement.prototype.requestPictureInPicture = function() {
                    const video = this;
                    const tabInfo = {
                        url: window.location.href,
                        tabId: window.BRAVE_TAB_ID || Date.now().toString(),
                        timestamp: Date.now()
                    };
                    
                    // Store in multiple places for reliability
                    try {
                        localStorage.setItem('android15_pip_tab', JSON.stringify(tabInfo));
                        sessionStorage.setItem('android15_pip_tab', JSON.stringify(tabInfo));
                    } catch (e) {
                        console.warn('Could not store Android 15 PiP info:', e);
                    }
                    
                    return originalRequestPiP.call(this).then(pipWindow => {
                        // Enhanced restoration setup
                        const restoreHandler = () => {
                            setTimeout(() => {
                                // Force tab to foreground
                                window.focus();
                                document.body.focus();
                                
                                // Trigger click to ensure tab activation
                                const clickEvent = new MouseEvent('click', {
                                    bubbles: true,
                                    cancelable: true,
                                    view: window
                                });
                                document.body.dispatchEvent(clickEvent);
                            }, 100);
                        };
                        
                        // Set up restoration on PiP window click (if accessible)
                        try {
                            if (pipWindow && pipWindow.addEventListener) {
                                pipWindow.addEventListener('click', restoreHandler);
                            }
                        } catch (e) {
                            // PiP window might not be accessible
                        }
                        
                        return pipWindow;
                    });
                };
                
                // Additional visibility override for Android 15
                const visibilityOverride = () => {
                    Object.defineProperty(document, 'hidden', {
                        get: () => false,
                        configurable: true
                    });
                    
                    Object.defineProperty(document, 'visibilityState', {
                        get: () => 'visible',
                        configurable: true
                    });
                };
                
                // Apply visibility override periodically
                setInterval(visibilityOverride, 2000);
                
                // Override visibility change events
                const originalAddEventListener = document.addEventListener;
                document.addEventListener = function(type, listener, options) {
                    if (type === 'visibilitychange') {
                        // Block or modify visibility change events
                        return;
                    }
                    return originalAddEventListener.call(this, type, listener, options);
                };
            }
        })();
    )";

bool IsYouTubeDomain(const GURL& url) {
  if (net::registry_controlled_domains::SameDomainOrHost(
          url, GURL("https://www.youtube.com"),
          net::registry_controlled_domains::INCLUDE_PRIVATE_REGISTRIES)) {
    return true;
  }

  return false;
}

}  // namespace

BackgroundVideoPlaybackTabHelper::BackgroundVideoPlaybackTabHelper(
    content::WebContents* contents)
    : WebContentsObserver(contents),
      content::WebContentsUserData<BackgroundVideoPlaybackTabHelper>(
          *contents) {}

BackgroundVideoPlaybackTabHelper::~BackgroundVideoPlaybackTabHelper() {}

void BackgroundVideoPlaybackTabHelper::PrimaryMainDocumentElementAvailable() {
  content::WebContents* contents = web_contents();
  
  if (!IsYouTubeDomain(contents->GetLastCommittedURL())) {
    return;
  }
  
  content::RenderFrameHost::AllowInjectingJavaScript();

  // Store tab reference for PiP restoration
  std::string tab_id = base::NumberToString(
      contents->GetPrimaryMainFrame()->GetRoutingID());
  
  std::string pip_setup_script = base::StringPrintf(R"(
    window.BRAVE_TAB_ID = '%s';
    window.BRAVE_PIP_RESTORATION = true;
  )", tab_id.c_str());
  
  contents->GetPrimaryMainFrame()->ExecuteJavaScript(
      base::UTF8ToUTF16(pip_setup_script), base::NullCallback());

  contents->GetPrimaryMainFrame()->ExecuteJavaScript(
      kYoutubeBackgroundPlayback, base::NullCallback());
  
  // Add delays and enhanced PiP handling
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

  base::SequencedTaskRunner::GetCurrentDefault()->PostDelayedTask(
      FROM_HERE,
      base::BindOnce([](content::WebContents* contents) {
        contents->GetPrimaryMainFrame()->ExecuteJavaScript(
            kYoutubeInAppPIP, base::NullCallback());
      }, contents),
      base::Milliseconds(300));

  base::SequencedTaskRunner::GetCurrentDefault()->PostDelayedTask(
    FROM_HERE,
    base::BindOnce([](content::WebContents* contents) {
    contents->GetPrimaryMainFrame()->ExecuteJavaScript(
        kAndroid15PipFix, base::NullCallback());
    }, contents),
    base::Milliseconds(400));
}

WEB_CONTENTS_USER_DATA_KEY_IMPL(BackgroundVideoPlaybackTabHelper);
