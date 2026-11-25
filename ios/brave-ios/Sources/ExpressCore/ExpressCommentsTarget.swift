// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import Foundation

/// What a comments sheet shows, keyed the way the Android app keys comments
/// (`BraveActivity.showCommentsBottomSheet`):
/// - a web page by its full URL,
/// - a YouTube watch page by the canonical `https://www.youtube.com/watch?v=<id>`,
/// - a feed post by its id (its author and media for the chip travel separately, as an
///   `ExpressCommentsPostSource`).
public enum ExpressCommentsTarget: Equatable {
  case page(URL)
  case youtube(videoID: String)
  case post(id: String, title: String?)

  /// The target for the page currently open in a tab.
  public static func forPage(_ url: URL) -> ExpressCommentsTarget {
    if let host = url.host?.lowercased(), host.contains("youtube.com"),
      let videoID = URLComponents(url: url, resolvingAgainstBaseURL: false)?
        .queryItems?.first(where: { $0.name == "v" })?.value,
      !videoID.isEmpty
    {
      return .youtube(videoID: videoID)
    }
    return .page(url)
  }

  /// The URL comments are stored under, for page and YouTube targets.
  public var pageKeyURL: URL? {
    switch self {
    case .page(let url): return url
    case .youtube(let videoID): return URL(string: "https://www.youtube.com/watch?v=\(videoID)")
    case .post: return nil
    }
  }

  /// YouTube watch pages use a shorter sheet so the video stays visible (Android: 60%).
  public var prefersShortSheet: Bool {
    if case .youtube = self { return true }
    return false
  }
}

/// What the chip at the top of a post's comments shows (Android's `SourceChip.Source.forPost`
/// with `SourceChip.postMedia`): the author line, the age, the caption, and the post's photo,
/// video or voice note. It travels next to a `.post` target rather than inside it,
/// so the target's shape stays the same. The browser fills it from the feed's copy of the post;
/// a post opened from a notification or a link without one keeps the plain "Post" chip.
public struct ExpressCommentsPostSource: Equatable {
  public var author: String?
  public var avatarURL: URL?
  public var createdDate: Date?
  public var text: String?
  /// A photo, or a video's poster.
  public var imageURL: URL?
  public var videoURL: URL?
  public var audioURL: URL?
  public var audioDurationMs: Int
  /// The feed post itself, so its video can open in the post viewer.
  public var post: ExpressPost?

  public init(
    author: String?,
    avatarURL: URL?,
    createdDate: Date?,
    text: String?,
    imageURL: URL? = nil,
    videoURL: URL? = nil,
    audioURL: URL? = nil,
    audioDurationMs: Int = 0,
    post: ExpressPost? = nil
  ) {
    self.author = author
    self.avatarURL = avatarURL
    self.createdDate = createdDate
    self.text = text
    self.imageURL = imageURL
    self.videoURL = videoURL
    self.audioURL = audioURL
    self.audioDurationMs = audioDurationMs
    self.post = post
  }

  /// A feed post, named the way Android's sheet names it (`PostListAdapter.sheetUsername`):
  /// `@username` for tweets and reels, else the publisher.
  public init(post: ExpressPost) {
    var author = post.authorName
    if let username = post.subPost?.author?.username.nonEmptyValue {
      author = "@\(username)"
    }
    self.init(
      author: author,
      avatarURL: post.avatarURL,
      createdDate: post.createdDate,
      text: post.caption,
      imageURL: post.mediaImageURL,
      videoURL: post.mediaVideoURL,
      audioURL: post.audio?.url,
      audioDurationMs: post.audio?.durationMs ?? 0,
      post: post
    )
  }
}
