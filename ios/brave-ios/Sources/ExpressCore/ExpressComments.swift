// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import CoreGraphics
import Foundation
import os.log

// Comments, matching the Android app (`browser_express_comments/`). Every request sends the
// raw access token (no `Bearer`) when there is one. Android loads one page of 100 comments
// and has no sorting or "load more"; each reply level is fetched with `commentId=`.

/// One emoji reaction and how many people chose it.
public struct ExpressReaction: Hashable {
  public var emoji: String
  public var count: Int

  public init(emoji: String, count: Int) {
    self.emoji = emoji
    self.count = count
  }

  /// Emoji that differ only by the variation selector (U+FE0F) count as the same.
  public static func same(_ a: String, _ b: String) -> Bool {
    a.replacingOccurrences(of: "\u{FE0F}", with: "") == b.replacingOccurrences(of: "\u{FE0F}", with: "")
  }
}

public struct ExpressComment: Decodable, Identifiable, Hashable {
  public struct User: Hashable {
    public let id: String?
    public let username: String?
    public let avatar: String?
  }

  public let id: String
  public let content: String
  public let user: User?
  public let youtubeAuthorName: String?
  public let youtubeAvatarURL: String?
  /// The YouTube comment this mirrors. Equal to `id` for a comment that exists only on
  /// YouTube so far (Android's `isYouTubeOnly`).
  public let youtubeId: String?
  public let createdDate: Date?
  /// Number of direct replies.
  public var commentCount: Int
  /// Most used first.
  public var reactions: [ExpressReaction]
  public var reactionCount: Int
  /// The current user's reaction, or nil.
  public var myReaction: String?
  /// Arrived since the reader last opened this post; cleared once they page past it.
  public var isNew: Bool
  public let imageURL: URL?
  public let videoURL: URL?
  public let audioURL: URL?
  /// The voice note's length, when the server sends it (`mediaDurationMs`).
  public let audioDurationMs: Int
  public let documentName: String?
  public let documentURL: URL?
  public let documentMime: String?
  public let documentSize: Int64
  /// Every file, in order. Cards use the single-media fields above unless there are several.
  public let attachments: [ExpressCommentAttachment]
  /// Pixel size of the image or video, when the server sends it (0 when unknown).
  /// Media size; often missing from the API, so the reel fills it in after probing.
  public var mediaWidth: Int
  public var mediaHeight: Int

  enum CodingKeys: String, CodingKey {
    case id = "_id"
    case content, user, youtubeAuthorName, youtubeAvatarUrl, youtubeId, createdAt, commentCount
    case reactions, reactionCount, myReaction, upvoteCount, downvoteCount, didVote, isNew
    case mediaImageUrl, mediaGifUrl, mediaVideoUrl, mediaAudioUrl, mediaDocumentName
    case mediaDocumentUrl, mediaDocumentMime, mediaDocumentSize, mediaDurationMs
    case mediaWidth, mediaHeight
    case attachments
  }

  enum UserKeys: String, CodingKey { case id = "_id", username, avatar }
  enum ReactionKeys: String, CodingKey { case emoji, count }
  enum VoteKeys: String, CodingKey { case type }
  enum AttachmentKeys: String, CodingKey {
    case kind, url, name, width, height, durationMs, mime, size
  }

  public init(from decoder: Decoder) throws {
    let c = try decoder.container(keyedBy: CodingKeys.self)
    guard let id = c.lenientString(.id) else {
      throw DecodingError.dataCorruptedError(forKey: .id, in: c, debugDescription: "Comment without _id")
    }
    self.id = id
    content = (try? c.decodeIfPresent(String.self, forKey: .content)) ?? ""
    if let u = try? c.nestedContainer(keyedBy: UserKeys.self, forKey: .user) {
      user = User(id: u.lenientString(.id), username: u.lenientString(.username), avatar: u.lenientString(.avatar))
    } else if let bareID = c.lenientString(.user) {
      user = User(id: bareID, username: nil, avatar: nil)
    } else {
      user = nil
    }
    youtubeId = c.lenientString(.youtubeId)
    youtubeAuthorName = youtubeId == nil ? nil : c.lenientString(.youtubeAuthorName)
    youtubeAvatarURL = youtubeId == nil ? nil : c.lenientString(.youtubeAvatarUrl)
    createdDate = c.lenientString(.createdAt).flatMap(ExpressDateParser.date(fromISO8601:))
    commentCount = c.lenientInt(.commentCount) ?? 0
    isNew = c.lenientBool(.isNew) ?? false

    // Reactions; old comments only have votes, which Android shows as 👍 and 👎.
    var parsed: [ExpressReaction] = []
    var hasReactionsField = false
    if var array = try? c.nestedUnkeyedContainer(forKey: .reactions) {
      hasReactionsField = true
      while !array.isAtEnd {
        if let r = try? array.nestedContainer(keyedBy: ReactionKeys.self),
          let emoji = r.lenientString(.emoji)
        {
          parsed.append(ExpressReaction(emoji: emoji, count: r.lenientInt(.count) ?? 0))
        } else {
          _ = try? array.decode(SkippedElement.self)
        }
      }
    }
    let didVote = (try? c.nestedContainer(keyedBy: VoteKeys.self, forKey: .didVote))?.lenientString(.type)
    if !hasReactionsField {
      let up = c.lenientInt(.upvoteCount) ?? 0
      let down = c.lenientInt(.downvoteCount) ?? 0
      if up > 0 { parsed.append(ExpressReaction(emoji: "👍", count: up)) }
      if down > 0 { parsed.append(ExpressReaction(emoji: "👎", count: down)) }
    }
    reactions = parsed.filter { $0.count > 0 }.sorted { $0.count > $1.count }
    reactionCount = c.lenientInt(.reactionCount) ?? reactions.reduce(0) { $0 + $1.count }
    if c.contains(.myReaction) {
      myReaction = c.lenientString(.myReaction)
    } else {
      myReaction = didVote == "up" ? "👍" : (didVote == "down" ? "👎" : nil)
    }

    // Media: the dedicated fields, else the first matching attachment.
    var image = c.lenientURL(.mediaImageUrl) ?? c.lenientURL(.mediaGifUrl)
    var video = c.lenientURL(.mediaVideoUrl)
    var audio = c.lenientURL(.mediaAudioUrl)
    var document = c.lenientString(.mediaDocumentName)
    var documentLink = c.lenientURL(.mediaDocumentUrl)
    var documentType = c.lenientString(.mediaDocumentMime)
    var documentBytes = c.lenientInt64(.mediaDocumentSize) ?? 0
    var audioMs = c.lenientInt(.mediaDurationMs) ?? 0
    var width = c.lenientInt(.mediaWidth) ?? 0
    var height = c.lenientInt(.mediaHeight) ?? 0
    var files: [ExpressCommentAttachment] = []
    if var attachments = try? c.nestedUnkeyedContainer(forKey: .attachments) {
      while !attachments.isAtEnd {
        guard let a = try? attachments.nestedContainer(keyedBy: AttachmentKeys.self) else {
          _ = try? attachments.decode(SkippedElement.self)
          continue
        }
        let url = a.lenientURL(.url)
        let kind = a.lenientString(.kind)
        if let kind, let url {
          files.append(
            ExpressCommentAttachment(
              kind: ExpressAttachmentKind(rawValue: kind) ?? .document,
              url: url,
              name: a.lenientString(.name),
              width: a.lenientInt(.width) ?? 0,
              height: a.lenientInt(.height) ?? 0,
              durationMs: a.lenientInt(.durationMs) ?? 0,
              mime: a.lenientString(.mime),
              size: a.lenientInt64(.size) ?? 0
            )
          )
        }
        if (kind == "image" || kind == "gif" || kind == "video"), width == 0 || height == 0 {
          width = a.lenientInt(.width) ?? 0
          height = a.lenientInt(.height) ?? 0
        }
        switch kind {
        case "image", "gif": if image == nil { image = url }
        case "video": if video == nil { video = url }
        case "audio":
          if audio == nil {
            audio = url
            if audioMs == 0 { audioMs = a.lenientInt(.durationMs) ?? 0 }
          }
        case "document":
          if document == nil {
            document = a.lenientString(.name) ?? "Document"
            documentLink = url
            documentType = a.lenientString(.mime)
            documentBytes = a.lenientInt64(.size) ?? 0
          }
        default: break
        }
      }
    }
    imageURL = image
    videoURL = video
    audioURL = audio
    audioDurationMs = audioMs
    documentName = document
    documentURL = documentLink
    documentMime = documentType
    documentSize = documentBytes
    attachments = files
    mediaWidth = width
    mediaHeight = height
  }

  /// Exists only on YouTube so far: reactions and replies go through `yt_interact`, which
  /// copies it into Express first.
  public var isYouTubeOnly: Bool { youtubeId != nil && youtubeId == id }

  public static func == (lhs: ExpressComment, rhs: ExpressComment) -> Bool {
    lhs.id == rhs.id && lhs.reactions == rhs.reactions && lhs.myReaction == rhs.myReaction
      && lhs.commentCount == rhs.commentCount
      && lhs.mediaWidth == rhs.mediaWidth && lhs.mediaHeight == rhs.mediaHeight
      && lhs.isNew == rhs.isNew
  }
  public func hash(into hasher: inout Hasher) { hasher.combine(id) }
}

extension ExpressComment {
  /// `user.username`, else the YouTube author, else empty.
  public var authorName: String {
    user?.username.nonEmptyValue ?? youtubeAuthorName.nonEmptyValue ?? ""
  }

  public var avatarURL: URL? {
    ExpressAvatar.resolve(user?.avatar ?? youtubeAvatarURL, seed: user?.id ?? id)
  }

  /// Android's single-media card shape: 16:9 when the media is at least 1.55 wide,
  /// 4:3 from 1.15, otherwise square. Unknown sizes use 16:9.
  public var mediaCardAspectRatio: CGFloat {
    guard mediaWidth > 0, mediaHeight > 0 else { return 16 / 9 }
    let ratio = CGFloat(mediaWidth) / CGFloat(mediaHeight)
    if ratio >= 1.55 { return 16 / 9 }
    if ratio >= 1.15 { return 4 / 3 }
    return 1
  }

  /// A portrait video with a known size: Android gives these a full-screen reel page.
  public var isPortraitVideo: Bool {
    videoURL != nil && mediaWidth > 0 && mediaHeight > mediaWidth
  }

  /// Applies a reaction change locally (Android's optimistic update): one off the previous
  /// reaction, one onto the new one, zero counts dropped, re-sorted by count.
  public mutating func applyReaction(_ emoji: String?) {
    if let previous = myReaction,
      let index = reactions.firstIndex(where: { ExpressReaction.same($0.emoji, previous) })
    {
      reactions[index].count -= 1
      if reactions[index].count <= 0 { reactions.remove(at: index) }
      reactionCount = max(0, reactionCount - 1)
    }
    if let emoji {
      if let index = reactions.firstIndex(where: { ExpressReaction.same($0.emoji, emoji) }) {
        reactions[index].count += 1
      } else {
        reactions.append(ExpressReaction(emoji: emoji, count: 1))
      }
      reactionCount += 1
    }
    reactions.sort { $0.count > $1.count }
    myReaction = emoji
  }
}

/// `GET /comment` response.
public struct ExpressCommentsResponse: Decodable {
  public let comments: [ExpressComment]
  public let parentComment: ExpressComment?
  /// Top-level comment count (page and post modes).
  public let commentCount: Int?
  /// Every emoji given across the post's or page's comments (`postReactions.reactionCount`, or
  /// `pageReactions` for a page), for the "Add your take" card; nil when the server sent none
  /// (Android's `BrowserExpressGetCommentsUtil` totals).
  public let reactionCount: Int?
  public let hashtag: String?

  enum CodingKeys: String, CodingKey {
    case success, error, comments, parentComment, commentCount, postHashtag
    case postReactions, pageReactions
  }

  enum TotalsKeys: String, CodingKey { case reactionCount }

  public init(from decoder: Decoder) throws {
    let c = try decoder.container(keyedBy: CodingKeys.self)
    guard c.lenientBool(.success) == true else {
      throw ExpressAPIError.server(c.lenientString(.error) ?? "Couldn't load comments")
    }
    var comments: [ExpressComment] = []
    if var array = try? c.nestedUnkeyedContainer(forKey: .comments) {
      while !array.isAtEnd {
        if let comment = try? array.decode(ExpressComment.self) {
          comments.append(comment)
        } else {
          _ = try? array.decode(SkippedElement.self)
        }
      }
    }
    self.comments = comments
    parentComment = try? c.decodeIfPresent(ExpressComment.self, forKey: .parentComment)
    commentCount = c.lenientInt(.commentCount)
    // A post's totals come as postReactions (newer servers), a page's as pageReactions; both are
    // {reactions, reactionCount}. A null postReactions falls through to pageReactions, as there.
    let totals =
      (try? c.nestedContainer(keyedBy: TotalsKeys.self, forKey: .postReactions))
      ?? (try? c.nestedContainer(keyedBy: TotalsKeys.self, forKey: .pageReactions))
    reactionCount = totals?.lenientInt(.reactionCount)
    if var tag = c.lenientString(.postHashtag) {
      while tag.hasPrefix("#") { tag.removeFirst() }
      hashtag = (tag.isEmpty || tag.contains(" ")) ? nil : tag
    } else {
      hashtag = nil
    }
  }
}

/// Where a new comment goes.
public enum ExpressCommentParent {
  /// A top-level comment on a page (YouTube pages use the canonical watch URL).
  case page(URL)
  case post(id: String)
  /// A reply. `pageURL` is sent along for page comments, like Android.
  case comment(id: String, pageURL: URL?)
}

/// Tokens the server returns when a signed-out user's first comment or reaction creates a
/// guest account.
/// The kinds of file a comment can carry (Android's `CommentAttachment` kinds).
public enum ExpressAttachmentKind: String, Sendable, CaseIterable {
  case image, gif, video, audio, document
}

/// One file on a posted comment.
public struct ExpressCommentAttachment: Hashable, Sendable {
  public let kind: ExpressAttachmentKind
  public let url: URL
  public let name: String?
  public let width: Int
  public let height: Int
  public let durationMs: Int
  public let mime: String?
  public let size: Int64

  public init(
    kind: ExpressAttachmentKind,
    url: URL,
    name: String?,
    width: Int,
    height: Int,
    durationMs: Int,
    mime: String?,
    size: Int64
  ) {
    self.kind = kind
    self.url = url
    self.name = name
    self.width = width
    self.height = height
    self.durationMs = durationMs
    self.mime = mime
    self.size = size
  }
}

/// A file already in storage, ready to go on `POST /comment`.
public struct ExpressUploadedAttachment: Sendable {
  public let key: String
  public let kind: ExpressAttachmentKind
  public let name: String?
  public let width: Int
  public let height: Int
  public let durationMs: Int

  public init(key: String, kind: ExpressAttachmentKind, name: String?, width: Int, height: Int, durationMs: Int) {
    self.key = key
    self.kind = kind
    self.name = name
    self.width = width
    self.height = height
    self.durationMs = durationMs
  }
}

public struct ExpressGuestTokens: Sendable {
  public let accessToken: String
  public let refreshToken: String?
}

public struct ExpressReactionResult {
  public let reactions: [ExpressReaction]
  public let reactionCount: Int
  public let myReaction: String?
  public let tokens: ExpressGuestTokens?
}

extension ExpressAPIClient {
  /// `GET /comment` for a page, a post, or the replies to a comment (one page of 100, like Android).
  public func fetchComments(
    for target: ExpressCommentsTarget?,
    repliesTo commentID: String? = nil,
    accessToken: String?
  ) async throws -> ExpressCommentsResponse {
    var query: [(String, String)] = []
    if let commentID {
      query.append(("commentId", commentID))
    } else if case .post(let id, _) = target {
      query.append(("postId", id))
    } else if let url = target?.pageKeyURL {
      query.append(("url", url.absoluteString))
    }
    query += [("page", "1"), ("per_page", "100"), ("country", Self.countryCode)]
    return try await send(
      makeRequest(
        path: "comment",
        query: query,
        authorization: accessToken.map { .raw($0) } ?? .none,
        timeout: 20
      ),
      as: ExpressCommentsResponse.self
    )
  }

  /// `POST /comment` (text only). Returns the server's copy and any new guest tokens.
  public func postComment(
    _ content: String,
    to parent: ExpressCommentParent,
    attachments: [ExpressUploadedAttachment] = [],
    accessToken: String?
  ) async throws -> (comment: ExpressComment, tokens: ExpressGuestTokens?) {
    if ExpressDebug.isDryRun {
      Logger.module.info("Express dry run: skipped POST comment")
      throw ExpressAPIError.server("Dry run: nothing was posted.")
    }
    var body: [String: Any] = ["content": content, "platform": "iOS"]
    switch parent {
    case .page(let url):
      body["parentType"] = "page"
      body["url"] = url.absoluteString
    case .post(let id):
      body["parentType"] = "post"
      body["parentId"] = id
    case .comment(let id, let pageURL):
      body["parentType"] = "comment"
      body["parentId"] = id
      if let pageURL { body["url"] = pageURL.absoluteString }
    }
    if !attachments.isEmpty {
      body["attachments"] = attachments.map { file -> [String: Any] in
        var item: [String: Any] = ["key": file.key, "kind": file.kind.rawValue]
        if let name = file.name { item["name"] = name }
        if file.width > 0 { item["width"] = file.width }
        if file.height > 0 { item["height"] = file.height }
        if file.durationMs > 0 { item["durationMs"] = file.durationMs }
        return item
      }
    }
    let country = Self.countryCode
    let request = makeRequest(
      path: "comment",
      query: country.isEmpty ? [] : [("country", country)],
      method: "POST",
      authorization: accessToken.map { .raw($0) } ?? .none,
      body: try JSONSerialization.data(withJSONObject: body),
      timeout: 30
    )
    struct Response: Decodable {
      let comment: ExpressComment
      let tokens: ExpressGuestTokens?
      enum CodingKeys: String, CodingKey { case success, error, message, comment, accessToken, refreshToken }
      init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        guard c.lenientBool(.success) == true else {
          throw ExpressAPIError.server(
            c.lenientString(.message) ?? c.lenientString(.error) ?? "Couldn't post your comment"
          )
        }
        comment = try c.decode(ExpressComment.self, forKey: .comment)
        tokens = c.lenientString(.accessToken).map {
          ExpressGuestTokens(accessToken: $0, refreshToken: c.lenientString(.refreshToken))
        }
      }
    }
    let response = try await send(request, as: Response.self)
    return (response.comment, response.tokens)
  }

  /// `PUT /comment/{id}/reaction` with an emoji, or `DELETE` to remove the user's reaction.
  public func setReaction(
    commentID: String,
    emoji: String?,
    accessToken: String?
  ) async throws -> ExpressReactionResult {
    if ExpressDebug.isDryRun {
      Logger.module.info("Express dry run: skipped comment reaction")
      throw ExpressAPIError.server("Dry run: the reaction wasn't sent.")
    }
    let request = makeRequest(
      path: "comment/\(commentID)/reaction",
      method: emoji == nil ? "DELETE" : "PUT",
      authorization: accessToken.map { .raw($0) } ?? .none,
      body: try emoji.map { try JSONSerialization.data(withJSONObject: ["emoji": $0]) },
      timeout: 15
    )
    struct Response: Decodable {
      let result: ExpressReactionResult
      enum CodingKeys: String, CodingKey {
        case success, reactions, reactionCount, myReaction, accessToken, refreshToken
      }
      enum ReactionKeys: String, CodingKey { case emoji, count }
      init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        guard c.lenientBool(.success) == true else { throw ExpressAPIError.invalidResponse }
        var reactions: [ExpressReaction] = []
        if var array = try? c.nestedUnkeyedContainer(forKey: .reactions) {
          while !array.isAtEnd {
            if let r = try? array.nestedContainer(keyedBy: ReactionKeys.self),
              let emoji = r.lenientString(.emoji)
            {
              reactions.append(ExpressReaction(emoji: emoji, count: r.lenientInt(.count) ?? 0))
            } else {
              _ = try? array.decode(SkippedElement.self)
            }
          }
        }
        reactions = reactions.filter { $0.count > 0 }.sorted { $0.count > $1.count }
        result = ExpressReactionResult(
          reactions: reactions,
          reactionCount: c.lenientInt(.reactionCount) ?? reactions.reduce(0) { $0 + $1.count },
          myReaction: c.lenientString(.myReaction),
          tokens: c.lenientString(.accessToken).map {
            ExpressGuestTokens(accessToken: $0, refreshToken: c.lenientString(.refreshToken))
          }
        )
      }
    }
    return try await send(request, as: Response.self).result
  }

  /// `POST /post/{id}/opened` after a post's comments first load, so the server resets its
  /// "new takes" count. Fire-and-forget; skipped when signed out.
  public func markPostOpened(id: String, accessToken: String) async {
    if ExpressDebug.isDryRun {
      Logger.module.info("Express dry run: skipped POST post/opened")
      return
    }
    let request = makeRequest(
      path: "post/\(id)/opened",
      method: "POST",
      authorization: .raw(accessToken),
      timeout: 15
    )
    _ = try? await session.data(for: request)
  }
}
