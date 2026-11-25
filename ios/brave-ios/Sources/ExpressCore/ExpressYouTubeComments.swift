// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import Foundation
import os.log

/// YouTube watch-page comments as Android shows them (`CommentListFragment`,
/// `ReplyListFragment`, `YouTubeInteractUtil`): YouTube's own threads from the Data API, with
/// Express's copies taking their place where they exist, then Express-only comments.
///
/// Unlike Android, paging stops after `maxPages` (Android fetches every page, which is costly on
/// big videos) and requests have timeouts.
public enum ExpressYouTubeComments {
  private static let apiKey = "AIzaSyC-LdqrxY9etQklNNbfK1lNLkozljiixQg"
  private static let maxPages = 3

  public static func watchURL(videoID: String) -> URL {
    URL(string: "https://www.youtube.com/watch?v=\(videoID)")!
  }

  // MARK: Top level

  /// YouTube threads (by relevance) merged with `GET /comment/youtube`. Either side failing
  /// counts as empty, as on Android.
  public static func topLevel(
    videoID: String,
    accessToken: String?,
    client: ExpressAPIClient = .shared
  ) async -> [ExpressComment] {
    async let youtube = threads(videoID: videoID, session: client.session)
    async let express = expressComments(
      path: "comment/youtube",
      query: [("pageUrl", watchURL(videoID: videoID).absoluteString)],
      accessToken: accessToken,
      client: client
    )
    return merge(youtube: await youtube, express: await express, keepUnmatchedMirrors: true)
  }

  /// Express copies replace YouTube's rows in YouTube's order; copies YouTube no longer lists
  /// come next, then Express-only comments, both in the server's order.
  static func merge(
    youtube: [ExpressComment],
    express: [ExpressComment],
    keepUnmatchedMirrors: Bool
  ) -> [ExpressComment] {
    var mirrors: [String: ExpressComment] = [:]
    var mirrorOrder: [ExpressComment] = []
    var native: [ExpressComment] = []
    for comment in express {
      if let youtubeID = comment.youtubeId {
        if mirrors[youtubeID] == nil { mirrorOrder.append(comment) }
        mirrors[youtubeID] = comment
      } else {
        native.append(comment)
      }
    }
    var result: [ExpressComment] = []
    var used: Set<String> = []
    for comment in youtube {
      let key = comment.youtubeId ?? comment.id
      result.append(mirrors[key] ?? comment)
      used.insert(key)
    }
    if keepUnmatchedMirrors {
      // Android keeps these at the top level but drops them from replies; both are kept here.
      result += mirrorOrder.filter { !used.contains($0.youtubeId ?? "") }
    }
    return result + native
  }

  // MARK: Replies

  /// Replies to a comment on a YouTube page: YouTube's replies (with Express copies in their
  /// place), then Express-only replies. A comment that exists only on YouTube has no Express
  /// replies yet.
  /// `expressID` is the parent's Express id (once copied into Express), or nil.
  public static func replies(
    to parent: ExpressComment,
    expressID: String?,
    accessToken: String?,
    client: ExpressAPIClient = .shared
  ) async -> [ExpressComment] {
    async let youtube = replyComments(parentID: parent.youtubeId, session: client.session)
    async let express = expressReplies(to: expressID, accessToken: accessToken, client: client)
    return merge(youtube: await youtube, express: await express, keepUnmatchedMirrors: true)
  }

  // MARK: Interactions

  public enum Interaction {
    case reply(String)
    /// nil removes the reader's reaction.
    case reaction(String?)
  }

  public struct InteractResult: Sendable {
    /// YouTube comment id → the Express id it now has.
    public let resolvedIDs: [String: String]
    public let targetID: String?
    public let commentCount: Int?
    public let reactions: [ExpressReaction]
    public let reactionCount: Int?
    public let myReaction: String?
    public let tokens: ExpressGuestTokens?
  }

  /// `POST /comment/yt_interact`: copies the ancestors (root first) into Express, then replies
  /// to or reacts on the last one. Raw token, as on Android.
  public static func interact(
    pageURL: URL,
    ancestors: [ExpressComment],
    interaction: Interaction,
    accessToken: String?,
    client: ExpressAPIClient = .shared
  ) async throws -> InteractResult {
    if ExpressDebug.isDryRun {
      Logger.module.info("Express dry run: skipped POST comment/yt_interact")
      throw ExpressAPIError.server("Dry run: nothing was sent.")
    }
    var body: [String: Any] = [
      "pageUrl": pageURL.absoluteString,
      "ancestors": ancestors.map { comment -> [String: Any] in
        var item: [String: Any] = [
          "youtubeId": comment.youtubeId ?? comment.id,
          "content": comment.content,
          "upvoteCount": comment.reactions.first(where: { $0.emoji == "👍" })?.count ?? 0,
          "commentCount": comment.commentCount,
        ]
        if let name = comment.youtubeAuthorName ?? comment.user?.username { item["youtubeAuthorName"] = name }
        if let avatar = comment.youtubeAvatarURL ?? comment.user?.avatar { item["youtubeAvatarUrl"] = avatar }
        return item
      },
    ]
    switch interaction {
    case .reply(let text):
      body["interactionType"] = "reply"
      body["replyContent"] = text
    case .reaction(let emoji):
      body["interactionType"] = "reaction"
      body["emoji"] = emoji ?? NSNull()
    }
    let request = client.makeRequest(
      path: "comment/yt_interact",
      method: "POST",
      authorization: accessToken.map { .raw($0) } ?? .none,
      body: try JSONSerialization.data(withJSONObject: body),
      timeout: 20
    )
    let (data, response) = try await client.session.data(for: request)
    let status = (response as? HTTPURLResponse)?.statusCode ?? 0
    let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    guard status == 200, json["success"] as? Bool == true else {
      throw ExpressAPIError.server((json["error"] as? String) ?? "HTTP \(status)")
    }
    var resolved: [String: String] = [:]
    for (key, value) in json["resolvedIds"] as? [String: Any] ?? [:] {
      if let id = value as? String { resolved[key] = id }
    }
    let reactions = (json["reactions"] as? [[String: Any]] ?? []).compactMap { item -> ExpressReaction? in
      guard let emoji = item["emoji"] as? String else { return nil }
      return ExpressReaction(emoji: emoji, count: (item["count"] as? NSNumber)?.intValue ?? 0)
    }
    .filter { $0.count > 0 }
    .sorted { $0.count > $1.count }
    return InteractResult(
      resolvedIDs: resolved,
      targetID: json["targetId"] as? String,
      commentCount: (json["commentCount"] as? NSNumber)?.intValue,
      reactions: reactions,
      reactionCount: (json["reactionCount"] as? NSNumber)?.intValue,
      myReaction: json["myReaction"] as? String,
      tokens: ExpressAPIClient.guestTokens(in: json)
    )
  }

  // MARK: Fetching

  /// Express replies to a comment (`GET /comment?commentId=`), none while it's only on YouTube.
  private static func expressReplies(
    to parentID: String?,
    accessToken: String?,
    client: ExpressAPIClient
  ) async -> [ExpressComment] {
    guard let parentID else { return [] }
    return await expressComments(
      path: "comment",
      query: [
        ("commentId", parentID), ("page", "1"), ("per_page", "100"),
        ("country", ExpressAPIClient.countryCode),
      ],
      accessToken: accessToken,
      client: client
    )
  }

  private static func expressComments(
    path: String,
    query: [(String, String)],
    accessToken: String?,
    client: ExpressAPIClient
  ) async -> [ExpressComment] {
    let request = client.makeRequest(
      path: path,
      query: query,
      authorization: accessToken.map { .raw($0) } ?? .none,
      timeout: 20
    )
    do {
      return try await client.send(request, as: ExpressCommentsResponse.self).comments
    } catch {
      Logger.module.error("Express YouTube comments (\(path, privacy: .public)) failed: \(error.localizedDescription, privacy: .public)")
      return []
    }
  }

  /// `commentThreads?videoId=` by relevance, up to `maxPages` pages of 100.
  static func threads(videoID: String, session: URLSession) async -> [ExpressComment] {
    await pages(
      endpoint: "commentThreads",
      parameters: [("part", "snippet"), ("videoId", videoID), ("order", "relevance")],
      session: session
    ) { item in
      guard let snippet = item["snippet"] as? [String: Any],
        let top = snippet["topLevelComment"] as? [String: Any],
        let id = top["id"] as? String,
        let inner = top["snippet"] as? [String: Any]
      else { return nil }
      return comment(id: id, snippet: inner, replyCount: (snippet["totalReplyCount"] as? NSNumber)?.intValue ?? 0)
    }
  }

  /// `comments?parentId=`, up to `maxPages` pages of 100. None for a comment not on YouTube.
  static func replyComments(parentID: String?, session: URLSession) async -> [ExpressComment] {
    guard let parentID else { return [] }
    return await pages(
      endpoint: "comments",
      parameters: [("part", "snippet"), ("parentId", parentID)],
      session: session
    ) { item in
      guard let id = item["id"] as? String, let snippet = item["snippet"] as? [String: Any] else { return nil }
      return comment(id: id, snippet: snippet, replyCount: 0)
    }
  }

  private static func pages(
    endpoint: String,
    parameters: [(String, String)],
    session: URLSession,
    map: ([String: Any]) -> ExpressComment?
  ) async -> [ExpressComment] {
    var results: [ExpressComment] = []
    var pageToken: String?
    for _ in 0..<maxPages {
      var components = URLComponents(string: "https://www.googleapis.com/youtube/v3/\(endpoint)")!
      var items = [
        URLQueryItem(name: "key", value: apiKey),
        URLQueryItem(name: "textFormat", value: "plainText"),
        URLQueryItem(name: "maxResults", value: "100"),
      ]
      items += parameters.map { URLQueryItem(name: $0.0, value: $0.1) }
      if let pageToken { items.append(URLQueryItem(name: "pageToken", value: pageToken)) }
      components.queryItems = items
      var request = URLRequest(url: components.url!, timeoutInterval: 15)
      request.setValue("application/json", forHTTPHeaderField: "Accept")
      if let bundleID = Bundle.main.bundleIdentifier {
        request.setValue(bundleID, forHTTPHeaderField: "X-Ios-Bundle-Identifier")
      }
      guard let (data, response) = try? await session.data(for: request),
        (response as? HTTPURLResponse)?.statusCode == 200,
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
      else { break }
      results += (json["items"] as? [[String: Any]] ?? []).compactMap(map)
      guard let next = json["nextPageToken"] as? String, !next.isEmpty else { break }
      pageToken = next
    }
    return results
  }

  /// A YouTube comment in Express's shape: its likes show as 👍, its author as the user.
  private static func comment(id: String, snippet: [String: Any], replyCount: Int) -> ExpressComment? {
    let author = snippet["authorDisplayName"] as? String
    let avatar = snippet["authorProfileImageUrl"] as? String
    let channel = (snippet["authorChannelId"] as? [String: Any])?["value"] as? String
    var user: [String: Any] = ["_id": channel ?? id]
    if let author { user["username"] = author }
    if let avatar { user["avatar"] = avatar }
    var json: [String: Any] = [
      "_id": id,
      "youtubeId": id,
      "content": (snippet["textDisplay"] as? String) ?? (snippet["textOriginal"] as? String) ?? "",
      "upvoteCount": (snippet["likeCount"] as? NSNumber)?.intValue ?? 0,
      "downvoteCount": 0,
      "commentCount": replyCount,
      "user": user,
    ]
    if let author { json["youtubeAuthorName"] = author }
    if let avatar { json["youtubeAvatarUrl"] = avatar }
    if let published = snippet["publishedAt"] as? String { json["createdAt"] = published }
    if let parent = snippet["parentId"] as? String { json["commentParent"] = parent }
    guard let data = try? JSONSerialization.data(withJSONObject: json) else { return nil }
    return try? JSONDecoder().decode(ExpressComment.self, from: data)
  }

  // MARK: Pill preview

  /// The top few threads for the pill's preview cards (`commentThreads&maxResults=3`).
  public static func preview(videoID: String, session: URLSession = .shared) async -> [ExpressComment] {
    var components = URLComponents(string: "https://www.googleapis.com/youtube/v3/commentThreads")!
    components.queryItems = [
      URLQueryItem(name: "key", value: apiKey),
      URLQueryItem(name: "textFormat", value: "plainText"),
      URLQueryItem(name: "part", value: "snippet"),
      URLQueryItem(name: "videoId", value: videoID),
      URLQueryItem(name: "maxResults", value: "3"),
      URLQueryItem(name: "order", value: "relevance"),
    ]
    var request = URLRequest(url: components.url!, timeoutInterval: 15)
    if let bundleID = Bundle.main.bundleIdentifier {
      request.setValue(bundleID, forHTTPHeaderField: "X-Ios-Bundle-Identifier")
    }
    guard let (data, response) = try? await session.data(for: request),
      (response as? HTTPURLResponse)?.statusCode == 200,
      let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    else { return [] }
    return (json["items"] as? [[String: Any]] ?? []).compactMap { item in
      guard let snippet = item["snippet"] as? [String: Any],
        let top = snippet["topLevelComment"] as? [String: Any],
        let id = top["id"] as? String,
        let inner = top["snippet"] as? [String: Any]
      else { return nil }
      return comment(id: id, snippet: inner, replyCount: 0)
    }
  }
}
