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
      // ---- 1. PiP flag overrides for YouTube mobile ----
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

      // ---- 2. Floating PiP button for video ----
      const buttonElement = document.createElement('button');
      const originalStyles = {
        backgroundColor: '#39B1F6',
        transform: 'scale(1)',
        boxShadow: '0 4px 8px rgba(0, 0, 0, 0.2)',
        outline: '2px solid transparent',
        outlineOffset: '2px'
      };

      const hoverStyles = {
        backgroundColor: '#2F90D5', // Slightly darker blue
        transform: 'scale(1.1)',
        boxShadow: '0 6px 16px rgba(0, 0, 0, 0.3)'
      };

      const activeStyles = {
        transform: 'scale(0.95)',
        boxShadow: '0 2px 8px rgba(0, 0, 0, 0.2)',
        backgroundColor: '#2A82BF' // Even darker blue
      };

      const focusStyles = {
        outline: '2px solid #0056b3' // Or a more contrasting focus ring color
      };

      // Base styles (including transitions)
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

      // Hover effects
      buttonElement.addEventListener('mouseenter', () => {
        buttonElement.style.backgroundColor = hoverStyles.backgroundColor;
        buttonElement.style.transform = hoverStyles.transform;
        buttonElement.style.boxShadow = hoverStyles.boxShadow;
      });

      buttonElement.addEventListener('mouseleave', () => {
        // Revert to original styles unless it's also focused and active
        if (document.activeElement !== buttonElement) { // Check if not focused
            buttonElement.style.backgroundColor = originalStyles.backgroundColor;
            buttonElement.style.transform = originalStyles.transform;
            buttonElement.style.boxShadow = originalStyles.boxShadow;
        } else { // If it's focused, keep focus styles and potentially hover if mouse is still over
            buttonElement.style.backgroundColor = hoverStyles.backgroundColor; // Keep hover BG if mouse still over
            buttonElement.style.transform = hoverStyles.transform; // Keep hover transform
            buttonElement.style.boxShadow = hoverStyles.boxShadow; // Keep hover shadow
            // Focus outline is handled by focus/blur
        }
      });

      // Active (click) effects
      buttonElement.addEventListener('mousedown', () => {
        buttonElement.style.transform = activeStyles.transform;
        buttonElement.style.boxShadow = activeStyles.boxShadow;
        buttonElement.style.backgroundColor = activeStyles.backgroundColor;
      });

      buttonElement.addEventListener('mouseup', () => {
        // Revert to hover styles if mouse is still over it, otherwise original
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

      // Focus effects (for accessibility / keyboard navigation)
      buttonElement.addEventListener('focus', () => {
        buttonElement.style.outline = focusStyles.outline;
        // Optional: Apply hover-like visual changes on focus too for better visibility
        buttonElement.style.backgroundColor = hoverStyles.backgroundColor;
        buttonElement.style.transform = hoverStyles.transform;
        buttonElement.style.boxShadow = hoverStyles.boxShadow;
      });

      buttonElement.addEventListener('blur', () => {
        buttonElement.style.outline = originalStyles.outline;
        // Revert other styles if not hovered
        if (!buttonElement.matches(':hover')) {
            buttonElement.style.backgroundColor = originalStyles.backgroundColor;
            buttonElement.style.transform = originalStyles.transform;
            buttonElement.style.boxShadow = originalStyles.boxShadow;
        }
      });


      // Click action
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
      });
      observer.observe(document.documentElement, { subtree: true, childList: true });

      // ---- 3. Visibility and focus hack + auto key pressing ----
      'use strict';
      const IS_YOUTUBE = /(?:^|.+\\.)youtube\\.com/.test(window.location.hostname) || /(?:^|.+\\.)youtube-nocookie\\.com/.test(window.location.hostname);
      const IS_MOBILE_YOUTUBE = window.location.hostname === 'm.youtube.com';
      const IS_DESKTOP_YOUTUBE = IS_YOUTUBE && !IS_MOBILE_YOUTUBE;
      const IS_VIMEO = /(?:^|.+\\.)vimeo\\.com/.test(window.location.hostname);
      const IS_ANDROID = window.navigator.userAgent.indexOf('Android') > -1;

      const videoElement = document.querySelector('video');
      if (videoElement) {
        videoElement.removeAttribute('disablePictureInPicture');
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

      if (IS_YOUTUBE) {
        loop(pressKey, 60000, 10000);
      }

      function pressKey() {
        const key = 18; // Alt key
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

  content::RenderFrameHost::AllowInjectingJavaScript();

  return true;
}
}  // namespace

BackgroundVideoPlaybackTabHelper::BackgroundVideoPlaybackTabHelper(
    content::WebContents* contents)
    : WebContentsObserver(contents),
      content::WebContentsUserData<BackgroundVideoPlaybackTabHelper>(
          *contents) {}

BackgroundVideoPlaybackTabHelper::~BackgroundVideoPlaybackTabHelper() {}

void BackgroundVideoPlaybackTabHelper::DidFinishNavigation(
    content::NavigationHandle* navigation_handle) {
  // Filter only YT domain here
  if (!IsYouTubeDomain(web_contents()->GetLastCommittedURL())) {
    return;
  }
  if (IsBackgroundVideoPlaybackEnabled(web_contents())) {
    web_contents()->GetPrimaryMainFrame()->ExecuteJavaScript(
        k_youtube_background_playback_script, base::NullCallback());
  }
}

WEB_CONTENTS_USER_DATA_KEY_IMPL(BackgroundVideoPlaybackTabHelper);
