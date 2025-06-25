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
const char16_t k_youtube_background_playback_script[] =
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

            function startPIPForNewVideo(videoElement, videoId) {
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
                                })
                                .catch(err => {
                                    console.warn('Failed to start PIP for new video:', err);
                                });
                        }
                    }, 300);
                }
            }

            function handleVideoPlay(videoElement) {
                const currentVideoId = getCurrentVideoId();
                
                console.log('Video play detected:', currentVideoId);
                
                const pipStatus = getPIPStatus();
                
                if (currentVideoId && pipStatus.isActive && pipStatus.videoId !== currentVideoId) {
                    console.log('New video playing, PIP detected for different video.');
                    
                    if (!isPIPActive()) {
                        console.log('This is a new tab, attempting PIP transition');
                        signalPIPTransition(currentVideoId);
                        
                        setTimeout(() => {
                            const playbackState = getPlaybackState();
                            if (playbackState) {
                                applyPlaybackState(videoElement, playbackState);
                            }
                            startPIPForNewVideo(videoElement, currentVideoId);
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
                });

                document.addEventListener('leavepictureinpicture', (event) => {
                    currentPIPVideoId = null;
                    lastPlayingVideoElement = null;
                    isOriginalPIPTab = false;
                    setPIPStatus(null, false);
                    console.log('PIP exited');
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

                setInterval(checkForPIPTransitionSignal, 1000);

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


      // ---- 1. PiP flag overrides for YouTube mobile ----
    let userPaused = false;
    let lastUserAction = 0;
    let playerFeaturesInitialized = false;

    function modifyYtcfgFlags() {
        const config = window.ytcfg?.get("WEB_PLAYER_CONTEXT_CONFIGS")?.WEB_PLAYER_CONTEXT_CONFIG_ID_MWEB_WATCH;
        if (config && typeof config.serializedExperimentFlags === 'string') {
        let flags = config.serializedExperimentFlags;
        flags = flags
            .replace("html5_picture_in_picture_blocking_ontimeupdate=true", "html5_picture_in_picture_blocking_ontimeupdate=false")
            .replace("html5_picture_in_picture_blocking_onresize=true", "html5_picture_in_picture_blocking_onresize=false")
            .replace("html5_picture_in_picture_blocking_document_fullscreen=true", "html5_picture_in_picture_blocking_document_fullscreen=false")
            .replace("html5_picture_in_picture_blocking_standard_api=true", "html5_picture_in_picture_blocking_standard_api=false")
            .replace("html5_picture_in_picture_logging_onresize=true", "html5_picture_in_picture_logging_onresize=false");
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

    function setupBackgroundPlayback() {
        const visibilityProps = ['hidden', 'webkitHidden', 'mozHidden', 'msHidden'];
        const visibilityStates = ['visibilityState', 'webkitVisibilityState', 'mozVisibilityState', 'msVisibilityState'];
        
        visibilityProps.forEach(prop => {
        if (prop in document) {
            Object.defineProperty(document, prop, {
            value: false,
            writable: false,
            configurable: false
            });
        }
        });
        
        visibilityStates.forEach(state => {
        if (state in document) {
            Object.defineProperty(document, state, {
            value: 'visible',
            writable: false,
            configurable: false
            });
        }
        });
        
        Object.defineProperty(document, 'hasFocus', {
        value: () => true,
        writable: false,
        configurable: false
        });
        
        const eventsToBlock = [
        'visibilitychange', 'webkitvisibilitychange', 'mozvisibilitychange', 'msvisibilitychange',
        'blur', 'focus', 'focusin', 'focusout', 'pagehide', 'pageshow'
        ];
        
        eventsToBlock.forEach(eventType => {
        document.addEventListener(eventType, (e) => {
            e.stopImmediatePropagation();
            e.preventDefault();
        }, true);
        
        window.addEventListener(eventType, (e) => {
            e.stopImmediatePropagation();
            e.preventDefault();
        }, true);
        });
    }

    function setupMediaSession() {
        if ('mediaSession' in navigator) {
        navigator.mediaSession.metadata = new MediaMetadata({
            title: document.title || 'YouTube Video',
            artist: 'YouTube',
            artwork: [
            { src: '/favicon.ico', sizes: '96x96', type: 'image/x-icon' }
            ]
        });
        
        navigator.mediaSession.setActionHandler('play', () => {
            const video = document.querySelector('video');
            if (video) {
            userPaused = false;
            lastUserAction = Date.now();
            video.play();
            }
        });
        
        navigator.mediaSession.setActionHandler('pause', () => {
            const video = document.querySelector('video');
            if (video) {
            userPaused = true;
            lastUserAction = Date.now();
            video.pause();
            }
        });
        
        navigator.mediaSession.setActionHandler('seekbackward', () => {
            const video = document.querySelector('video');
            if (video) video.currentTime = Math.max(0, video.currentTime - 10);
        });
        
        navigator.mediaSession.setActionHandler('seekforward', () => {
            const video = document.querySelector('video');
            if (video) video.currentTime = Math.min(video.duration, video.currentTime + 10);
        });
        }
    }

    function isUserAction() {
        return Date.now() - lastUserAction < 1000;
    }

    function setupVideoElement() {
        const video = document.querySelector('video');
        if (!video) return;
        
        video.removeAttribute('disablePictureInPicture');
        
        const originalPause = video.pause;
        video.pause = function() {
        const stack = new Error().stack;
        if (stack.includes('visibilitychange') || stack.includes('blur') || stack.includes('focus')) {
            return;
        }
        userPaused = true;
        lastUserAction = Date.now();
        return originalPause.call(this);
        };

        const originalPlay = video.play;
        video.play = function() {
        userPaused = false;
        lastUserAction = Date.now();
        return originalPlay.call(this);
        };
        
        video.addEventListener('pause', (e) => {
        if (!userPaused && !isUserAction()) {
            setTimeout(() => {
            if (video.paused && !video.ended && !userPaused) {
                video.play().catch(console.error);
            }
            }, 100);
        }
        });

        video.addEventListener('play', () => {
        if ('mediaSession' in navigator) {
            navigator.mediaSession.playbackState = 'playing';
        }
        });
        
        video.addEventListener('pause', () => {
        if ('mediaSession' in navigator) {
            navigator.mediaSession.playbackState = 'paused';
        }
        });

        document.addEventListener('click', (e) => {
        const target = e.target;
        if (target.closest('.ytp-play-button') || 
            target.closest('[data-title-no-tooltip="Play"]') || 
            target.closest('[data-title-no-tooltip="Pause"]') ||
            target.closest('.player-controls-play-pause-replay-button') ||
            target.matches('[aria-label*="Play"]') ||
            target.matches('[aria-label*="Pause"]')) {
            lastUserAction = Date.now();
            userPaused = target.closest('[data-title-no-tooltip="Pause"]') || target.matches('[aria-label*="Pause"]') ? false : true;
        }
        }, true);

        document.addEventListener('keydown', (e) => {
        if (e.code === 'Space' || e.key === 'k' || e.key === 'K') {
            lastUserAction = Date.now();
            userPaused = !userPaused;
        }
        }, true);
    }

    if (document._addEventListener === undefined) {
        document._addEventListener = document.addEventListener;
        document.addEventListener = function(a, b, c) {
        if (a != 'visibilitychange') {
            document._addEventListener(a, b, c);
        }
        };
    }

    const buttonElement = document.createElement('button');
    const originalStyles = {
        backgroundColor: '#39B1F6',
        transform: 'scale(1)',
        boxShadow: '0 4px 8px rgba(0, 0, 0, 0.2)',
        outline: '2px solid transparent',
        outlineOffset: '2px'
    };

    const hoverStyles = {
        backgroundColor: '#2F90D5',
        transform: 'scale(1.1)',
        boxShadow: '0 6px 16px rgba(0, 0, 0, 0.3)'
    };

    const activeStyles = {
        transform: 'scale(0.95)',
        boxShadow: '0 2px 8px rgba(0, 0, 0, 0.2)',
        backgroundColor: '#2A82BF'
    };

    const focusStyles = {
        outline: '2px solid #0056b3'
    };

    buttonElement.setAttribute('style', `
        position: fixed;
        bottom: 20px;
        right: 20px;
        z-index: 9999;
        width: 60px;
        height: 60px;
        border-radius: 50%;
        background-color: ${originalStyles.backgroundColor};
        border: none;
        box-shadow: ${originalStyles.boxShadow};
        background-image: url("https://raw.githubusercontent.com/phosphor-icons/core/refs/heads/main/assets/light/picture-in-picture-light.svg");
        background-repeat: no-repeat;
        background-position: center;
        background-size: 55%;
        cursor: pointer;
        transform: ${originalStyles.transform};
        outline: ${originalStyles.outline};
        outline-offset: ${originalStyles.outlineOffset};
        transition: transform 0.2s ease-in-out, box-shadow 0.2s ease-in-out, background-color 0.2s ease-in-out, outline 0.1s linear;
    `);

    buttonElement.setAttribute('aria-label', 'Enter Picture-in-Picture mode');
    buttonElement.setAttribute('title', 'Picture-in-Picture');

    buttonElement.addEventListener('mouseenter', () => {
        buttonElement.style.backgroundColor = hoverStyles.backgroundColor;
        buttonElement.style.transform = hoverStyles.transform;
        buttonElement.style.boxShadow = hoverStyles.boxShadow;
    });

    buttonElement.addEventListener('mouseleave', () => {
        if (document.activeElement !== buttonElement) {
        buttonElement.style.backgroundColor = originalStyles.backgroundColor;
        buttonElement.style.transform = originalStyles.transform;
        buttonElement.style.boxShadow = originalStyles.boxShadow;
        } else {
        buttonElement.style.backgroundColor = hoverStyles.backgroundColor;
        buttonElement.style.transform = hoverStyles.transform;
        buttonElement.style.boxShadow = hoverStyles.boxShadow;
        }
    });

    buttonElement.addEventListener('mousedown', () => {
        buttonElement.style.transform = activeStyles.transform;
        buttonElement.style.boxShadow = activeStyles.boxShadow;
        buttonElement.style.backgroundColor = activeStyles.backgroundColor;
    });

    buttonElement.addEventListener('mouseup', () => {
        if (buttonElement.matches(':hover')) {
        buttonElement.style.backgroundColor = hoverStyles.backgroundColor;
        buttonElement.style.transform = hoverStyles.transform;
        buttonElement.style.boxShadow = hoverStyles.boxShadow;
        } else {
        buttonElement.style.backgroundColor = originalStyles.backgroundColor;
        buttonElement.style.transform = originalStyles.transform;
        buttonElement.style.boxShadow = originalStyles.boxShadow;
        }
    });

    buttonElement.addEventListener('focus', () => {
        buttonElement.style.outline = focusStyles.outline;
        buttonElement.style.backgroundColor = hoverStyles.backgroundColor;
        buttonElement.style.transform = hoverStyles.transform;
        buttonElement.style.boxShadow = hoverStyles.boxShadow;
    });

    buttonElement.addEventListener('blur', () => {
        buttonElement.style.outline = originalStyles.outline;
        if (!buttonElement.matches(':hover')) {
        buttonElement.style.backgroundColor = originalStyles.backgroundColor;
        buttonElement.style.transform = originalStyles.transform;
        buttonElement.style.boxShadow = originalStyles.boxShadow;
        }
    });

    buttonElement.addEventListener('click', () => {
        const videoElement = document.querySelector('video');
        if (videoElement) {
        videoElement.removeAttribute('disablePictureInPicture');
        videoElement.requestPictureInPicture().catch(console.error);
        }
    });

    const observer = new MutationObserver(() => {
        const buttonContainerElement = document.querySelector('.mobile-topbar-header-content');
        if (window.location.pathname !== '/watch' || !buttonContainerElement || buttonContainerElement.contains(buttonElement)) return;
        buttonContainerElement.prepend(buttonElement);
        setupVideoElement();
    });
    observer.observe(document.documentElement, { subtree: true, childList: true });

    const IS_YOUTUBE = /(?:^|.+\.)youtube\.com/.test(window.location.hostname) || /(?:^|.+\.)youtube-nocookie\.com/.test(window.location.hostname);
    const IS_MOBILE_YOUTUBE = window.location.hostname === 'm.youtube.com';
    const IS_DESKTOP_YOUTUBE = IS_YOUTUBE && !IS_MOBILE_YOUTUBE;
    const IS_VIMEO = /(?:^|.+\.)vimeo\.com/.test(window.location.hostname);
    const IS_ANDROID = window.navigator.userAgent.indexOf('Android') > -1;

    const initialVideoElement = document.querySelector('video');
    if (initialVideoElement) {
        initialVideoElement.removeAttribute('disablePictureInPicture');
    }

    if (IS_ANDROID || !IS_DESKTOP_YOUTUBE) {
        Object.defineProperties(document, {
        hidden: { value: false },
        visibilityState: { value: 'visible' }
        });
    }

    window.addEventListener('visibilitychange', evt => evt.stopImmediatePropagation(), true);
    if (IS_VIMEO) {
        window.addEventListener('fullscreenchange', evt => evt.stopImmediatePropagation(), true);
    }

    function pressKey() {
        const key = 18;
        sendKeyEvent("keydown", key);
        sendKeyEvent("keyup", key);
    }

    function sendKeyEvent(type, key) {
        document.dispatchEvent(new KeyboardEvent(type, {
        bubbles: true,
        cancelable: true,
        keyCode: key,
        which: key
        }));
    }

    function loop(callback, delay, jitter) {
        const actualDelay = Math.max(delay + getRandomInt(-jitter / 2, jitter / 2), 0);
        setTimeout(() => {
        callback();
        loop(callback, delay, jitter);
        }, actualDelay);
    }

    function getRandomInt(min, max) {
        min = Math.ceil(min);
        max = Math.floor(max);
        return Math.floor(Math.random() * (max - min)) + min;
    }
    
    function initializePlayerDependentFeatures() {
        if (playerFeaturesInitialized) {
            const currentVideo = document.querySelector('video');
            if (currentVideo && currentVideo.dataset.playerFeaturesSet === "true") {
                return; // Already set up on this specific video
            } else if (!currentVideo) {
                playerFeaturesInitialized = false; // Video gone, reset for next one
                return;
            }
            playerFeaturesInitialized = false;
        }

        const video = document.querySelector('video');
        if (video) {
            if (video.dataset.playerFeaturesSet === "true") return; // Already did this one

            setupVideoElement();
            setupMediaSession();
            video.dataset.playerFeaturesSet = "true";
            playerFeaturesInitialized = true;
        }
    }

    setupBackgroundPlayback(); // This can run early

    // Observer for the PiP button and its container
    const buttonObserver = new MutationObserver(() => {
        const buttonContainerElement = document.querySelector('.mobile-topbar-header-content');
        const onWatchPage = window.location.pathname === '/watch';

        if (onWatchPage && buttonContainerElement && !buttonContainerElement.contains(buttonElement)) {
            buttonContainerElement.prepend(buttonElement);
        }
        
        // When button container is ready (or any relevant mutation occurs on a watch page),
        // ensure player features are initialized.
        // Also, if we navigate off a watch page, reset the flag.
        if (onWatchPage) {
            initializePlayerDependentFeatures();
        } else {
            if(playerFeaturesInitialized) {
                const oldVideo = document.querySelector('video[data-player-features-set="true"]');
                if(oldVideo) delete oldVideo.dataset.playerFeaturesSet;
            }
            playerFeaturesInitialized = false; // Reset if not on watch page
        }
    });
    buttonObserver.observe(document.documentElement, { subtree: true, childList: true });

    // Attempt to initialize features early if document is already loaded or when it loads
    if (document.readyState === 'complete' || document.readyState === 'interactive') {
        initializePlayerDependentFeatures();
    } else {
        document.addEventListener('DOMContentLoaded', initializePlayerDependentFeatures, { once: true });
    }

    // Fallback observer specifically for the video element if it appears later
    // and hasn't been caught by DOMContentLoaded or the buttonObserver logic yet.
    let earlyVideoObserver = null;
    if (!playerFeaturesInitialized) {
        earlyVideoObserver = new MutationObserver((mutations, obs) => {
            if (document.querySelector('video')) {
                initializePlayerDependentFeatures();
                if (playerFeaturesInitialized) {
                    obs.disconnect(); // Successfully initialized
                }
            }
        });
        earlyVideoObserver.observe(document.documentElement, { childList: true, subtree: true });
        // Clean up this observer after a timeout if it didn't find a video,
        // to prevent it from running indefinitely on non-video pages.
        setTimeout(() => {
            if (earlyVideoObserver && !playerFeaturesInitialized) {
                earlyVideoObserver.disconnect();
            }
        }, 15000); // 15 seconds timeout
    }

    if (IS_YOUTUBE) {
        loop(pressKey, 60000, 10000);
    }
    })();
    )";

    // u"(function() { "
    //   u"const buttonElement = document.createElement('button');"
    //   u"buttonElement.setAttribute('style', `    -webkit-mask: url(\"https://raw.githubusercontent.com/phosphor-icons/core/refs/heads/main/assets/light/picture-in-picture-light.svg\") right center / auto 75% no-repeat;    background-color: white;    align-self: stretch;    flex: 1;`);"
    //   u"buttonElement.addEventListener('click', () => {"
    //   u"    const videoElement = document.querySelector('video');"
    //   u"    videoElement.removeAttribute('disablePictureInPicture');"
    //   u"    videoElement.requestPictureInPicture();"
    //   u"});"
    //   u"const observer = new MutationObserver(() => {"
    //   u"    const buttonContainerElement = document.querySelector('.mobile-topbar-header-content');"
    //   u"    if(window.location.pathname !== '/watch' || !buttonContainerElement || buttonContainerElement.contains(buttonElement)) return;"
    //   u"    buttonContainerElement.prepend(buttonElement);"
    //   u"});"
    //   u"observer.observe(document.documentElement, { subtree: true, childList: true });"
    // "}());";
    // uR"(
    // (function() {
    //     if (document._addEventListener === undefined) {
    //         document._addEventListener = document.addEventListener;
    //         document.addEventListener = function(a,b,c) {
    //             if(a != 'visibilitychange') {
    //                 document._addEventListener(a,b,c);
    //             }
    //         };
    //     }
    // }());
    // // Function to modify the flags if the target object exists.
    // function modifyYtcfgFlags() {
    //   if (!window.ytcfg) {
    //     return;
    //   }
    //   const config = window.ytcfg.get("WEB_PLAYER_CONTEXT_CONFIGS")?.WEB_PLAYER_CONTEXT_CONFIG_ID_MWEB_WATCH
    //   if (config && config.serializedExperimentFlags) {
    //     let flags = config.serializedExperimentFlags;
    //     // Replace target flags.
    //     flags = flags
    //       .replace("html5_picture_in_picture_blocking_ontimeupdate=true", "html5_picture_in_picture_blocking_ontimeupdate=false")
    //       .replace("html5_picture_in_picture_blocking_onresize=true", "html5_picture_in_picture_blocking_onresize=false")
    //       .replace("html5_picture_in_picture_blocking_document_fullscreen=true", "html5_picture_in_picture_blocking_document_fullscreen=false")
    //       .replace("html5_picture_in_picture_blocking_standard_api=true", "html5_picture_in_picture_blocking_standard_api=false")
    //       .replace("html5_picture_in_picture_logging_onresize=true", "html5_picture_in_picture_logging_onresize=false");
    //     // Assign updated flags back to config.
    //     config.serializedExperimentFlags = flags;
    //     if (observer) {
    //       observer.disconnect();
    //     }
    //   }
    // }
    // const observer = new MutationObserver((mutations) => {
    //   for (const mutation of mutations) {
    //     if (mutation.type === "childList" && mutation.addedNodes.length > 0) {
    //       mutation.addedNodes.forEach((node) => {
    //         if (node.tagName === "SCRIPT") {
    //           // Check and modify flags when a new script is added.
    //           modifyYtcfgFlags();
    //         }
    //       });
    //     }
    //   }
    // });
    // observer.observe(document.documentElement, { childList: true, subtree: true });
    // )";
    // u"(function() {"
    // u"  const configModificationScript = document.createElement('script');"
    // u"  configModificationScript.textContent = `"
    // u"    function modifyYtcfgFlags() {"
    // u"      if (!window.ytcfg) {"
    // u"          return;"
    // u"      }"
    // u"      const config = window.ytcfg.get(\"WEB_PLAYER_CONTEXT_CONFIGS\")?.WEB_PLAYER_CONTEXT_CONFIG_ID_MWEB_WATCH"
    // u"      if (config && config.serializedExperimentFlags) {"
    // u"          let flags = config.serializedExperimentFlags;"
    // u"          flags = flags"
    // u"              .replace(\"html5_picture_in_picture_blocking_ontimeupdate=true\", \"html5_picture_in_picture_blocking_ontimeupdate=false\")"
    // u"              .replace(\"html5_picture_in_picture_blocking_onresize=true\", \"html5_picture_in_picture_blocking_onresize=false\")"
    // u"              .replace(\"html5_picture_in_picture_blocking_document_fullscreen=true\", \"html5_picture_in_picture_blocking_document_fullscreen=false\")"
    // u"              .replace(\"html5_picture_in_picture_blocking_standard_api=true\", \"html5_picture_in_picture_blocking_standard_api=false\")"
    // u"              .replace(\"html5_picture_in_picture_logging_onresize=true\", \"html5_picture_in_picture_logging_onresize=false\");"
    // u"          // Assign updated flags back to the config"
    // u"          config.serializedExperimentFlags = flags;"
    // u"          if (configModificationObserver) {"
    // u"              configModificationObserver.disconnect();"
    // u"          }"
    // u"      }"
    // u"    }"
    // u"    // MutationObserver to watch for new <script> elements"
    // u"    const configModificationObserver = new MutationObserver((mutations) => {"
    // u"        for (const mutation of mutations) {"
    // u"            if (mutation.type === \"childList\" && mutation.addedNodes.length > 0) {"
    // u"                mutation.addedNodes.forEach((node) => {"
    // u"                    if (node.tagName === \"SCRIPT\") {"
    // u"                        // Check and modify flags when a new script is added"
    // u"                        modifyYtcfgFlags();"
    // u"                    }"
    // u"                });"
    // u"            }"
    // u"        }"
    // u"    });"
    // u"    configModificationObserver.observe(document.documentElement, { childList: true, subtree: true });"
    // u"  `;"
    // u"  document.head.appendChild(configModificationScript);"
    // u"  configModificationScript.remove();"
    // u""
    // u"  // Script 2: Adds a Picture-in-Picture button to the mobile YouTube interface"
    // u"  const buttonElement = document.createElement('button');"
    // u"  buttonElement.setAttribute('style', `"
    // u"      -webkit-mask: url(\"https://raw.githubusercontent.com/phosphor-icons/core/refs/heads/main/assets/light/picture-in-picture-light.svg\") right center / auto 75% no-repeat;"
    // u"      background-color: white;"
    // u"      align-self: stretch;"
    // u"      flex: 1;"
    // u"  `);"
    // u"  buttonElement.addEventListener('click', () => {"
    // u"      const videoElement = document.querySelector('video');"
    // u"      videoElement.removeAttribute('disablePictureInPicture');"
    // u"      videoElement.requestPictureInPicture();"
    // u"  });"
    // u"  const buttonObserver = new MutationObserver(() => {"
    // u"      const buttonContainerElement = document.querySelector('.mobile-topbar-header-content');"
    // u"      // Check if the button container exists and does NOT contain the button"
    // u"      if(window.location.pathname === '/watch' && buttonContainerElement && !buttonContainerElement.contains(buttonElement)) {"
    // u"          buttonContainerElement.prepend(buttonElement);"
    // u"      }"
    // u"  });"
    // u"  buttonObserver.observe(document.documentElement, { subtree: true, childList: true });"
    // u""
    // u"  // Initial check in case the elements are already present on page load"
    // u"  const initialButtonContainerElement = document.querySelector('.mobile-topbar-header-content');"
    // u"  if (window.location.pathname === '/watch' && initialButtonContainerElement && !initialButtonContainerElement.contains(buttonElement)) {"
    // u"      initialButtonContainerElement.prepend(buttonElement);"
    // u"  }"
    // u"})();";
    
    // u"(function() { "
    //   u"const buttonElement = document.createElement('button');"
    //   u"buttonElement.setAttribute('style', `    -webkit-mask: url(\"https://raw.githubusercontent.com/phosphor-icons/core/refs/heads/main/assets/light/picture-in-picture-light.svg\") right center / auto 75% no-repeat;    background-color: white;    align-self: stretch;    flex: 1;`);"
    //   u"buttonElement.addEventListener('click', () => {"
    //   u"    const videoElement = document.querySelector('video');"
    //   u"    videoElement.removeAttribute('disablePictureInPicture');"
    //   u"    videoElement.requestPictureInPicture();"
    //   u"});"
    //   u"const observer = new MutationObserver(() => {"
    //   u"    const buttonContainerElement = document.querySelector('.mobile-topbar-header-content');"
    //   u"    if(window.location.pathname !== '/watch' || !buttonContainerElement || buttonContainerElement.contains(buttonElement)) return;"
    //   u"    buttonContainerElement.prepend(buttonElement);"
    //   u"});"
    //   u"observer.observe(document.documentElement, { subtree: true, childList: true });"
    // "}());";

    // u"(function() {"
    // "    function enablePiP() {"
    // "        let video = document.querySelector('video');"
    // "        if (video && document.pictureInPictureEnabled && !document.pictureInPictureElement) {"
    // "            video.play();"  
    // "            video.requestFullscreen();"
    // "            video.requestPictureInPicture().catch(err => console.log('PiP Error:', err));"
    // "        }"
    // "    }"
    // "    enablePiP();"
    // "    document.addEventListener('visibilitychange', enablePiP);"
    // "}());";
    // u"(function() {"
    // "    if (document._addEventListener === undefined) {"
    // "        document._addEventListener = document.addEventListener;"
    // "        document.addEventListener = function(a,b,c) {"
    // "            if(a != 'visibilitychange') {"
    // "                document._addEventListener(a,b,c);"
    // "            }"
    // "        };"
    // "    }"
    // "}());";

bool IsYouTubeDomain(const GURL& url) {
  if (net::registry_controlled_domains::SameDomainOrHost(
          url, GURL("https://www.youtube.com"),
          net::registry_controlled_domains::INCLUDE_PRIVATE_REGISTRIES)) {
    return true;
  }

  return false;
}

bool IsBackgroundVideoPlaybackEnabled(content::WebContents* contents) {
  // PrefService* prefs =
  //     static_cast<Profile*>(contents->GetBrowserContext())->GetPrefs();

  // if (!base::FeatureList::IsEnabled(
  //         ::preferences::features::kBraveBackgroundVideoPlayback) &&
  //     !prefs->GetBoolean(kBackgroundVideoPlaybackEnabled))
  //   return false;

  // content::RenderFrameHost::AllowInjectingJavaScript();

  return true;
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
  // Filter only YT domain here
  if (!IsYouTubeDomain(contents->GetLastCommittedURL())) {
    return;
  }
  content::RenderFrameHost::AllowInjectingJavaScript();
  if (IsBackgroundVideoPlaybackEnabled(contents)) {
    contents->GetPrimaryMainFrame()->ExecuteJavaScript(
        k_youtube_background_playback_script, base::NullCallback());
  }
}

WEB_CONTENTS_USER_DATA_KEY_IMPL(BackgroundVideoPlaybackTabHelper);
