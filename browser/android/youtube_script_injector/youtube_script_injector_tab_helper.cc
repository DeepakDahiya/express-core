/* Copyright (c) 2019 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "brave/browser/android/youtube_script_injector/youtube_script_injector_tab_helper.h"

#include <memory>
#include <string>

#include "base/feature_list.h"
#include "base/logging.h"
#include "base/strings/string_util.h"
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
    )";

constexpr char16_t kYoutubeDisableHomeAutoplay[] =
    uR"(
    (function() {
        'use strict';

        function isWatchPage() {
            return window.location.pathname === '/watch' ||
                   window.location.pathname.startsWith('/shorts/');
        }

        // On non-watch pages (home feed, search, etc.), prevent inline video autoplay
        if (!isWatchPage()) {
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
            document.querySelectorAll('video').forEach(v => {
                v.autoplay = false;
                v.preload = 'none';
                if (!v.paused) v.pause();
            });
        }

        // Re-check on YouTube SPA navigation
        window.addEventListener('yt-navigate-finish', () => {
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
        });
    })();
    )";

constexpr char16_t kYoutubeInAppPIP[] =
    uR"(
    (function() {
        function setupPIPProtection() {
            let currentPIPVideoId = null;
            let pipReplacementEnabled = false;
            let lastPlayingVideoElement = null;
            let isOriginalPIPTab = false;
            let isTransitionClose = false;

            function isPIPActive() {
                return document.pictureInPictureElement !== null;
            }

            function isSearchActive() {
                const searchInput = document.querySelector('.ytSearchboxComponentInput');
                const searchContainer = document.querySelector('.ytSearchboxComponentHost');

                if (searchInput && searchContainer) {
                    const containerVisible = searchContainer.offsetParent !== null;
                    const inputHasText = searchInput.value && searchInput.value.trim().length > 0;
                    const inputHasFocus = document.activeElement === searchInput;

                    if (containerVisible && (inputHasText || inputHasFocus)) {
                        return true;
                    }
                }

                if (window.location.hash.includes('searching')) {
                    return true;
                }

                const suggestionsContainer = document.querySelector('.ytSearchboxComponentSuggestionsContainer');
                if (suggestionsContainer && suggestionsContainer.offsetParent !== null) {
                    return true;
                }

                return false;
            }

            function getSearchQuery() {
                const searchInput = document.querySelector('.ytSearchboxComponentInput, input[name="search_query"]');
                return searchInput ? searchInput.value.trim() : '';
            }

            function getCurrentVideoId() {
                const urlParams = new URLSearchParams(window.location.search);
                return urlParams.get('v');
            }

            function getCurrentVideoElement() {
                return document.querySelector('video');
            }

            function getTabId() {
                if (!window.tabId) {
                    window.tabId = 'tab_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
                }
                return window.tabId;
            }

            function setPIPStatus(videoId, isActive) {
                try {
                    const pipStatus = {
                        isActive: isActive,
                        videoId: videoId,
                        timestamp: Date.now(),
                        tabId: getTabId()
                    };
                    localStorage.setItem('pip_status', JSON.stringify(pipStatus));
                } catch (e) {
                    console.warn('Could not set PIP status:', e);
                }
            }

            function getPIPStatus() {
                try {
                    const stored = localStorage.getItem('pip_status');
                    if (stored) {
                        const status = JSON.parse(stored);
                        if (Date.now() - status.timestamp < 30000) {
                            return status;
                        }
                    }
                } catch (e) {
                    console.warn('Could not get PIP status:', e);
                }
                return { isActive: false, videoId: null, tabId: null };
            }

            function saveCurrentPlaybackState() {
                if (isPIPActive() && lastPlayingVideoElement) {
                    try {
                        // We only need to save the current time for seamless transitions.
                        localStorage.setItem('pip_video_id', lastPlayingVideoElement.currentSrc);
                        localStorage.setItem('pip_playback_time', lastPlayingVideoElement.currentTime);
                    } catch (e) {
                        // This can fail in private mode, which is fine.
                    }
                }
            }

            function signalPIPTransition(newVideoId) {
                try {
                    const transitionSignal = {
                        action: 'PIP_TRANSITION',
                        newVideoId: newVideoId,
                        timestamp: Date.now(),
                        fromTabId: getTabId()
                    };
                    localStorage.setItem('pip_transition_signal', JSON.stringify(transitionSignal));
                    
                    setTimeout(() => {
                        try {
                            localStorage.removeItem('pip_transition_signal');
                        } catch (e) {}
                    }, 5000);
                } catch (e) {
                    console.warn('Could not signal PIP transition:', e);
                }
            }

            function checkForPIPTransitionSignal() {
                try {
                    const stored = localStorage.getItem('pip_transition_signal');
                    if (stored) {
                        const signal = JSON.parse(stored);

                        if (Date.now() - signal.timestamp < 5000 &&
                            signal.fromTabId !== getTabId()) {

                            console.log('Received PIP transition signal:', signal);
                            localStorage.removeItem('pip_transition_signal');

                            if (isPIPActive()) {
                                sendPlaybackState();
                                isTransitionClose = true;
                                closePIP();
                            } else {
                                // PIP element not in this document but we may
                                // still own the pip_status — clear it so the
                                // new tab can proceed.
                                setPIPStatus(null, false);
                                currentPIPVideoId = null;
                                lastPlayingVideoElement = null;
                                isOriginalPIPTab = false;
                            }
                        }
                    }
                } catch (e) {
                    // Ignore errors
                }
            }

            // --- NEW FUNCTION ---
            // Sends a signal to a specific tab ID, telling it to close.
            function signalTabToClose(tabId) {
                if (!tabId) return;
                try {
                    const closeSignal = {
                        targetTabId: tabId,
                        timestamp: Date.now()
                    };
                    localStorage.setItem('pip_close_tab_signal', JSON.stringify(closeSignal));
                    console.log('Sent close signal to tab:', tabId);
                } catch (e) {
                    console.warn('Could not send close tab signal:', e);
                }
            }

            // --- NEW FUNCTION ---
            // Checks if this tab has received a signal to close itself.
            function checkForCloseSignal() {
                try {
                    const stored = localStorage.getItem('pip_close_tab_signal');
                    if (stored) {
                        const signal = JSON.parse(stored);
                        // Check if the signal is fresh and targeted at this specific tab
                        if (Date.now() - signal.timestamp < 5000 && signal.targetTabId === getTabId()) {
                            console.log('Received close signal. This tab will now close.');
                            localStorage.removeItem('pip_close_tab_signal');
                            window.close();
                        }
                    }
                } catch (e) {
                    // Ignore errors
                }
            }

            function sendPlaybackState() {
                try {
                    if (isPIPActive() && lastPlayingVideoElement) {
                        const state = {
                            videoId: currentPIPVideoId,
                            currentTime: lastPlayingVideoElement.currentTime,
                            duration: lastPlayingVideoElement.duration,
                            wasPlaying: !lastPlayingVideoElement.paused,
                            volume: lastPlayingVideoElement.volume,
                            muted: lastPlayingVideoElement.muted,
                            timestamp: Date.now()
                        };
                        
                        localStorage.setItem('pip_playback_state', JSON.stringify(state));
                        console.log('Sent playback state:', state);
                    }
                } catch (e) {
                    console.warn('Could not send playback state:', e);
                }
            }

            function getPlaybackState() {
                try {
                    const stored = localStorage.getItem('pip_playback_state');
                    if (stored) {
                        const state = JSON.parse(stored);
                        if (Date.now() - state.timestamp < 3000) {
                            localStorage.removeItem('pip_playback_state');
                            return state;
                        }
                    }
                } catch (e) {
                    console.warn('Could not get playback state:', e);
                }
                return null;
            }

            function applyPlaybackState(videoElement, state) {
                try {
                    if (state.videoId === getCurrentVideoId()) {
                        console.log('Same video, syncing playback time:', state.currentTime);
                        
                        const syncTime = () => {
                            if (videoElement.readyState >= 2) {
                                videoElement.currentTime = state.currentTime;
                                if (state.wasPlaying && videoElement.paused) {
                                    videoElement.play().catch(console.warn);
                                }
                            } else {
                                setTimeout(syncTime, 100);
                            }
                        };
                        
                        syncTime();
                    }
                    
                    if (typeof state.volume === 'number') {
                        videoElement.volume = state.volume;
                    }
                    if (typeof state.muted === 'boolean') {
                        videoElement.muted = state.muted;
                    }
                    
                } catch (e) {
                    console.warn('Could not apply playback state:', e);
                }
            }

            // Attempts to enter PIP, retrying a few times if the video element
            // isn't ready yet (YouTube SPA may swap elements during navigation).
            function startPIPForNewVideo(videoElement, videoId, previousPIPTabId) {
                let waitAttempts = 0;
                const maxWaitAttempts = 20;  // 20 x 100ms = 2 seconds max wait
                const waitInterval = 100;

                // Phase 1: Wait for the old tab to actually exit PIP.
                // The old tab receives our transition signal via the storage event
                // and calls closePIP() + setPIPStatus(null, false).
                // We poll pip_status until it clears.
                function waitForOldPIPToExit() {
                    const pipStatus = getPIPStatus();
                    const oldPIPCleared = !pipStatus.isActive || pipStatus.videoId === videoId;

                    if (oldPIPCleared) {
                        console.log('Old PIP cleared, proceeding to request PIP');
                        attemptPIP();
                        return;
                    }

                    if (++waitAttempts < maxWaitAttempts) {
                        setTimeout(waitForOldPIPToExit, waitInterval);
                    } else {
                        // Old tab didn't respond in time — force ahead anyway.
                        console.warn('Old PIP did not clear in time, forcing PIP request');
                        attemptPIP();
                    }
                }

                // Phase 2: Actually request PIP on the new video.
                let pipAttempts = 0;
                const maxPIPAttempts = 6;
                const retryInterval = 250;

                function attemptPIP() {
                    // Re-fetch the video element each attempt — YouTube may
                    // have replaced the original element during SPA navigation.
                    const video = getCurrentVideoElement() || videoElement;
                    if (!video || typeof video.requestPictureInPicture !== 'function') {
                        if (++pipAttempts < maxPIPAttempts) {
                            setTimeout(attemptPIP, retryInterval);
                        } else {
                            console.warn('PIP: gave up — no usable video element');
                            signalTabToClose(previousPIPTabId);
                        }
                        return;
                    }

                    const tryRequest = () => {
                        video.requestPictureInPicture()
                            .then(() => {
                                currentPIPVideoId = videoId;
                                lastPlayingVideoElement = video;
                                isOriginalPIPTab = true;
                                setPIPStatus(videoId, true);
                                console.log('PIP started for new video:', videoId);
                                signalTabToClose(previousPIPTabId);
                            })
                            .catch(err => {
                                console.warn('PIP request failed (attempt ' + (pipAttempts+1) + '):', err);
                                if (++pipAttempts < maxPIPAttempts) {
                                    setTimeout(attemptPIP, retryInterval);
                                } else {
                                    console.warn('PIP: all attempts exhausted');
                                    if (video.paused) video.play().catch(console.warn);
                                    signalTabToClose(previousPIPTabId);
                                }
                            });
                    };

                    if (!video.paused) {
                        tryRequest();
                    } else {
                        video.play().then(tryRequest).catch(err => {
                            console.warn('PIP: could not resume video:', err);
                            if (++pipAttempts < maxPIPAttempts) {
                                setTimeout(attemptPIP, retryInterval);
                            } else {
                                signalTabToClose(previousPIPTabId);
                            }
                        });
                    }
                }

                // Start Phase 1 — wait for old PIP to exit first.
                waitForOldPIPToExit();
            }

            // --- MODIFIED FUNCTION ---
            // Captures the old tab's ID to pass it along.
            function handleVideoPlay(videoElement) {
                const currentVideoId = getCurrentVideoId();
                
                console.log('Video play detected:', currentVideoId);
                
                const pipStatus = getPIPStatus();
                
                if (currentVideoId && pipStatus.isActive && pipStatus.videoId !== currentVideoId) {
                    console.log('New video playing, PIP detected for different video.');
                    
                    if (!isPIPActive()) {
                        console.log('New tab with different video, closing existing PIP');
                        signalPIPTransition(currentVideoId);
                    } else {
                        if (pipReplacementEnabled) {
                            replacePIPVideo(videoElement, currentVideoId);
                        } else {
                            closePIP();
                        }
                    }
                }
                
                lastPlayingVideoElement = videoElement;
            }

            function handleVideoChange() {
                const currentVideoId = getCurrentVideoId();
                const videoElement = getCurrentVideoElement();

                if (!currentVideoId || !videoElement || window.location.pathname !== '/watch') {
                    return;
                }

                if (isPIPActive() && currentPIPVideoId && currentPIPVideoId !== currentVideoId) {
                    if (pipReplacementEnabled) {
                        replacePIPVideo(videoElement, currentVideoId);
                    } else {
                        closePIP();
                    }
                }
            }

            function replacePIPVideo(newVideoElement, newVideoId) {
                try {
                    console.log('Replacing PIP video with:', newVideoId);

                    if (document.pictureInPictureElement) {
                        isTransitionClose = true;
                        document.exitPictureInPicture().then(() => {
                            setTimeout(() => {
                                if (newVideoElement &&
                                    typeof newVideoElement.requestPictureInPicture === 'function' &&
                                    !newVideoElement.paused) {

                                    newVideoElement.requestPictureInPicture()
                                        .then(() => {
                                            currentPIPVideoId = newVideoId;
                                            lastPlayingVideoElement = newVideoElement;
                                            setPIPStatus(newVideoId, true);
                                            console.log('PIP replaced with new video:', newVideoId);
                                        })
                                        .catch(err => {
                                            console.warn('Failed to enter PIP with new video:', err);
                                            currentPIPVideoId = null;
                                            setPIPStatus(null, false);
                                        });
                                } else {
                                    currentPIPVideoId = null;
                                    setPIPStatus(null, false);
                                    console.log('New video cannot do PIP, closed old PIP');
                                }
                            }, 200);
                        }).catch(err => {
                            console.warn('Failed to exit current PIP:', err);
                        });
                    }
                } catch (error) {
                    console.warn('Error replacing PIP video:', error);
                }
            }

            function closePIP() {
                try {
                    if (document.pictureInPictureElement) {
                        document.exitPictureInPicture().then(() => {
                            currentPIPVideoId = null;
                            lastPlayingVideoElement = null;
                            isOriginalPIPTab = false;
                            setPIPStatus(null, false);
                            console.log('PIP closed due to video change');
                        }).catch(err => {
                            console.warn('Failed to close PIP:', err);
                        });
                    } else {
                        setPIPStatus(null, false);
                    }
                } catch (error) {
                    console.warn('Error closing PIP:', error);
                }
            }

            function setupVideoChangeDetection() {
                document.addEventListener('enterpictureinpicture', (event) => {
                    currentPIPVideoId = getCurrentVideoId();
                    lastPlayingVideoElement = event.target;
                    isOriginalPIPTab = true;
                    setPIPStatus(currentPIPVideoId, true);
                    console.log('PIP entered for video:', currentPIPVideoId);

                    // const videoElement = document.querySelector('video');
                    // if (videoElement) {
                    //     // Save the current playback state
                    //     localStorage.setItem('pip_video_id', videoElement.currentSrc);
                    //     localStorage.setItem('pip_playback_time', videoElement.currentTime);
                    // }
                });

                document.addEventListener('leavepictureinpicture', (event) => {
                    // Defensive wrapper: any throw here happens inside a media
                    // event handler during the PiP-to-resume transition, which
                    // on newer WebView (Android 15/16) can take down the
                    // renderer. Swallow everything.
                    try {
                        const wasTransitionClose = isTransitionClose;
                        isTransitionClose = false;

                        if (wasTransitionClose) {
                            // PiP closed because a new video is taking over in
                            // another tab. Pause the old video so it doesn't
                            // keep playing in background.
                            console.log('Left PiP - transition close, pausing old video');
                            const video = event.target || document.querySelector('video');
                            if (video && !video.paused) {
                                video.pause();
                            }
                            currentPIPVideoId = null;
                            lastPlayingVideoElement = null;
                            isOriginalPIPTab = false;
                            setPIPStatus(null, false);
                            return;
                        }

                        // User-initiated PiP exit (tapped expand or closed PiP).
                        // The native side switches back to the watch tab in
                        // onPictureInPictureModeChanged; nothing for JS to do
                        // beyond clearing local state and nudging playback.
                        console.log('Left PiP - user exit');

                        currentPIPVideoId = null;
                        lastPlayingVideoElement = null;
                        isOriginalPIPTab = false;
                        setPIPStatus(null, false);

                        // Only nudge playback back if the tab returned to the
                        // foreground (EXPAND). On CLOSE (X) the tab stays
                        // backgrounded, and the kYoutubePipButton handler stops
                        // the audio -- do NOT resume it here or the background
                        // music keeps playing after the user dismissed PiP.
                        // Don't touch document.hidden/visibilityState because
                        // Chromium owns those and redefining throws on newer
                        // WebView, which used to crash the renderer here.
                        setTimeout(() => {
                            try {
                                if (!document.hasFocus()) return;
                                const video = document.querySelector('video');
                                if (video && video.paused) {
                                    video.play().catch(() => {});
                                }
                            } catch (e) {}
                        }, 200);
                    } catch (e) {
                        console.warn('leavepictureinpicture handler error:', e);
                    }
                });

                document.addEventListener('play', (event) => {
                    if (event.target.tagName === 'VIDEO') {
                        console.log('Video play event detected');
                        setTimeout(() => handleVideoPlay(event.target), 100);
                    }
                }, true);

                document.addEventListener('loadstart', (event) => {
                    if (event.target.tagName === 'VIDEO') {
                        console.log('Video loadstart event detected');
                        setTimeout(() => {
                            const currentVideoId = getCurrentVideoId();
                            if (currentVideoId) {
                                const pipStatus = getPIPStatus();
                                if (pipStatus.isActive && pipStatus.videoId !== currentVideoId) {
                                    if (!isPIPActive()) {
                                        signalPIPTransition(currentVideoId);
                                    }
                                }
                            }
                            
                            if (isPIPActive() && currentPIPVideoId &&
                                currentVideoId && currentPIPVideoId !== currentVideoId) {
                                handleVideoChange();
                            }
                        }, 100);
                    }
                }, true);

                // Listen for cross-tab localStorage changes — fires immediately
                // when another tab writes to localStorage (much faster than polling).
                window.addEventListener('storage', (event) => {
                    if (event.key === 'pip_transition_signal') {
                        checkForPIPTransitionSignal();
                    } else if (event.key === 'pip_close_tab_signal') {
                        checkForCloseSignal();
                    }
                });

                // Fallback polling in case storage events are missed.
                setInterval(() => {
                    checkForPIPTransitionSignal();
                    checkForCloseSignal();
                }, 1000);

                setInterval(saveCurrentPlaybackState, 500);

                setInterval(() => {
                    if (isPIPActive() && currentPIPVideoId) {
                        setPIPStatus(currentPIPVideoId, true);
                    }
                }, 10000);

                let lastVideoId = getCurrentVideoId();
                const checkVideoChange = () => {
                    const currentVideoId = getCurrentVideoId();
                    if (currentVideoId && currentVideoId !== lastVideoId) {
                        console.log('Video changed from', lastVideoId, 'to', currentVideoId);
                        handleVideoChange();
                        lastVideoId = currentVideoId;
                    }
                };

                setInterval(checkVideoChange, 2000);

                window.addEventListener('yt-navigate-finish', () => {
                    setTimeout(checkVideoChange, 500);
                });

                const videoObserver = new MutationObserver((mutations) => {
                    mutations.forEach(mutation => {
                        mutation.addedNodes.forEach(node => {
                            if (node.nodeType === 1 && node.tagName === 'VIDEO') {
                                console.log('New video element detected');

                                node.addEventListener('play', () => {
                                    console.log('New video element started playing');
                                    setTimeout(() => handleVideoPlay(node), 100);
                                });

                                node.addEventListener('loadstart', () => {
                                    console.log('New video element loadstart');
                                    setTimeout(() => {
                                        const currentVideoId = getCurrentVideoId();
                                        if (currentVideoId) {
                                            const pipStatus = getPIPStatus();
                                            if (pipStatus.isActive && pipStatus.videoId !== currentVideoId) {
                                                if (!isPIPActive()) {
                                                    signalPIPTransition(currentVideoId);
                                                }
                                            }
                                        }
                                        
                                        if (isPIPActive() && currentPIPVideoId &&
                                            currentVideoId && currentPIPVideoId !== currentVideoId) {
                                            handleVideoChange();
                                        }
                                    }, 100);
                                });

                                setTimeout(checkVideoChange, 500);
                            }
                        });
                    });
                });

                videoObserver.observe(document.body, {
                    childList: true,
                    subtree: true
                });
            }

            function openNewTabWithPIPAwareness(url) {
                const newTab = window.open(url, '_blank');
                if (newTab) {
                    newTab.focus();
                    setTimeout(() => newTab.focus(), 100);

                    setTimeout(() => {
                        injectPIPManagementScript(newTab);
                    }, 2000);
                }
                return newTab;
            }

            function injectPIPManagementScript(tab) {
                try {
                    if (!tab.document) return;

                    const script = tab.document.createElement('script');
                    script.textContent = `
                    (function() {
                        function checkPIPTakeover() {
                        const videoElement = document.querySelector('video');
                        const currentVideoId = new URLSearchParams(window.location.search).get('v');
                        
                        if (videoElement && currentVideoId && window.location.pathname === '/watch') {
                            addPIPIndicator(videoElement);
                        }
                        }
                        
                        function addPIPIndicator(videoElement) {
                        if (document.getElementById('pip-takeover-btn')) return;
                        
                        const pipBtn = document.createElement('button');
                        pipBtn.id = 'pip-takeover-btn';
                        pipBtn.innerHTML = '📺 PIP';
                        pipBtn.style.cssText = \`
                            position: absolute; top: 10px; right: 10px; z-index: 1000;
                            background: rgba(0,0,0,0.7); color: white; border: none;
                            padding: 8px 12px; border-radius: 6px; cursor: pointer;
                            font-size: 12px; font-family: Arial, sans-serif;
                            transition: all 0.2s ease;
                        \`;
                        
                        pipBtn.onmouseover = () => {
                            pipBtn.style.background = 'rgba(255,68,68,0.9)';
                        };
                        
                        pipBtn.onmouseout = () => {
                            pipBtn.style.background = 'rgba(0,0,0,0.7)';
                        };
                        
                        pipBtn.onclick = () => {
                            if (videoElement.requestPictureInPicture) {
                            videoElement.requestPictureInPicture().catch(console.warn);
                            }
                        };
                        
                        const videoContainer = videoElement.closest('.html5-video-player') || videoElement.parentElement;
                        if (videoContainer) {
                            videoContainer.style.position = 'relative';
                            videoContainer.appendChild(pipBtn);
                            
                            setTimeout(() => {
                            if (pipBtn.parentElement) {
                                pipBtn.style.opacity = '0';
                                setTimeout(() => pipBtn.remove(), 300);
                            }
                            }, 5000);
                        }
                        }
                        
                        if (document.readyState === 'loading') {
                        document.addEventListener('DOMContentLoaded', checkPIPTakeover);
                        } else {
                        checkPIPTakeover();
                        }
                        
                        setTimeout(checkPIPTakeover, 1000);
                    })();
                    `;

                    tab.document.head.appendChild(script);
                } catch (e) {
                    console.warn('Could not inject PIP management script:', e);
                }
            }

            function extractSearchQuery(element) {
                const textElement = element.querySelector('.ytSuggestionComponentText');
                if (textElement) {
                    const ariaLabel = textElement.getAttribute('aria-label');
                    if (ariaLabel) {
                        return ariaLabel;
                    }
                }

                const spans = element.querySelectorAll('.ytSuggestionComponentLeftContainer span span');
                let searchText = '';
                spans.forEach(span => {
                    searchText += span.textContent;
                });

                return searchText.trim();
            }

            function closeSearchDropdown() {
                const backButton = document.querySelector('.mobile-topbar-back-arrow[aria-label="Close search"]');
                if (backButton) {
                    setTimeout(() => {
                        backButton.click();
                    }, 100);
                }
            }

            function openSearchInNewTab(searchQuery) {
                if (!searchQuery) return false;

                const encodedQuery = encodeURIComponent(searchQuery);
                const searchUrl = `https://m.youtube.com/results?sp=mAEA&search_query=${encodedQuery}`;

                const newTab = window.open(searchUrl, '_blank');
                if (newTab) {
                    newTab.focus();
                    setTimeout(() => newTab.focus(), 100);
                    closeSearchDropdown();
                    return true;
                }
                return false;
            }

            function handleLogoClick(event) {
                const isVideoPage = window.location.pathname === '/watch';

                if (isVideoPage && isPIPActive()) {
                    event.preventDefault();
                    event.stopPropagation();
                    event.stopImmediatePropagation();

                    openNewTabWithPIPAwareness('https://www.youtube.com/');

                    return false;
                }
                return true;
            }

            function handleSearchSuggestionClick(event) {
                const isVideoPage = window.location.pathname === '/watch';

                if (isVideoPage && isPIPActive()) {
                    event.preventDefault();
                    event.stopPropagation();
                    event.stopImmediatePropagation();

                    const suggestionElement = event.target.closest('.ytSuggestionComponentSuggestion');
                    if (suggestionElement) {
                        const searchQuery = extractSearchQuery(suggestionElement);
                        if (searchQuery) {
                            const encodedQuery = encodeURIComponent(searchQuery);
                            const searchUrl = `https://m.youtube.com/results?sp=mAEA&search_query=${encodedQuery}`;

                            setTimeout(() => {
                                openNewTabWithPIPAwareness(searchUrl);
                                closeSearchDropdown();
                            }, 10);
                        }
                    }

                    return false;
                }
                return true;
            }

            function handleSearchSubmit(event) {
                const isVideoPage = window.location.pathname === '/watch';

                if (isVideoPage && isPIPActive()) {
                    const isSearchSubmission = event.type === 'submit' ||
                        event.target.closest('form[role="search"]') ||
                        event.target.matches('button[type="submit"]');

                    if (isSearchSubmission) {
                        event.preventDefault();
                        event.stopPropagation();
                        event.stopImmediatePropagation();

                        const searchInput = document.querySelector('#search, input[name="search_query"], .ytSearchboxComponentInput');
                        if (searchInput && searchInput.value.trim()) {
                            const encodedQuery = encodeURIComponent(searchInput.value.trim());
                            const searchUrl = `https://m.youtube.com/results?sp=mAEA&search_query=${encodedQuery}`;

                            setTimeout(() => {
                                openNewTabWithPIPAwareness(searchUrl);
                                closeSearchDropdown();
                            }, 10);
                        }

                        return false;
                    }
                }
                return true;
            }

            function handleSearchButtonClick(event) {
                const isVideoPage = window.location.pathname === '/watch';

                if (isVideoPage && isPIPActive() && isSearchActive()) {
                    event.preventDefault();
                    event.stopPropagation();
                    event.stopImmediatePropagation();

                    const searchQuery = getSearchQuery();
                    if (searchQuery) {
                        setTimeout(() => {
                            openSearchInNewTab(searchQuery);
                        }, 10);
                    }

                    return false;
                }
                return true;
            }

            function handleSearchKeydown(event) {
                const isVideoPage = window.location.pathname === '/watch';

                if (event.key === 'Enter' && isVideoPage && isPIPActive() && isSearchActive()) {
                    event.preventDefault();
                    event.stopPropagation();
                    event.stopImmediatePropagation();

                    const searchQuery = getSearchQuery();
                    if (searchQuery) {
                        setTimeout(() => {
                            openSearchInNewTab(searchQuery);
                        }, 10);
                    }

                    return false;
                }
                return true;
            }

            function interceptYouTubeLogo() {
                const logoSelectors = [
                    'ytm-home-logo button',
                    'ytm-home-logo button.mobile-topbar-header-endpoint',
                    'button[aria-label*="YouTube"][aria-label*="Home"]',
                    'button[key="logo"]',
                    'ytd-topbar-logo-renderer a',
                    'a[href="/"]',
                    'a[href="https://www.youtube.com/"]',
                    '#logo a',
                    '.ytd-topbar-logo-renderer a',
                    '.mobile-topbar-header-endpoint',
                    'c3-icon.mobile-topbar-logo',
                    '[aria-label*="YouTube Home"]',
                    '[aria-label*="YouTube Premium Home"]'
                ];

                logoSelectors.forEach(selector => {
                    const elements = document.querySelectorAll(selector);
                    elements.forEach(element => {
                        element.removeEventListener('click', handleLogoClick, true);
                        element.removeEventListener('click', handleLogoClick, false);
                        element.addEventListener('click', handleLogoClick, true);
                        element.addEventListener('click', handleLogoClick, false);
                    });
                });
            }

            function interceptSearchSuggestions() {
                const suggestionSelectors = [
                    '.ytSuggestionComponentSuggestion',
                    '.ytSuggestionComponentText',
                    '[role="option"].ytSuggestionComponentText',
                    '.ytSuggestionComponentLeftContainer span[role="button"]',
                    '.ytSuggestionComponentLeftContainer span'
                ];

                suggestionSelectors.forEach(selector => {
                    const elements = document.querySelectorAll(selector);
                    elements.forEach(element => {
                        element.removeEventListener('click', handleSearchSuggestionClick, true);
                        element.removeEventListener('click', handleSearchSuggestionClick, false);
                        element.removeEventListener('mousedown', handleSearchSuggestionClick, true);
                        element.removeEventListener('mousedown', handleSearchSuggestionClick, false);

                        element.addEventListener('click', handleSearchSuggestionClick, true);
                        element.addEventListener('click', handleSearchSuggestionClick, false);
                        element.addEventListener('mousedown', handleSearchSuggestionClick, true);
                        element.addEventListener('mousedown', handleSearchSuggestionClick, false);

                        element.addEventListener('touchstart', handleSearchSuggestionClick, true);
                        element.addEventListener('touchend', handleSearchSuggestionClick, true);
                    });
                });
            }

            function interceptSearchForms() {
                const searchForms = document.querySelectorAll(
                    'form[role="search"], ' +
                    '#search-form, ' +
                    '.ytSearchboxComponentSearchForm, ' +
                    'form[action="/results"]'
                );

                searchForms.forEach(form => {
                    form.removeEventListener('submit', handleSearchSubmit, true);
                    form.removeEventListener('submit', handleSearchSubmit, false);
                    form.addEventListener('submit', handleSearchSubmit, true);
                    form.addEventListener('submit', handleSearchSubmit, false);
                });

                const searchButtons = document.querySelectorAll(
                    '.ytSearchboxComponentSearchButton, ' +
                    'button[aria-label="Search"], ' +
                    'button[title="Search YouTube"]'
                );

                searchButtons.forEach(button => {
                    button.removeEventListener('click', handleSearchButtonClick, true);
                    button.removeEventListener('click', handleSearchButtonClick, false);
                    button.addEventListener('click', handleSearchButtonClick, true);
                    button.addEventListener('click', handleSearchButtonClick, false);
                });

                const searchInputs = document.querySelectorAll(
                    '.ytSearchboxComponentInput, ' +
                    'input[name="search_query"], ' +
                    'input[role="combobox"]'
                );

                searchInputs.forEach(input => {
                    input.removeEventListener('keydown', handleSearchKeydown, true);
                    input.removeEventListener('keydown', handleSearchKeydown, false);
                    input.addEventListener('keydown', handleSearchKeydown, true);
                    input.addEventListener('keydown', handleSearchKeydown, false);
                });
            }

            function interceptNavigationMethods() {
                const originalPushState = history.pushState;
                const originalReplaceState = history.replaceState;

                history.pushState = function (state, title, url) {
                    if (isPIPActive() && window.location.pathname === '/watch') {
                        if (url === '/' ||
                            url === 'https://www.youtube.com/' ||
                            url.includes('/results?') ||
                            url.includes('search_query=')) {

                            const fullUrl = url.startsWith('/') ? `https://m.youtube.com${url}` : url;
                            setTimeout(() => {
                                const newTab = window.open(fullUrl, '_blank');
                                if (newTab) {
                                    newTab.focus();

                                    if (url.includes('/results?') || url.includes('search_query=')) {
                                        closeSearchDropdown();
                                    }
                                }
                            }, 10);
                            return;
                        }
                    }
                    return originalPushState.apply(this, arguments);
                };

                history.replaceState = function (state, title, url) {
                    if (isPIPActive() && window.location.pathname === '/watch') {
                        if (url === '/' ||
                            url === 'https://www.youtube.com/' ||
                            url.includes('/results?') ||
                            url.includes('search_query=')) {

                            const fullUrl = url.startsWith('/') ? `https://m.youtube.com${url}` : url;
                            setTimeout(() => {
                                const newTab = window.open(fullUrl, '_blank');
                                if (newTab) {
                                    newTab.focus();

                                    if (url.includes('/results?') || url.includes('search_query=')) {
                                        closeSearchDropdown();
                                    }
                                }
                            }, 10);
                            return;
                        }
                    }
                    return originalReplaceState.apply(this, arguments);
                };
            }

            function initialize() {
                interceptYouTubeLogo();
                interceptSearchSuggestions();
                interceptSearchForms();
                interceptNavigationMethods();
                setupVideoChangeDetection();
            }

            initialize();

            const observer = new MutationObserver((mutations) => {
                let shouldReintercept = false;

                mutations.forEach(mutation => {
                    mutation.addedNodes.forEach(node => {
                        if (node.nodeType === 1) {
                            if (node.matches && (
                                node.matches('ytm-home-logo') ||
                                node.matches('ytd-topbar-logo-renderer') ||
                                node.matches('.ytSuggestionComponentSuggestion') ||
                                node.matches('.ytSearchboxComponentHost') ||
                                node.matches('yt-searchbox') ||
                                node.querySelector('ytm-home-logo, ytd-topbar-logo-renderer, .ytSuggestionComponentSuggestion, .ytSearchboxComponentHost, yt-searchbox')
                            )) {
                                shouldReintercept = true;
                            }
                        }
                    });
                });

                if (shouldReintercept) {
                    setTimeout(initialize, 100);
                }
            });

            observer.observe(document.body, {
                childList: true,
                subtree: true
            });

            window.addEventListener('yt-navigate-start', initialize);
            window.addEventListener('yt-navigate-finish', initialize);

            document.addEventListener('focus', (event) => {
                if (event.target.matches('.ytSearchboxComponentInput, input[name="search_query"], input[role="combobox"]')) {
                    setTimeout(() => {
                        interceptSearchSuggestions();
                        interceptSearchForms();
                    }, 500);
                }
            }, true);

            document.addEventListener('input', (event) => {
                if (event.target.matches('.ytSearchboxComponentInput, input[name="search_query"], input[role="combobox"]')) {
                    setTimeout(interceptSearchSuggestions, 300);
                }
            }, true);
        }

        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', setupPIPProtection);
        } else {
            setupPIPProtection();
        }
    }());
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

const char16_t kRemoveYoutubeComment[] =
    uR"(
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

        window.addEventListener('yt-navigate-finish', fullCleanup);
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
                touch-action: manipulation;
                isolation: isolate;
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
                // Distinguish CLOSE (X button) from EXPAND (tap PiP -> back to
                // the tab). focused == EXPAND on devices where the native
                // restore ran and refocused this tab. Unfocused is AMBIGUOUS:
                // on Android 16 the expand tap skips the mode-change callback,
                // no native restore runs, and this tab stays backgrounded just
                // like on CLOSE -- there the discriminator is the video state
                // Chromium set (CLOSE pauses via should_pause_video, EXPAND
                // keeps playing). visibilityState is force-pinned to 'visible'
                // by the background-playback shim and can't be used here.
                //
                //   EXPAND (focused)   -> make sure playback continues (Samsung
                //             One UI can leave the player paused after
                //             restoring the activity, so retry across ~2s).
                //   unfocused          -> ambiguous (Android 16 delivers expand
                //             as a close: video paused, no focus). The native
                //             onResume expand hook restores the tab on expand,
                //             so wait for the outcome: focus within ~5s ->
                //             expand, resume playback; never focused -> close,
                //             hold the audio paused.
                const DECIDE_MS = 300;
                setTimeout(() => {
                    if (document.hasFocus()) {
                        const RETRY_DELAYS_MS = [0, 300, 700, 1200, 1800];
                        let resumed = false;
                        RETRY_DELAYS_MS.forEach((delay) => {
                            setTimeout(() => {
                                if (resumed) return;
                                const video = document.querySelector('video');
                                if (!video) return;
                                if (!video.paused) { resumed = true; return; }
                                const p = video.play();
                                if (p && typeof p.then === 'function') {
                                    p.then(() => { resumed = true; }).catch(() => {});
                                }
                            }, delay);
                        });
                    } else {
                        // Unfocused at decide time is AMBIGUOUS: on Android 16
                        // an EXPAND is delivered exactly like a CLOSE -- the
                        // mode-change callback is skipped, the teardown falls
                        // to OverlayWindowAndroid::OnActivityStopped which
                        // pauses the video (should_pause_video=true), and this
                        // tab is not yet refocused. The native onResume expand
                        // hook (BravePictureInPictureActivity) restores the
                        // watch tab in that case, so discriminate by outcome:
                        // if focus arrives within ~5s the exit was an EXPAND
                        // and playback resumes; if it never arrives it was a
                        // CLOSE and the video is held paused so background
                        // audio stays stopped.
                        const SETTLE_DELAYS_MS = [0, 250, 600, 1200, 2000, 3200, 4800];
                        let settled = false;
                        SETTLE_DELAYS_MS.forEach((delay) => {
                            setTimeout(() => {
                                if (settled) return;
                                const v = document.querySelector('video');
                                if (!v) return;
                                if (document.hasFocus()) {
                                    // Expand after all: the restore landed and
                                    // this tab is foreground again.
                                    settled = true;
                                    if (v.paused && !v.ended) {
                                        try {
                                            const p = v.play();
                                            if (p && p.catch) p.catch(() => {});
                                        } catch (e) {}
                                    }
                                    return;
                                }
                                if (!v.paused) {
                                    try { v.pause(); } catch (e) {}
                                }
                            }, delay);
                        });
                    }
                }, DECIDE_MS);
            });
        }

        function updateButtonVisibility() {
            // Button is currently hidden; no DOM observation needed.
            buttonElement.style.display = 'none';
        }

        updateButtonVisibility();

        // visibilitychange is already blocked by kYoutubeBackgroundPlayback.

    })();
)";

// Triggers Web PiP directly on the video element — same logic as the
// kYoutubePipButton click handler, executed with user activation so that
// requestPictureInPicture() is allowed by the browser.
const char16_t kYoutubePipTrigger[] =
    uR"(
    (function() {
        var video = document.querySelector('video');
        if (video) {
            console.error('[BravePiPDebug] kYoutubePipTrigger requestPictureInPicture url=' + location.href);
            video.removeAttribute('disablePictureInPicture');
            video.requestPictureInPicture()
                .then(function() {
                    console.error('[BravePiPDebug] kYoutubePipTrigger request resolved');
                })
                .catch(function(error) {
                    console.error('[BravePiPDebug] kYoutubePipTrigger request failed', error);
                });
        } else {
            console.error('[BravePiPDebug] kYoutubePipTrigger no video url=' + location.href);
        }
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

// Add this new constant for the tab restoration fix.
constexpr char16_t kYoutubePipNavigationFix[] =
    uR"(
    (function() {
        if (window.bravePipFixAttached) return;
        window.bravePipFixAttached = true;

        // Bring the user back to the source (watch) tab when PiP is dismissed.
        //
        // On some Android versions, tapping either the PiP expand/fullscreen
        // button or the close button tears down the PiP overlay activity and
        // resurfaces the browser on whatever tab is currently selected (the
        // YouTube home tab we open on PiP entry) WITHOUT delivering
        // onPictureInPictureModeChanged(false) to the overlay activity. When
        // that callback is skipped, BraveActivity's native tab restore never
        // runs and the user is stranded on the home tab while this source tab
        // keeps playing in the background.
        //
        // 'leavepictureinpicture' does fire reliably here, in the source tab's
        // own document, so use it as the trigger and ask the native side to
        // select the source tab by the tab id it captured before PiP entry.
        // Restoring by id (not URL) avoids selecting the wrong tab when YouTube
        // rewrites the watch URL, which is why the earlier URL-based restore was
        // removed. The native side is idempotent and no-ops if we are already on
        // the source tab, so this is safe alongside the native restore path on
        // devices where the mode-change callback does fire.
        document.addEventListener('leavepictureinpicture', function(event) {
            // Only return to the source tab when the user EXPANDS the PiP back
            // into the page -- in which case the video keeps playing inline. Do
            // NOT return on the CLOSE (X) button: closing pauses the video
            // (should_pause_video), so the user is done and wants to stay where
            // they are, not be dragged onto the old video tab. Check the playback
            // state a beat later so the pause from a close has settled. The native
            // bridge additionally skips the restore if the user has moved to a
            // different YouTube watch page (a new video superseding this PiP).
            var video = event.target || document.querySelector('video');
            setTimeout(function() {
                try {
                    // Restore the watch tab only when the video is still
                    // playing (an expand that kept playback alive). On CLOSE
                    // Chromium pauses it (should_pause_video) and every resume
                    // handler is focus-gated, so paused videos are never
                    // restored from here. Do NOT require document.hasFocus():
                    // focus can lag the restore. NOTE: on Android 16 an expand
                    // arrives with the video PAUSED too (teardown falls to
                    // OnActivityStopped) -- that path is handled by the native
                    // onResume expand hook in BravePictureInPictureActivity,
                    // not by this bridge.
                    if (!video || video.paused || video.ended) {
                        console.warn(
                            '[BravePiPDebug] leavepictureinpicture not playing; skip restore');
                        return;
                    }
                    if (window.BravePiPNavigator &&
                        typeof window.BravePiPNavigator.restorePipOriginTab === 'function') {
                        window.BravePiPNavigator.restorePipOriginTab();
                    }
                } catch (e) {
                    console.warn('[BravePiPDebug] restorePipOriginTab bridge failed', e);
                }
            }, 500);
        });
    })();
)";

// Resume the watch position across a PiP-exit reload.
//
// When the user returns to the watch tab via the media notification, the page
// can reload from scratch (the backgrounded ChromeTabbedActivity gets recreated
// under memory pressure and the tab is restored from its serialized state, or
// YouTube's SPA re-renders the player). The renderer playing PiP audio is alive,
// but the tab the user lands on is a fresh load that starts at 0:00.
//
// We can't reliably stop that reload, so make it seamless: persist the current
// time to localStorage (which survives same-origin reloads) while playing, and
// seek back to it on the next watch-page load. Scoped to an *immediate* reload
// via a short freshness window so a normal revisit later is unaffected; the
// saved entry is consumed (removed) once applied.
constexpr char16_t kYoutubePipResumePosition[] =
    uR"(
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
        window.addEventListener('yt-navigate-finish', restorePosition);
    })();
)";

// First-run intro tutorial on the m.youtube.com home feed.
// Picks the first long-form video tile (≥ 60s, not Shorts), scrolls it into
// view, and dims the rest of the page with a gold-ringed cutout around the
// tile. Tap inside the cutout = clicks the tile (navigates to /watch, where
// the existing PiP coach mark fires). Tap outside dismisses. localStorage
// gate makes this strictly one-shot per origin.
constexpr char16_t kYoutubeHomeIntroTutorial[] =
    uR"(
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

        // Bumped to v2 so any stale flag from earlier failed attempts is
        // bypassed and the tutorial gets a fresh chance to render.
        const STORAGE_KEY    = 'brave_yt_home_tutorial_shown_v2';
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

        function isYouTubeHome() {
            const host = window.location.hostname;
            const path = window.location.pathname;
            return /(?:^|\.)youtube\.com$/.test(host) &&
                   (path === '/' || path === '');
        }

        function alreadyShown() {
            try { return localStorage.getItem(STORAGE_KEY) === '1'; }
            catch (e) { return false; }
        }
        function markShown() {
            try { localStorage.setItem(STORAGE_KEY, '1'); } catch (e) {}
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
            // document while these rAFs are still pending. The profile pref
            // (native side) handles the cross-document/cross-visit one-shot;
            // this flag handles the in-document race.
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
            spotlightTile(tile);
            return true;
        }

        const start = Date.now();
        function poll() {
            if (alreadyShown() || !isYouTubeHome()) return;
            if (attempt()) return;
            if (Date.now() - start > MAX_WAIT_MS) {
                log('poll timed out without finding a tile');
                return;
            }
            setTimeout(poll, POLL_MS);
        }

        log('arming poll');
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', poll);
        } else {
            poll();
        }

        window.addEventListener('yt-navigate-finish', () => {
            attempted = false;
            setTimeout(poll, 200);
        });
    })();
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
  ExecuteJavaScriptIfYouTube(kYoutubeBackgroundPlayback2);
  ExecuteJavaScriptIfYouTube(kYoutubeDisableHomeAutoplay);
  
  base::SequencedTaskRunner::GetCurrentDefault()->PostDelayedTask(
      FROM_HERE,
      base::BindOnce(&YouTubeScriptInjectorTabHelper::ExecuteJavaScriptIfYouTube,
                     weak_factory_.GetWeakPtr(), kYoutubePIP),
      base::Milliseconds(100));
      
  base::SequencedTaskRunner::GetCurrentDefault()->PostDelayedTask(
      FROM_HERE,
      base::BindOnce(&YouTubeScriptInjectorTabHelper::ExecuteJavaScriptIfYouTube,
                     weak_factory_.GetWeakPtr(), kYoutubePipButton),
      base::Milliseconds(200));

  ExecuteJavaScriptIfYouTube(kYoutubeInAppPIP);

  base::SequencedTaskRunner::GetCurrentDefault()->PostDelayedTask(
      FROM_HERE,
      base::BindOnce(&YouTubeScriptInjectorTabHelper::ExecuteJavaScriptIfYouTube,
                     weak_factory_.GetWeakPtr(), kYoutubePipNavigationFix),
      base::Milliseconds(500));

  // Persist/restore the watch position so a PiP-exit reload resumes where the
  // user left off instead of starting from 0:00. Injected immediately (so the
  // restore runs as early as possible on a reload) and the script self-guards
  // against duplicate execution.
  ExecuteJavaScriptIfYouTube(kYoutubePipResumePosition);

  // First-run YouTube home PiP tutorial — strictly one-shot per profile.
  // The gate lives in the profile (not page localStorage) so a www.->m.
  // redirect, a consent-page bounce, an SPA re-render, or a repeat visit can't
  // show it twice. localStorage was per-origin and the tutorial matches both
  // www.youtube.com and m.youtube.com, which is what let it fire once per host.
  // Scoped to the m.youtube.com home (the app's canonical YouTube entry) so a
  // transient www. document can never consume the one-shot before m. has a
  // chance to render it.
  {
    const GURL& committed_url = contents->GetLastCommittedURL();
    const auto path = committed_url.path_piece();
    const bool is_mobile_home =
        base::EqualsCaseInsensitiveASCII(committed_url.host(),
                                         "m.youtube.com") &&
        (path == "/" || path.empty());
    PrefService* prefs =
        static_cast<Profile*>(contents->GetBrowserContext())->GetPrefs();
    if (is_mobile_home && !prefs->GetBoolean(kYoutubeHomeTutorialShown)) {
      prefs->SetBoolean(kYoutubeHomeTutorialShown, true);
      base::SequencedTaskRunner::GetCurrentDefault()->PostDelayedTask(
          FROM_HERE,
          base::BindOnce(
              &YouTubeScriptInjectorTabHelper::ExecuteJavaScriptIfYouTube,
              weak_factory_.GetWeakPtr(), kYoutubeHomeIntroTutorial),
          base::Milliseconds(300));
    }
  }

  // Inject the comment-hiding script immediately and at delayed intervals to
  // handle both fast and slow page loads. The script guards against duplicate
  // execution via window.__braveCommentHiderActive.
  contents->GetPrimaryMainFrame()->ExecuteJavaScript(
      kRemoveYoutubeComment, base::NullCallback());
  for (int delay_ms : {500, 1500, 3000}) {
    base::SequencedTaskRunner::GetCurrentDefault()->PostDelayedTask(
        FROM_HERE,
        base::BindOnce(&YouTubeScriptInjectorTabHelper::ExecuteJavaScriptIfYouTube,
                       weak_factory_.GetWeakPtr(), kRemoveYoutubeComment),
        base::Milliseconds(delay_ms));
  }

  if (IsBackgroundVideoPlaybackEnabled(contents)) {
    ExecuteJavaScriptIfYouTube(kYoutubeBackgroundPlayback);
  }
  if (base::FeatureList::IsEnabled(
          ::preferences::features::kBravePictureInPictureForYouTubeVideos)) {
    ExecuteJavaScriptIfYouTube(kYoutubePictureInPictureSupport);
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

void YouTubeScriptInjectorTabHelper::TriggerYouTubePiP() {
  content::RenderFrameHost* rfh = web_contents()->GetPrimaryMainFrame();
  if (!rfh || !rfh->IsRenderFrameLive()) {
    LOG(ERROR) << "BravePiPDebug TriggerYouTubePiP skip rfh=" << rfh
               << " url=" << web_contents()->GetLastCommittedURL();
    return;
  }
  LOG(ERROR) << "BravePiPDebug TriggerYouTubePiP executing JS rfh=" << rfh
             << " url=" << web_contents()->GetLastCommittedURL();
  EnsureBound(rfh);
  script_injector_remote_->RequestAsyncExecuteScript(
      ISOLATED_WORLD_ID_BRAVE_INTERNAL, kYoutubePipTrigger,
      blink::mojom::UserActivationOption::kActivate,
      blink::mojom::PromiseResultOption::kAwait, base::DoNothing());
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

void YouTubeScriptInjectorTabHelper::ExecuteJavaScriptIfYouTube(
    const char16_t* script) {
  content::WebContents* contents = web_contents();
  if (!script || !contents || contents->IsBeingDestroyed() ||
      !IsYouTubeDomain(contents->GetLastCommittedURL())) {
    return;
  }

  content::RenderFrameHost* rfh = contents->GetPrimaryMainFrame();
  if (!rfh || !rfh->IsRenderFrameLive()) {
    return;
  }

  rfh->ExecuteJavaScript(script, base::NullCallback());
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
