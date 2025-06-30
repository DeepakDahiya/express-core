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
        (function () {
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
                            } catch (e) { }
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

                                if (isPIPActive()) {
                                    sendPlaybackState();
                                    closePIP();
                                    localStorage.removeItem('pip_transition_signal');
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

                // --- MODIFIED FUNCTION ---
                // Added `previousPIPTabId` parameter to know which tab to close later.
                function startPIPForNewVideo(videoElement, videoId, previousPIPTabId) {
                    if (videoElement && typeof videoElement.requestPictureInPicture === 'function') {
                        setTimeout(() => {
                            if (!videoElement.paused) {
                                videoElement.requestPictureInPicture()
                                    .then(() => {
                                        currentPIPVideoId = videoId;
                                        lastPlayingVideoElement = videoElement;
                                        isOriginalPIPTab = true;
                                        setPIPStatus(videoId, true);
                                        console.log('PIP started for new video:', videoId);
                                        // After successfully starting PIP, tell the old tab to close.
                                        signalTabToClose(previousPIPTabId);
                                    })
                                    .catch(err => {
                                        console.warn('Failed to start PIP for new video:', err);
                                    });
                            }
                        }, 300);
                    }
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
                            console.log('This is a new tab, attempting PIP transition');
                            const previousPIPTabId = pipStatus.tabId; // Capture the old tab's ID
                            signalPIPTransition(currentVideoId);

                            setTimeout(() => {
                                const playbackState = getPlaybackState();
                                if (playbackState) {
                                    applyPlaybackState(videoElement, playbackState);
                                }
                                // Pass the old tab's ID to the function that starts the new PIP
                                startPIPForNewVideo(videoElement, currentVideoId, previousPIPTabId);
                            }, 600);
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

                        const videoElement = document.querySelector('video');
                        if (videoElement) {
                            // Save the current playback state
                            localStorage.setItem('pip_video_id', videoElement.currentSrc);
                            localStorage.setItem('pip_playback_time', videoElement.currentTime);
                        }
                    });

                    document.addEventListener('leavepictureinpicture', (event) => {
                        currentPIPVideoId = null;
                        lastPlayingVideoElement = null;
                        isOriginalPIPTab = false;
                        setPIPStatus(null, false);
                        console.log('PIP exited');

                        const videoElement = document.querySelector('video');
                        if (videoElement) {
                            // Restore the playback state if needed
                            const videoId = localStorage.getItem('pip_video_id');
                            const playbackTime = localStorage.getItem('pip_playback_time');
                            if (videoId && playbackTime) {
                                videoElement.currentTime = playbackTime;
                                videoElement.play();
                            }
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

                    // --- MODIFIED INTERVAL ---
                    // Now checks for both transition signals and close signals.
                    setInterval(() => {
                        checkForPIPTransitionSignal();
                        checkForCloseSignal(); // Add check for the close signal
                    }, 1000);

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
                        get: function () {
                            if (isPIPActive() || currentPIPVideoId) {
                                return false;
                            }
                            return originalHidden ? originalHidden.get.call(this) : false;
                        },
                        configurable: true
                    });

                    Object.defineProperty(document, 'visibilityState', {
                        get: function () {
                            if (isPIPActive() || currentPIPVideoId) {
                                return 'visible';
                            }
                            return originalVisibilityState ? originalVisibilityState.get.call(this) : 'visible';
                        },
                        configurable: true
                    });
                }


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

            if (!document.getElementById('yt-pip-gold-styles')) {
                const css = `
                .yt-pip-gold {
                    position: fixed;
                    bottom: 20px; right: 20px; z-index: 9999;
                    width: 60px; height: 60px; border-radius: 50%;
                    background: #D4AF37;
                    border: none; cursor: pointer; overflow: hidden;
                    box-shadow: 0 4px 12px rgba(0,0,0,.30);
                    background-image: url("https://raw.githubusercontent.com/DeepakDahiya/DeepakDahiya.github.io/refs/heads/master/youtube-icon.svg");
                    background-repeat: no-repeat;
                    background-position: center;
                    background-size: 55%;
                    transition: transform .2s, box-shadow .2s, filter .2s;
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
                .yt-pip-gold {
                    animation: scalePulse 2.4s ease-in-out infinite;
                }
            `;
                const styleTag = document.createElement('style');
                styleTag.id = 'yt-pip-gold-styles';
                styleTag.textContent = css;
                document.head.appendChild(styleTag);
            }


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
