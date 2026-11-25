// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import Foundation
import Web
import WebKit

/// Runs `ExpressYouTubeScript.js` on youtube.com and passes its messages on: YouTube pages
/// shown (`pageView`, with `path` and `url`), whether the page's video can go into picture in
/// picture (`pipAvailable`, with `available` and `url`), and picture in picture starting
/// (`pipEnter`) and ending (`pipLeave`, with `playing`: still playing means it was expanded,
/// paused means closed).
///
/// It also answers the home tutorial itself (`ExpressYouTubeHomeTutorial`): `homeTutorialState`
/// replies `{shown}` (true anywhere but m.youtube.com), and `homeTutorialShown` sets the flag.
class ExpressYouTubeScriptHandler: NSObject, TabContentScript {
  static let scriptName = "ExpressYouTubeScript"
  static let scriptId = UUID().uuidString
  static let messageHandlerName = "\(scriptName)_\(messageUUID)"
  static let scriptSandbox: WKContentWorld = .page
  static let userScript: WKUserScript? = {
    guard let script = loadUserScript(named: scriptName) else {
      return nil
    }
    return WKUserScript(
      source: secureScript(
        handlerNamesMap: ["$<message_handler>": messageHandlerName],
        securityToken: scriptId,
        script: script
      ),
      injectionTime: .atDocumentStart,
      forMainFrameOnly: true,
      in: scriptSandbox
    )
  }()

  private let onMessage: @MainActor (_ tab: any TabState, _ event: String, _ body: [String: Any]) -> Void

  init(onMessage: @escaping @MainActor (_ tab: any TabState, _ event: String, _ body: [String: Any]) -> Void) {
    self.onMessage = onMessage
    super.init()
  }

  func tab(
    _ tab: some TabState,
    receivedScriptMessage message: WKScriptMessage,
    replyHandler: @escaping (Any?, String?) -> Void
  ) {
    guard verifyMessage(message: message),
      let body = message.body as? [String: Any],
      let event = body["event"] as? String,
      let host = message.frameInfo.request.url?.host?.lowercased(),
      host == "youtube.com" || host.hasSuffix(".youtube.com")
    else {
      replyHandler(nil, nil)
      return
    }
    switch event {
    case "homeTutorialState":
      replyHandler(["shown": !ExpressYouTubeHomeTutorial.mayShow(onHost: host)], nil)
      return
    case "homeTutorialShown":
      ExpressYouTubeHomeTutorial.markShown()
    default:
      break
    }
    replyHandler(nil, nil)
    onMessage(tab, event, body)
  }
}
