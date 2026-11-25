// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import Foundation
import os.log

/// Like and reply notifications, after the Android app's `browser_express_push`: the device's
/// push token is kept on the signed-in account (`PUT /v1/user/push-token`, `platform: "ios"`),
/// and the server sends through Apple's push service.
///
/// A notification's custom keys match Android's data payload: `v`, `type` (`comment_like`,
/// `comment_reply`, `post_like`), `notificationId`, `collapseKey`, `commentId`,
/// `parentCommentId`, `parentType` (`page`, `youtube`, `post`), `url`, `videoId`, `postId`,
/// `postUsername`, `postContent`, `postAvatarUrl`, `postVideoUrl`, `actorUsername`,
/// `actorAvatarUrl`, `sentAt`.
public struct ExpressPushPayload: Equatable, Sendable {
  public let type: String
  public let notificationID: String?
  public let commentID: String?
  public let parentCommentID: String?
  public let parentType: String?
  public let url: URL?
  public let postID: String?
  public let postContent: String?

  public init?(userInfo: [AnyHashable: Any]) {
    func text(_ key: String) -> String? {
      (userInfo[key] as? String).nonEmptyValue ?? (userInfo[key] as? NSNumber)?.stringValue
    }
    guard let type = text("type") else { return nil }
    self.type = type
    notificationID = text("notificationId")
    commentID = text("commentId")
    parentCommentID = text("parentCommentId")
    parentType = text("parentType")
    url = text("url").flatMap(URL.init(string:))
    postID = text("postId")
    postContent = text("postContent")
  }

  /// For a reply, the reader lands on their own comment with the replies under it.
  public var targetCommentID: String? {
    type == "comment_reply" ? (parentCommentID ?? commentID) : commentID
  }

  public var isPost: Bool { parentType == "post" }
}

extension ExpressAPIClient {
  /// `PUT /v1/user/push-token`; true on HTTP 200 with `success: true`.
  public func registerPushToken(
    _ token: String,
    accessToken: String,
    refreshToken: String?,
    appVersion: String,
    language: String
  ) async -> Bool {
    if ExpressDebug.isDryRun {
      Logger.module.info("Express dry run: skipped PUT user/push-token")
      return true
    }
    return await pushCall(
      "PUT",
      path: "user/push-token",
      query: [],
      body: [
        "token": token, "platform": "ios", "deviceId": ExpressAccount.deviceID,
        "appVersion": appVersion, "lang": language,
      ],
      accessToken: accessToken,
      refreshToken: refreshToken
    )
  }

  /// `DELETE /v1/user/push-token` (the token also in the query, for proxies that drop bodies).
  public func deletePushToken(_ token: String, accessToken: String, refreshToken: String?) async {
    if ExpressDebug.isDryRun {
      Logger.module.info("Express dry run: skipped DELETE user/push-token")
      return
    }
    _ = await pushCall(
      "DELETE",
      path: "user/push-token",
      query: [("token", token)],
      body: ["token": token],
      accessToken: accessToken,
      refreshToken: refreshToken
    )
  }

  /// `POST /v1/user/notifications/read {ids}`.
  public func markNotificationRead(_ id: String, accessToken: String, refreshToken: String?) async {
    if ExpressDebug.isDryRun {
      Logger.module.info("Express dry run: skipped POST user/notifications/read")
      return
    }
    _ = await pushCall(
      "POST",
      path: "user/notifications/read",
      query: [],
      body: ["ids": [id]],
      accessToken: accessToken,
      refreshToken: refreshToken
    )
  }

  private func pushCall(
    _ method: String,
    path: String,
    query: [(String, String)],
    body: [String: Any],
    accessToken: String,
    refreshToken: String?
  ) async -> Bool {
    var request = makeRequest(
      path: path,
      query: query,
      method: method,
      authorization: .bearer(accessToken),
      body: try? JSONSerialization.data(withJSONObject: body),
      timeout: 10
    )
    if let refreshToken { request.setValue(refreshToken, forHTTPHeaderField: "x-refresh") }
    guard let (data, response) = try? await session.data(for: request),
      (response as? HTTPURLResponse)?.statusCode == 200
    else { return false }
    let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    return json?["success"] as? Bool == true
  }
}
