// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import ExpressCore
import SDWebImage
import SwiftUI
import UIKit

// "Add your take" (Android's `TakeComposer`): on the top level of the comments, the comment box
// grows into a sheet before anything is typed or picked: a handle, the title and ×, and a card
// with the topic and a stream of the people in the conversation, over the dimmed comments
// (a reel behind it is paused and blurred). The input row is the box's own. The paperclip puts
// the picker where the keyboard was, and closing with a draft asks first. With Reduce Motion
// everything only fades, over 150 ms.

extension ExpressColors {
  static let takeBackdrop = UIColor(expressLight: 0x000000, expressDark: 0x000000)
  static let takeSheet = UIColor(expressLight: 0xFFFFFF, expressDark: 0x232428)
  static let takeHandle = UIColor(expressLight: 0xCCCFD9, expressDark: 0x3A3D45)
  static let takeTitle = UIColor(expressLight: 0x1F212E, expressDark: 0xF4F5F7)
  static let takeMeta = UIColor(expressLight: 0x80808C, expressDark: 0xA8ABB5)
  static let takeCloseBackground = UIColor(expressLight: 0xEEF0F8, expressDark: 0x31343B)
  static let takeCloseIcon = UIColor(expressLight: 0x747480, expressDark: 0xA8ABB5)
  static let takeTopicBackground = UIColor(expressLight: 0xF4F5FA, expressDark: 0x1E1F24)
  static let takeStatsNumber = UIColor(expressLight: 0x111111, expressDark: 0xF4F5F7)
  static let takeStatsDot = UIColor(expressLight: 0xC7C7DB, expressDark: 0x3A3D45)
}

/// What the comments are about, for the topic card.
struct ExpressTakeTopic: Equatable {
  /// The hashtag ("#KohliKaKamaal"), or the caption or page title when there is none.
  var title: String
  /// How many comments the topic has.
  var reactions: Int
  /// Every emoji given to those comments.
  var emotes: Int
  var faces: [URL]
  var emoji: [String]

  /// Android's `topicFrom`: the server's totals when it gave them (it counts past the 100 comments
  /// loaded), else counts from the loaded comments; up to four faces and four emoji always come
  /// from the loaded comments.
  static func from(
    title: String,
    comments: [ExpressComment],
    commentCount: Int? = nil,
    emoteCount: Int? = nil
  ) -> ExpressTakeTopic {
    var faces: [URL] = []
    var emoji: [String] = []
    var emotes = 0
    for comment in comments {
      emotes += max(0, comment.reactionCount)
      if let face = comment.avatarURL, faces.count < 4, !faces.contains(face) { faces.append(face) }
      for reaction in comment.reactions where emoji.count < 4 && !emoji.contains(reaction.emoji) {
        emoji.append(reaction.emoji)
      }
    }
    return ExpressTakeTopic(
      title: title,
      reactions: commentCount.flatMap { $0 >= 0 ? $0 : nil } ?? comments.count,
      emotes: emoteCount.flatMap { $0 >= 0 ? $0 : nil } ?? emotes,
      faces: faces,
      emoji: emoji
    )
  }
}

/// The sheet's state, shared by the comments screen (backdrop, blur) and the comment box.
@MainActor
final class ExpressTakeSheetModel: ObservableObject {
  /// Only the top level of the comments opens the sheet; replies use the plain box.
  var isEnabled: Bool
  @Published private(set) var isOpen = false
  @Published var isPickerShown = false
  @Published private(set) var topic = ExpressTakeTopic(title: "", reactions: 0, emotes: 0, faces: [], emoji: [])
  /// Bumped when the backdrop asks to close; the box decides whether to ask about its draft.
  @Published private(set) var closeRequest = 0
  /// Faces for the stream, loaded round.
  @Published private(set) var faceImages: [UIImage] = []

  init(isEnabled: Bool) {
    self.isEnabled = isEnabled
  }

  /// The box would open the sheet rather than act.
  var interceptsBox: Bool { isEnabled && !isOpen }

  /// Opens the sheet. `source` is Android's analytics source: text_box, mic, attach or
  /// take_button.
  func open(
    source: String,
    picker: Bool,
    model: ExpressCommentsListModel,
    fallbackTitle: String?
  ) {
    guard isEnabled else { return }
    if isOpen {
      if picker { isPickerShown = true }
      return
    }
    let title = model.hashtag.map { "#\($0)" } ?? fallbackTitle?.nonEmptyTitle ?? "Comments"
    topic = .from(
      title: title,
      comments: model.comments,
      commentCount: model.serverCommentCount,
      emoteCount: model.serverReactionCount
    )
    loadFaces(topic.faces)
    isPickerShown = picker
    isOpen = true
    var postID: String?
    let commentsFor: String
    switch model.rootTarget {
    case .post(let id, _):
      commentsFor = "post"
      postID = id
    case .youtube: commentsFor = "youtube"
    case .page: commentsFor = "page"
    }
    ExpressAnalytics.shared.capture(
      "Take Composer Opened",
      ["source": source, "comments_for": commentsFor, "post_id": postID]
    )
  }

  func requestClose() {
    guard isOpen else { return }
    if isPickerShown {
      isPickerShown = false
      return
    }
    closeRequest += 1
  }

  func close() {
    isPickerShown = false
    isOpen = false
  }

  private func loadFaces(_ urls: [URL]) {
    faceImages = []
    for url in urls {
      SDWebImageManager.shared.loadImage(with: url, options: [], progress: nil) { [weak self] image, _, _, _, _, _ in
        guard let image else { return }
        Task { @MainActor in self?.faceImages.append(image) }
      }
    }
  }
}

extension String {
  fileprivate var nonEmptyTitle: String? {
    let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
    return trimmed.isEmpty ? nil : trimmed
  }
}

// MARK: - Backdrop

/// The dim behind the sheet; a tap closes it (asking first when there's a draft).
struct ExpressTakeBackdrop: View {
  @ObservedObject var take: ExpressTakeSheetModel
  var overReel: Bool
  @Environment(\.colorScheme) private var colorScheme
  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  var body: some View {
    ZStack {
      if take.isOpen {
        backdropColor
          .ignoresSafeArea()
          .contentShape(Rectangle())
          .onTapGesture { take.requestClose() }
          .transition(.opacity)
          .accessibilityLabel("Close")
          .accessibilityAddTraits(.isButton)
      }
    }
    .animation(
      reduceMotion ? .linear(duration: 0.15) : .timingCurve(0.215, 0.61, 0.355, 1, duration: take.isOpen ? 0.5 : 0.4),
      value: take.isOpen
    )
  }

  /// Android: #4D000000 (light) or #59121318 over a reel; #80000000 in dark mode.
  private var backdropColor: Color {
    if colorScheme == .dark { return Color.black.opacity(0x80 / 255.0) }
    return overReel
      ? Color(UIColor(expressRGB: 0x121318)).opacity(0x59 / 255.0)
      : Color.black.opacity(0x4D / 255.0)
  }
}

// MARK: - Header and topic

/// The handle, "Add your take" with ×, and the topic card.
struct ExpressTakeHeader: View {
  @ObservedObject var take: ExpressTakeSheetModel
  var showsTopic: Bool
  var onClose: () -> Void

  var body: some View {
    VStack(spacing: 0) {
      Capsule()
        .fill(Color(ExpressColors.takeHandle))
        .frame(width: 36, height: 4)
        .frame(height: 20)
      HStack {
        Text("Add your take")
          .font(.system(size: 17))
          .foregroundColor(Color(ExpressColors.takeTitle))
          .accessibilityAddTraits(.isHeader)
        Spacer()
        Button(action: onClose) {
          Image(systemName: "xmark")
            .font(.system(size: 13, weight: .semibold))
            .foregroundColor(Color(ExpressColors.takeCloseIcon))
            .frame(width: 32, height: 32)
            .background(Circle().fill(Color(ExpressColors.takeCloseBackground)))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Close")
      }
      .padding(.top, 4)
      .padding(.bottom, 12)
      if showsTopic {
        ExpressTakeTopicCard(topic: take.topic, faces: take.faceImages)
          .padding(.bottom, 14)
          .transition(.opacity)
      }
    }
  }
}

/// The topic: its title and "N reactions · N emotes" over Android's ring artwork, with people
/// and emoji rising at the bottom right.
private struct ExpressTakeTopicCard: View {
  let topic: ExpressTakeTopic
  let faces: [UIImage]
  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  private static let rings: [UInt32] = [0xC7372A, 0xE0742A, 0xE8B923, 0xB9A640, 0x9AA0A6, 0xD38A86, 0xB24A2A]
  private static let ringStarts: [CGFloat] = [0.14, 0.24, 0.36, 0.46, 0.58, 0.70]

  var body: some View {
    ZStack(alignment: .bottomLeading) {
      Color(ExpressColors.takeTopicBackground)
      GeometryReader { proxy in
        let coverWidth = proxy.size.width * 0.62
        cover(size: CGSize(width: coverWidth, height: proxy.size.height))
          .frame(width: coverWidth, height: proxy.size.height)
          .frame(maxWidth: .infinity, alignment: .trailing)
        // Android's fade: the card colour across the left, into the cover.
        LinearGradient(
          stops: [
            .init(color: Color(ExpressColors.takeTopicBackground), location: 0),
            .init(color: Color(ExpressColors.takeTopicBackground), location: 0.38),
            .init(color: Color(ExpressColors.takeTopicBackground).opacity(209 / 255.0), location: 0.58),
            .init(color: .clear, location: 1),
          ],
          startPoint: .leading,
          endPoint: .trailing
        )
      }
      VStack(alignment: .leading, spacing: 6) {
        Text(topic.title)
          .font(.system(size: 26))
          .foregroundColor(Color(ExpressColors.takeTitle))
          .lineLimit(2)
        statsLine
      }
      .padding(.leading, 16)
      .padding(.trailing, 110)
      .padding(.bottom, 16)
      if !reduceMotion {
        ExpressTakeStream(faces: faces, emoji: topic.emoji)
          .frame(width: 70, height: 140)
          .padding(.trailing, 14)
          .padding(.bottom, 14)
          .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
          .allowsHitTesting(false)
      }
    }
    .frame(height: 150)
    .clipShape(RoundedRectangle(cornerRadius: 16))
    .accessibilityElement(children: .ignore)
    .accessibilityLabel("\(topic.title), \(Self.count(topic.reactions, "reaction")), \(Self.count(topic.emotes, "emote"))")
  }

  /// Hard-edged rings centred 70% across and 120% down, out to the farthest corner.
  private func cover(size: CGSize) -> some View {
    Canvas { context, canvasSize in
      let center = CGPoint(x: canvasSize.width * 0.7, y: canvasSize.height * 1.2)
      let radius = hypot(max(center.x, canvasSize.width - center.x), max(center.y, canvasSize.height - center.y))
      // Outermost first, so each inner ring paints over the one outside it.
      for index in stride(from: Self.rings.count - 1, through: 0, by: -1) {
        let outer = index == Self.rings.count - 1 ? 1 : Self.ringStarts[index]
        let r = radius * outer
        context.fill(
          Path(ellipseIn: CGRect(x: center.x - r, y: center.y - r, width: r * 2, height: r * 2)),
          with: .color(Color(UIColor(expressRGB: Self.rings[index])))
        )
      }
    }
  }

  private var statsLine: some View {
    HStack(spacing: 6) {
      countText(topic.reactions, "reaction")
      Circle().fill(Color(ExpressColors.takeStatsDot)).frame(width: 3, height: 3)
      countText(topic.emotes, "emote")
    }
    .font(.system(size: 12, weight: .medium))
    .foregroundColor(Color(ExpressColors.takeMeta))
    .lineLimit(1)
  }

  private func countText(_ count: Int, _ noun: String) -> Text {
    Text(ExpressNumberFormat.compact(count))
      .fontWeight(.semibold)
      .foregroundColor(Color(ExpressColors.takeStatsNumber))
      + Text(" \(noun)\(count == 1 ? "" : "s")")
  }

  private static func count(_ count: Int, _ noun: String) -> String {
    "\(count) \(noun)\(count == 1 ? "" : "s")"
  }
}

/// Android's `TakeStreamView`: seven items rising in turn from the bottom right, faces (with a
/// small emoji) and loose emoji, each over 2.7–3.2 s, 450 ms apart, starting 500 ms in.
private struct ExpressTakeStream: View {
  let faces: [UIImage]
  let emoji: [String]
  @State private var startedAt = Date()

  private static let items = 7
  private static let stagger = 0.45
  private static let period = Double(items) * stagger

  private var emojiSet: [String] { emoji.isEmpty ? ["🔥", "👏", "😮", "❤️"] : emoji }

  var body: some View {
    TimelineView(.animation) { context in
      let t = context.date.timeIntervalSince(startedAt) - 0.5
      GeometryReader { proxy in
        ZStack {
          ForEach(0..<Self.items, id: \.self) { i in
            item(i, time: t, size: proxy.size)
          }
        }
      }
    }
    .onAppear { startedAt = Date() }
  }

  @ViewBuilder private func item(_ i: Int, time t: Double, size: CGSize) -> some View {
    let local = t - Double(i) * Self.stagger
    let duration = 2.7 + Double((i * 173) % 501) / 1000
    let p = local < 0 ? 2 : local.truncatingRemainder(dividingBy: Self.period) / duration
    if p <= 1 {
      let alpha = p < 0.12 ? p / 0.12 : 1 - (p - 0.12) / 0.88
      let scale = p < 0.5 ? 0.6 + 0.4 * (p / 0.5) : 1 - 0.15 * ((p - 0.5) / 0.5)
      let x = size.width * (0.25 + 0.5 * Double((i * 37) % 7) / 6) + sin(.pi * p) * (i % 2 == 0 ? 6 : -6)
      let y = size.height - 14 - 112 * (1 - pow(1 - p, 3))
      let emoji = emojiSet[i % emojiSet.count]
      Group {
        if i % 2 == 0, !faces.isEmpty {
          let diameter = Double(20 + 4 * ((i / 2) % 2)) * scale
          Image(uiImage: faces[(i / 2) % faces.count])
            .resizable()
            .scaledToFill()
            .frame(width: diameter, height: diameter)
            .clipShape(Circle())
            .overlay(Circle().stroke(Color.white.opacity(0.9), lineWidth: 1.5))
            .overlay(alignment: .bottomTrailing) {
              Text(emoji).font(.system(size: 10 * scale)).offset(x: 4, y: 4)
            }
        } else {
          Text(emoji).font(.system(size: (14 + 4 * Double((i * 3) % 3) / 2) * scale))
        }
      }
      .opacity(alpha)
      .position(x: x, y: y)
    }
  }
}

// MARK: - Picker

/// "Add to your take": the six tiles where the keyboard was.
struct ExpressTakePicker: View {
  var onChoose: (ExpressAttachChoice) -> Void
  var onClose: () -> Void
  @Environment(\.colorScheme) private var colorScheme

  private static let titles: [ExpressAttachChoice: String] = [
    .photo: "Photos", .camera: "Camera", .video: "Video", .voice: "Voice note", .gif: "GIF", .document: "Document",
  ]

  var body: some View {
    VStack(alignment: .leading, spacing: 14) {
      HStack(alignment: .top) {
        VStack(alignment: .leading, spacing: 2) {
          Text("Add to your take")
            .font(.system(size: 16, weight: .semibold))
            .foregroundColor(Color(ExpressColors.takeTitle))
          Text("Choose what you want to share")
            .font(.system(size: 12))
            .foregroundColor(Color(ExpressColors.takeMeta))
        }
        Spacer()
        Button(action: onClose) {
          Image(systemName: "keyboard")
            .font(.system(size: 14, weight: .semibold))
            .foregroundColor(Color(ExpressColors.takeCloseIcon))
            .frame(width: 32, height: 32)
            .background(Circle().fill(Color(ExpressColors.takeCloseBackground)))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Back to the keyboard")
      }
      LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 3), spacing: 16) {
        ForEach(ExpressAttachChoice.allCases) { choice in
          Button {
            onChoose(choice)
          } label: {
            VStack(spacing: 8) {
              Circle()
                .fill(Color(UIColor(expressRGB: choice.tint)).opacity(colorScheme == .dark ? 0x38 / 255.0 : 0x24 / 255.0))
                .frame(width: 52, height: 52)
                .overlay(
                  Image(systemName: choice.symbol)
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundColor(Color(UIColor(expressRGB: choice.tint)))
                )
              Text(Self.titles[choice] ?? choice.title)
                .font(.system(size: 13))
                .foregroundColor(Color(ExpressColors.takeTitle))
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
          }
          .buttonStyle(.plain)
        }
      }
    }
    .padding(.top, 16)
    .frame(height: 300, alignment: .top)
  }
}
