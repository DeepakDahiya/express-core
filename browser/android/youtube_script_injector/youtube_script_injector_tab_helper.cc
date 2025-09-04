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
        function setupPIPProtection() {
            let currentPIPVideoId = null;
            let pipReplacementEnabled = true;
            let lastPlayingVideoElement = null;
            let isOriginalPIPTab = false;

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

                    // const videoElement = document.querySelector('video');
                    // if (videoElement) {
                    //     // Save the current playback state
                    //     localStorage.setItem('pip_video_id', videoElement.currentSrc);
                    //     localStorage.setItem('pip_playback_time', videoElement.currentTime);
                    // }
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

constexpr char16_t kYoutubeGlobalPipTrigger[] =
    uR"(
(function() {
    // --- PREVENTS SCRIPT FROM RUNNING MULTIPLE TIMES ---
    if (window.braveGlobalPipTriggerInitialized) return;
    window.braveGlobalPipTriggerInitialized = true;

    const BUTTON_CLASS_NAME = 'yt-pip-gold';
    const STYLE_ID = 'yt-pip-gold-styles';

    // --- CORE LOGIC: Calls the native bridge for cross-tab PiP ---
    function enterGlobalPipMode() {
        if (window.BravePipBridge && window.BravePipBridge.enterGlobalPipMode) {
            window.BBravePipBridge.enterGlobalPipMode();
        } else {
            console.error("Brave Global PiP Bridge is not available.");
        }
    }

    // --- INJECT STYLES: This function is idempotent (safe to call multiple times) ---
    function injectGoldenButtonStyles() {
        if (document.getElementById(STYLE_ID)) return;

        const css = `
            .${BUTTON_CLASS_NAME} {
                position: fixed;
                bottom: 20px; left: 20px;
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
            .${BUTTON_CLASS_NAME}:hover { transform: scale(1.10); box-shadow: 0 6px 16px rgba(0,0,0,.40); }
            .${BUTTON_CLASS_NAME}:active { transform: scale(0.95); }
            .${BUTTON_CLASS_NAME}:focus { outline: 2px solid #000; outline-offset: 2px; }
            .${BUTTON_CLASS_NAME}::before {
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
            @keyframes shine { 0% { left: -75%; } 100% { left: 125%; } }
            @keyframes scalePulse { 0%, 100% { transform: scale(1); } 50% { transform: scale(1.1); } }
        `;
        const styleTag = document.createElement('style');
        styleTag.id = STYLE_ID;
        styleTag.textContent = css;
        document.head.appendChild(styleTag);
    }

    // --- BUTTON CREATION: This is also idempotent ---
    function createOrGetButton() {
        let button = document.querySelector(`.${BUTTON_CLASS_NAME}`);
        if (!button) {
            button = document.createElement('button');
            button.className = BUTTON_CLASS_NAME;
            button.setAttribute('aria-label', 'Enter Picture-in-Picture mode');
            button.title = 'Picture-in-Picture';
            button.onclick = enterGlobalPipMode;
            document.body.appendChild(button);
        }
        return button;
    }

    // --- [!! NEW !!] THE RESILIENT RECONCILIATION LOOP ---
    function ensureButtonState() {
        const isOnWatchPage = window.location.pathname === '/watch';
        let button = document.querySelector(`.${BUTTON_CLASS_NAME}`);

        if (isOnWatchPage) {
            // We should be on a watch page. Make sure the button and styles exist.
            injectGoldenButtonStyles();
            createOrGetButton(); // This will create it if it's missing
        } else {
            // We are not on a watch page. If the button exists, remove it.
            if (button) {
                button.remove();
            }
        }
    }

    // --- START THE LOOP ---
    // Run it once immediately, then every 500ms to catch any changes.
    ensureButtonState();
    setInterval(ensureButtonState, 500);

})();
)";

constexpr char16_t kYoutubeTogglePlayback[] =
    uR"(
(function() {
    const video = document.querySelector('video.video-stream');
    if (video) {
        if (video.paused) {
            video.play();
        } else {
            video.pause();
        }
        return !video.paused; // Return the new playing state
    }
    return false;
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
      kYoutubeBackgroundPlayback2, base::NullCallback());
    
  contents->GetPrimaryMainFrame()->ExecuteJavaScript(
    kYoutubeGlobalPipTrigger, base::NullCallback());
  
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

  contents->GetPrimaryMainFrame()->ExecuteJavaScript(
    kYoutubeInAppPIP, base::NullCallback());

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

void YouTubeScriptInjectorTabHelper::StartGlobalPip(
    const base::android::JavaParamRef<jobject>& jsurface) {
  // This is the most complex part of the implementation.
  // It requires accessing Chromium's internal media player manager.
  // The exact API can change between Chromium versions.
  // This is a conceptual representation:
  
  // 1. Get the media player instance for the main frame.
  // content::MediaPlayer* player = web_contents()->GetMainFrame()->GetMediaPlayer();
  
  // 2. Tell the player to redirect its output to the new Surface.
  // if (player) {
  //   player->SetSurface(jsurface);
  // }
  
  // For now, we will log to show the connection is made.
  LOG(ERROR) << "C++: StartGlobalPip called. Media pipeline redirection would happen here.";
}

void YouTubeScriptInjectorTabHelper::StopGlobalPip() {
  // Tell the media player to stop redirecting to our surface and render
  // back to the web page's context.
  // content::MediaPlayer* player = web_contents()->GetMainFrame()->GetMediaPlayer();
  // if (player) {
  //   player->SetSurface(nullptr); // Passing null often reverts it.
  // }
  LOG(ERROR) << "C++: StopGlobalPip called. Media pipeline would be restored here.";
}

void YouTubeScriptInjectorTabHelper::TogglePipPlayback() {
  content::RenderFrameHost* rfh = web_contents()->GetPrimaryMainFrame();
  if (!rfh || !rfh->IsRenderFrameLive()) return;

  EnsureBound(rfh);
  script_injector_remote_->RequestAsyncExecuteScript(
      ISOLATED_WORLD_ID_BRAVE_INTERNAL, kYoutubeTogglePlayback,
      blink::mojom::UserActivationOption::kDoNotActivate,
      blink::mojom::PromiseResultOption::kAwait,
      base::BindOnce(&YouTubeScriptInjectorTabHelper::OnTogglePlaybackScriptComplete,
                     weak_factory_.GetWeakPtr()));
}

void YouTubeScriptInjectorTabHelper::OnTogglePlaybackScriptComplete(base::Value value) {
    if (value.is_bool()) {
        // Send the new playback state back up to Java to update the UI
        youtube_script_injector::SetPipPlaybackState(web_contents(), value.GetBool());
    }
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
