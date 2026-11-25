// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import CoreGraphics
import Foundation

// Models for `GET /v1/post/feed`, parsed with the same rules as the Android app
// (`BrowserExpressGetPostsUtil.java`): a malformed post is skipped instead of failing the
// page, and nil, "" and "null" all count as absent.

/// One page of the feed.
public struct ExpressFeedPage: Decodable {
  public let success: Bool
  /// Cursor for the next page; nil on the last page.
  public let nextCursor: String?
  public let posts: [ExpressPost]
  public let error: String?

  enum CodingKeys: String, CodingKey { case success, nextCursor, posts, error }

  public init(from decoder: Decoder) throws {
    let c = try decoder.container(keyedBy: CodingKeys.self)
    success = c.lenientBool(.success) ?? false
    nextCursor = c.lenientString(.nextCursor)
    error = c.lenientString(.error)
    var posts: [ExpressPost] = []
    if var array = try? c.nestedUnkeyedContainer(forKey: .posts) {
      while !array.isAtEnd {
        if let post = try? array.decode(ExpressPost.self) {
          posts.append(post)
        } else {
          _ = try? array.decode(SkippedElement.self)
        }
      }
    }
    self.posts = posts
  }
}

/// A feed post. Tweets and Instagram reels carry a `subPost`; publisher posts link to an article.
public struct ExpressPost: Decodable, Identifiable, Hashable {
  public struct Publisher: Hashable {
    public let name: String?
    public let imageURL: String?
  }

  public struct Author: Hashable {
    public let name: String?
    public let username: String?
    public let profilePicture: String?
  }

  public struct Media: Hashable {
    public let imageURL: URL?
    public let videoURL: URL?
    public let width: Int
    public let height: Int
  }

  public struct SubPost: Hashable {
    public let url: URL?
    public let content: String?
    public let author: Author?
    public let media: Media?
  }

  public struct Audio: Hashable {
    public let url: URL
    public let durationMs: Int
  }

  public struct NewTakes: Hashable {
    public let count: Int
    public let video: Int
    public let photo: Int
    public let voice: Int
    public let text: Int
    public let reactionsTotal: Int
    public let topEmoji: [String]
    public let firstNewTakeID: String?
  }

  public let id: String
  public let type: String?
  public let title: String?
  public let content: String?
  public let showFull: Bool
  public let imageURL: URL?
  public let url: URL?
  public let redirect: Bool
  public let publisher: Publisher?
  public let commentCount: Int
  public let reactionCount: Int
  /// Up to 3 emoji, in the order the server sorts them.
  public let topReactionEmoji: [String]
  public let hashtag: String?
  public let audio: Audio?
  public private(set) var newTakes: NewTakes?
  /// Only set for `type` "Twitter" or "Instagram", like Android.
  public let subPost: SubPost?
  /// `subPost.createdAt` (ms) when present, else `createdAt` (ISO-8601).
  public let createdDate: Date?

  enum CodingKeys: String, CodingKey {
    case id = "_id"
    case type, title, content, showFull, imageUrl, url, redirect, publisher, createdAt
    case commentCount, reactionCount, reactions, hashtag, audioUrl, audioDurationMs
    case newTakes, subPost
  }

  enum PublisherKeys: String, CodingKey { case name, imageUrl }
  enum ReactionKeys: String, CodingKey { case emoji }
  enum SubPostKeys: String, CodingKey { case url, content, createdAt, author, media }
  enum AuthorKeys: String, CodingKey { case name, username, profilePicture }
  enum MediaKeys: String, CodingKey {
    case imageUrl, videoUrl, width, height, audioUrl, durationMs
  }
  enum NewTakesKeys: String, CodingKey {
    case count, byFormat, reactionsTotal, topEmoji, firstNewTakeId
  }
  enum ByFormatKeys: String, CodingKey { case video, photo, voice, text }

  public init(from decoder: Decoder) throws {
    let c = try decoder.container(keyedBy: CodingKeys.self)
    guard let id = c.lenientString(.id) else {
      throw DecodingError.dataCorruptedError(
        forKey: .id,
        in: c,
        debugDescription: "Post without _id"
      )
    }
    self.id = id
    type = c.lenientString(.type)
    title = c.lenientString(.title)
    content = c.lenientString(.content)
    showFull = c.lenientBool(.showFull) ?? false
    imageURL = c.lenientURL(.imageUrl)
    url = c.lenientURL(.url)
    redirect = c.lenientBool(.redirect) ?? false
    commentCount = c.lenientInt(.commentCount) ?? 0
    reactionCount = c.lenientInt(.reactionCount) ?? 0

    if let p = try? c.nestedContainer(keyedBy: PublisherKeys.self, forKey: .publisher) {
      publisher = Publisher(name: p.lenientString(.name), imageURL: p.lenientString(.imageUrl))
    } else {
      publisher = nil
    }

    var emoji: [String] = []
    if var reactions = try? c.nestedUnkeyedContainer(forKey: .reactions) {
      while !reactions.isAtEnd, emoji.count < 3 {
        if let r = try? reactions.nestedContainer(keyedBy: ReactionKeys.self),
          let e = r.lenientString(.emoji)
        {
          emoji.append(e)
        } else {
          _ = try? reactions.decode(SkippedElement.self)
        }
      }
    }
    topReactionEmoji = emoji

    if var tag = c.lenientString(.hashtag) {
      while tag.hasPrefix("#") { tag.removeFirst() }
      tag = tag.trimmingCharacters(in: .whitespaces)
      hashtag = (tag.isEmpty || tag.contains(" ")) ? nil : tag
    } else {
      hashtag = nil
    }

    // `subPost.createdAt` and its audio are read for every post type; the rest only for
    // tweets and reels.
    let sub = try? c.nestedContainer(keyedBy: SubPostKeys.self, forKey: .subPost)
    let subMedia = try? sub?.nestedContainer(keyedBy: MediaKeys.self, forKey: .media)
    let isEmbed = type == "Twitter" || type == "Instagram"
    if isEmbed, let sub {
      let author = (try? sub.nestedContainer(keyedBy: AuthorKeys.self, forKey: .author)).map {
        Author(
          name: $0.lenientString(.name),
          username: $0.lenientString(.username),
          profilePicture: $0.lenientString(.profilePicture)
        )
      }
      let media = subMedia.map {
        Media(
          imageURL: $0.lenientURL(.imageUrl),
          videoURL: $0.lenientURL(.videoUrl),
          width: $0.lenientInt(.width) ?? 0,
          height: $0.lenientInt(.height) ?? 0
        )
      }
      subPost = SubPost(
        url: sub.lenientURL(.url),
        content: sub.lenientString(.content),
        author: author,
        media: media
      )
    } else {
      subPost = nil
    }

    if let subAudio = subMedia?.lenientURL(.audioUrl) {
      audio = Audio(url: subAudio, durationMs: subMedia?.lenientInt(.durationMs) ?? 0)
    } else if let postAudio = c.lenientURL(.audioUrl) {
      audio = Audio(url: postAudio, durationMs: c.lenientInt(.audioDurationMs) ?? 0)
    } else {
      audio = nil
    }

    if let ms = sub?.lenientInt64(.createdAt), ms > 0 {
      createdDate = Date(timeIntervalSince1970: TimeInterval(ms) / 1000)
    } else if let iso = c.lenientString(.createdAt) {
      createdDate = ExpressDateParser.date(fromISO8601: iso)
    } else {
      createdDate = nil
    }

    if let n = try? c.nestedContainer(keyedBy: NewTakesKeys.self, forKey: .newTakes),
      let count = n.lenientInt(.count), count > 0
    {
      let byFormat = try? n.nestedContainer(keyedBy: ByFormatKeys.self, forKey: .byFormat)
      var top: [String] = []
      if var emojiArray = try? n.nestedUnkeyedContainer(forKey: .topEmoji) {
        while !emojiArray.isAtEnd, top.count < 3 {
          if let e = (try? emojiArray.decode(String.self)).nonEmptyValue {
            top.append(e)
          } else {
            _ = try? emojiArray.decode(SkippedElement.self)
          }
        }
      }
      newTakes = NewTakes(
        count: count,
        video: byFormat?.lenientInt(.video) ?? 0,
        photo: byFormat?.lenientInt(.photo) ?? 0,
        voice: byFormat?.lenientInt(.voice) ?? 0,
        text: byFormat?.lenientInt(.text) ?? 0,
        reactionsTotal: n.lenientInt(.reactionsTotal) ?? 0,
        topEmoji: top,
        firstNewTakeID: n.lenientString(.firstNewTakeId)
      )
    } else {
      newTakes = nil
    }
  }

  public static func == (lhs: ExpressPost, rhs: ExpressPost) -> Bool { lhs.id == rhs.id }
  public func hash(into hasher: inout Hasher) { hasher.combine(id) }
}

extension ExpressPost {
  /// Author line: `author.name`, else `@username`, else the publisher's name.
  public var authorName: String {
    if let name = subPost?.author?.name.nonEmptyValue { return name }
    if let username = subPost?.author?.username.nonEmptyValue { return "@\(username)" }
    return publisher?.name.nonEmptyValue ?? ""
  }

  /// Avatar, resolved with the same rules as Android's `ProfileAvatar`.
  public var avatarURL: URL? {
    let raw = subPost?.author?.profilePicture ?? publisher?.imageURL
    let seed = subPost?.author?.username.nonEmptyValue ?? publisher?.name.nonEmptyValue
    return ExpressAvatar.resolve(raw, seed: seed)
  }

  /// Caption: the embed's text, else the title plus (when `showFull`) the body.
  public var caption: String? {
    if let text = subPost?.content.nonEmptyValue { return text }
    let parts = [title.nonEmptyValue, showFull ? content.nonEmptyValue : nil].compactMap { $0 }
    return parts.isEmpty ? nil : parts.joined(separator: "\n")
  }

  public var mediaImageURL: URL? { subPost?.media?.imageURL ?? imageURL }
  public var mediaVideoURL: URL? { subPost?.media?.videoURL }
  public var hasMedia: Bool { mediaImageURL != nil || mediaVideoURL != nil }

  /// Width/height when the server sends both, clamped to 0.8...1.91 like Android.
  public var mediaAspectRatio: CGFloat? {
    guard let media = subPost?.media, media.width > 0, media.height > 0 else { return nil }
    return min(max(CGFloat(media.width) / CGFloat(media.height), 0.8), 1.91)
  }

  /// Publisher posts with `redirect` open their article instead of the comments.
  public var opensArticle: Bool { subPost == nil && redirect && url != nil }

  /// The link to share or open for this post.
  public var shareURL: URL? { subPost?.url ?? url ?? mediaVideoURL }
}

/// Builds avatar URLs exactly like the Android app's `ProfileAvatar`.
public enum ExpressAvatar {
  static let defaultFacesURL = "https://media.browser.express/avatars/default/v1/"
  static let defaultFaceCount: UInt32 = 48
  static let cdnOrigin = "https://media.browser.express/"
  static let s3Origins = [
    "https://telehealth-public.s3.eu-west-2.amazonaws.com/",
    "https://telehealth-public.s3.amazonaws.com/",
    "https://s3.eu-west-2.amazonaws.com/telehealth-public/",
  ]

  /// Rewrites old storage-bucket links to the CDN, and replaces missing, DiceBear and legacy
  /// avatars with one of 48 default faces picked from the seed.
  public static func resolve(_ avatarURL: String?, seed: String?) -> URL? {
    var seed = seed
    if let raw = avatarURL.nonEmptyValue,
      raw.range(of: #"/pfp0*\d+\.png$"#, options: .regularExpression) == nil
    {
      if let components = URLComponents(string: raw), components.host == "api.dicebear.com" {
        if let dicebearSeed = components.queryItems?.first(where: { $0.name == "seed" })?.value,
          !dicebearSeed.isEmpty
        {
          seed = dicebearSeed
        }
      } else {
        return URL(string: onCDN(raw))
      }
    }
    guard let seed, !seed.isEmpty else { return nil }
    return URL(string: "\(defaultFacesURL)\(faceIndex(seed)).webp")
  }

  /// FNV-1a 32-bit of the UTF-8 seed, unsigned, modulo 48.
  static func faceIndex(_ seed: String) -> UInt32 {
    var hash: UInt32 = 0x811c_9dc5
    for byte in seed.utf8 {
      hash ^= UInt32(byte)
      hash = hash &* 0x0100_0193
    }
    return hash % defaultFaceCount
  }

  static func onCDN(_ url: String) -> String {
    for origin in s3Origins where url.hasPrefix(origin) {
      return cdnOrigin + url.dropFirst(origin.count)
    }
    return url
  }
}

/// "now", "5m ago", "3h ago", "2d ago", "3w ago", then a short date (Android's format).
public enum ExpressRelativeTime {
  public static func string(from date: Date, now: Date = Date()) -> String {
    let seconds = max(0, now.timeIntervalSince(date))
    let minutes = Int(seconds / 60)
    if minutes < 1 { return "now" }
    if minutes < 60 { return "\(minutes)m ago" }
    let hours = minutes / 60
    if hours < 24 { return "\(hours)h ago" }
    let days = hours / 24
    if days < 7 { return "\(days)d ago" }
    let weeks = days / 7
    if weeks < 5 { return "\(weeks)w ago" }
    return date.formatted(.dateTime.month(.abbreviated).day().year())
  }
}

enum ExpressDateParser {
  static func date(fromISO8601 string: String) -> Date? {
    let withFraction = ISO8601DateFormatter()
    withFraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    if let date = withFraction.date(from: string) { return date }
    let plain = ISO8601DateFormatter()
    plain.formatOptions = [.withInternetDateTime]
    return plain.date(from: string)
  }
}

/// Consumes one element of an unkeyed container so a bad element can be skipped.
struct SkippedElement: Decodable {
  init(from decoder: Decoder) throws {}
}

extension KeyedDecodingContainer {
  /// A string, or a number rendered as a string. nil, "" and "null" become nil.
  func lenientString(_ key: Key) -> String? {
    if let value = try? decodeIfPresent(String.self, forKey: key) {
      return Optional(value).nonEmptyValue
    }
    if let value = try? decodeIfPresent(Int.self, forKey: key) { return String(value) }
    return nil
  }

  func lenientURL(_ key: Key) -> URL? {
    lenientString(key).flatMap { URL(string: $0) }
  }

  func lenientInt(_ key: Key) -> Int? {
    if let value = try? decodeIfPresent(Int.self, forKey: key) { return value }
    if let value = try? decodeIfPresent(Double.self, forKey: key) { return Int(value) }
    if let value = try? decodeIfPresent(String.self, forKey: key) { return Int(value) }
    return nil
  }

  func lenientInt64(_ key: Key) -> Int64? {
    if let value = try? decodeIfPresent(Int64.self, forKey: key) { return value }
    if let value = try? decodeIfPresent(Double.self, forKey: key) { return Int64(value) }
    if let value = try? decodeIfPresent(String.self, forKey: key) { return Int64(value) }
    return nil
  }

  func lenientBool(_ key: Key) -> Bool? {
    if let value = try? decodeIfPresent(Bool.self, forKey: key) { return value }
    if let value = try? decodeIfPresent(Int.self, forKey: key) { return value != 0 }
    if let value = try? decodeIfPresent(String.self, forKey: key) {
      return value.lowercased() == "true"
    }
    return nil
  }
}

#if DEBUG
extension ExpressPost {
  /// Debug builds: the post with sample "new takes", to see the callout without real ones.
  public func withSampleNewTakes(textOnly: Bool, firstNewTakeID: String?) -> ExpressPost {
    var copy = self
    copy.newTakes =
      textOnly
      ? NewTakes(
        count: 3, video: 0, photo: 0, voice: 0, text: 3, reactionsTotal: 0, topEmoji: [],
        firstNewTakeID: firstNewTakeID
      )
      : NewTakes(
        count: 12, video: 5, photo: 3, voice: 0, text: 4, reactionsTotal: 2400,
        topEmoji: ["🔥", "😂", "❤️"], firstNewTakeID: firstNewTakeID
      )
    return copy
  }
}
#endif
