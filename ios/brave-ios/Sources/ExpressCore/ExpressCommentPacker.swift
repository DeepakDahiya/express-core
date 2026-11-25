// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import CoreGraphics

/// One page of the comments reel: a page of comment cards, or a full-screen portrait video.
public enum ExpressCommentSlide: Identifiable, Equatable {
  /// Cards on a text page. `peekReelID` is the video page that follows, whose top shows in a
  /// strip at the bottom of this page.
  case text(commentIDs: [String], peekReelID: String?)
  /// A portrait video comment on its own page.
  case reel(commentID: String)

  /// The page's first comment, so a page keeps its identity across re-packs.
  public var id: String {
    switch self {
    case .text(let ids, _): return "t:" + (ids.first ?? "")
    case .reel(let id): return "r:" + id
    }
  }

  public var commentIDs: [String] {
    switch self {
    case .text(let ids, _): return ids
    case .reel(let id): return [id]
    }
  }

  public var isReel: Bool {
    if case .reel = self { return true }
    return false
  }

  public var reelCommentID: String? {
    if case .reel(let id) = self { return id }
    return nil
  }
}

/// Packs comments into reel pages the way the Android app's `CommentSlidePacker` does: up to
/// 8 measured cards per text page, a portrait video on its own page, and the text page before
/// a video giving up its bottom 15% to a preview of it. Comments stay in server order, except
/// that a short comment from up to 10 places ahead can be pulled up to fill a page.
public enum ExpressCommentPacker {
  public static let maxRowsPerPage = 8
  public static let lookahead = 10
  public static let peekFraction: CGFloat = 0.15

  public struct Item: Equatable {
    public let id: String
    public let isPortraitVideo: Bool

    public init(id: String, isPortraitVideo: Bool) {
      self.id = id
      self.isPortraitVideo = isPortraitVideo
    }
  }

  /// - Parameters:
  ///   - rowsHeight: the height available for cards on a text page.
  ///   - peekHeight: the height of the strip previewing a following video page.
  ///   - height: a card's measured height, including the gap below it.
  public static func pack(
    _ items: [Item],
    rowsHeight: CGFloat,
    peekHeight: CGFloat,
    height: (String) -> CGFloat
  ) -> [ExpressCommentSlide] {
    var queue = items
    var slides: [ExpressCommentSlide] = []
    var pending: [(id: String, height: CGFloat)] = []
    var pendingHeight: CGFloat = 0

    func closeTextPage(peek: String? = nil) {
      slides.append(.text(commentIDs: pending.map(\.id), peekReelID: peek))
      pending = []
      pendingHeight = 0
    }

    // Pulls cards that still fit from the next few comments, skipping (but counting) videos.
    func fillFromLookahead(budget: CGFloat) {
      var scanned = 0
      var index = 0
      while index < queue.count, scanned < lookahead, pending.count < maxRowsPerPage {
        scanned += 1
        let candidate = queue[index]
        if candidate.isPortraitVideo {
          index += 1
          continue
        }
        let h = height(candidate.id)
        if pendingHeight + h <= budget {
          pending.append((candidate.id, h))
          pendingHeight += h
          queue.remove(at: index)
        } else {
          index += 1
        }
      }
    }

    while !queue.isEmpty {
      let comment = queue.removeFirst()
      if comment.isPortraitVideo {
        if !pending.isEmpty {
          let budget = rowsHeight - peekHeight
          // Make room for the peek strip by moving the last cards (never the first) on.
          var displaced: [(id: String, height: CGFloat)] = []
          while pending.count > 1, pendingHeight > budget {
            let last = pending.removeLast()
            pendingHeight -= last.height
            displaced.insert(last, at: 0)
          }
          if !displaced.isEmpty {
            // The moved cards start the page that gets the peek; this one closes without it.
            let requeued =
              displaced.map { Item(id: $0.id, isPortraitVideo: false) } + [comment]
            queue.insert(contentsOf: requeued, at: 0)
            closeTextPage()
            continue
          }
          fillFromLookahead(budget: budget)
          closeTextPage(peek: comment.id)
        }
        slides.append(.reel(commentID: comment.id))
        continue
      }
      let h = height(comment.id)
      if !pending.isEmpty, pendingHeight + h > rowsHeight || pending.count >= maxRowsPerPage {
        fillFromLookahead(budget: rowsHeight)
        closeTextPage()
      }
      // An empty page takes any card, even one taller than the page.
      pending.append((comment.id, h))
      pendingHeight += h
    }
    if !pending.isEmpty {
      closeTextPage()
    }
    return slides
  }

  /// Android's pager text cut: the text up to its 18th word, with "…", or nil when the text is
  /// that short already. Words start wherever a non-space follows a space.
  public static func clampedText(_ text: String, maxWords: Int = 18) -> String? {
    var words = 0
    var previousWasSpace = true
    for index in text.indices {
      let isSpace = text[index].isWhitespace
      if previousWasSpace && !isSpace {
        words += 1
        if words > maxWords {
          var head = String(text[..<index])
          while let last = head.last, last.isWhitespace { head.removeLast() }
          return head + "…"
        }
      }
      previousWasSpace = isSpace
    }
    return nil
  }
}
