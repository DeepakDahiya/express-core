// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import CoreText
import ExpressCore
import Foundation
import SwiftUI
import UIKit

// The reaction picker, ported from the Android app's `CommentReactionSheet` (Figma "Express /
// Emoji picker", 483:3250) and the recents and drawable-emoji check of its `EmojiCatalog`.

// MARK: - Colours (Android `browser_express_colors.xml`, light / dark)

extension ExpressColors {
  /// The sheet (`browser_express_comment_sheet`).
  static let reactionPickerSheet = UIColor(expressLight: 0xFFFFFF, expressDark: 0x232428)
  /// "Add a reaction" (`browser_express_comment_sheet_title`).
  static let reactionPickerTitle = UIColor(expressLight: 0x1F212E, expressDark: 0xF4F5F7)
  /// Section titles (`browser_express_comment_sheet_label`).
  static let reactionPickerLabel = UIColor(expressLight: 0x2D303A, expressDark: 0xF4F5F7)
  /// The quiet close cross (`browser_express_comment_picker_close`).
  static let reactionPickerClose = UIColor(expressLight: 0xB4B8C4, expressDark: 0x3A3D45)
  static let reactionPickerDivider = UIColor(expressLight: 0xE5E7EB, expressDark: 0x383838)
  /// A reaction chip (`browser_express_comment_picker_option` and its stroke).
  static let reactionPickerChip = UIColor(expressLight: 0xF5F7FA, expressDark: 0x232428)
  static let reactionPickerChipStroke = UIColor(expressLight: 0xE0E3EB, expressDark: 0x3A3D45)
  static let reactionPickerCount = UIColor(expressLight: 0x40454F, expressDark: 0xF4F5F7)
  /// The reader's own reaction chip (`browser_express_comment_picker_selected*`); its count
  /// colour also tints the selected section icon.
  static let reactionPickerMine = UIColor(expressLight: 0xE5F2FF, expressDark: 0x232428)
  static let reactionPickerMineStroke = UIColor(expressLight: 0x61A1F5, expressDark: 0x61A1F5)
  static let reactionPickerMineCount = UIColor(expressLight: 0x145CC7, expressDark: 0x6EA8FF)
  /// A section icon in the jump bar (`browser_express_comment_sheet_subtitle`), and the pill
  /// behind the selected one (`browser_express_comment_picker_tab_selected`).
  static let reactionPickerTab = UIColor(expressLight: 0x747480, expressDark: 0xA8ABB5)
  static let reactionPickerTabSelected = UIColor(expressLight: 0xE5F2FF, expressDark: 0x31343B)
}

// MARK: - Recents

/// The reader's latest reactions, the picker's Recents (Android's `EmojiCatalog.recents` and
/// `remember`): newest first, at most 24 (three rows of eight), and the classic six until the
/// reader has picked any. Kept in `UserDefaults`.
enum ExpressRecentReactions {
  static let key = "express.reactions.recents"
  static let limit = 24

  /// Newest first; the classic six when nothing has been picked yet.
  static var all: [String] {
    let saved = UserDefaults.standard.stringArray(forKey: key) ?? []
    return saved.isEmpty ? ExpressEmojiCatalog.classicReactions : saved
  }

  /// Puts `emoji` at the front, dropping an earlier copy (with or without U+FE0F) and anything
  /// past the limit.
  static func record(_ emoji: String) {
    guard !emoji.isEmpty else { return }
    var next = [emoji]
    for recent in all where next.count < limit && !ExpressReaction.same(recent, emoji) {
      next.append(recent)
    }
    UserDefaults.standard.set(next, forKey: key)
  }
}

// MARK: - Picker

/// Android's `CommentReactionSheet`: any emoji is a reaction. Under the title come the comment's
/// reactions as chips with their counts, the reader's own highlighted (the classic six, without
/// counts, when nobody has reacted yet); then Recents and every emoji section, Flags included,
/// eight to a row; and below, a bar of the sections' icons to jump between them, lighting up the
/// section at the top of the list.
///
/// Tapping an emoji, as a chip or in the grid, reports it through `onPick`, and the caller
/// dismisses the sheet. Tapping the reader's own reaction reports that same emoji, which the
/// caller's `react(to:with:)` treats as removing it (Android passes null there). A new reaction is
/// recorded in Recents before `onPick` runs; a removal is not.
///
/// Present it with `.presentationDetents([.medium, .large])`: the list scrolls and the section bar
/// stays at the bottom.
struct ExpressReactionPicker: View {
  let comment: ExpressComment
  let onPick: (String) -> Void  // the chosen emoji; the caller dismisses the sheet

  @Environment(\.dismiss) private var dismiss
  /// Recents as they were when the picker opened (Android reads them once, too).
  @State private var recents = ExpressRecentReactions.all
  /// The catalog's sections this device can draw; nil until checked.
  @State private var sections: [ExpressEmojiCatalog.PickerSection]?
  /// The section at the top of the list, lit up in the bar.
  @State private var selected = ExpressEmojiCatalog.recentsID
  /// The list's width, for spreading the grid's columns the way Android does.
  @State private var listWidth: CGFloat = 0

  private static let columnCount = 8
  /// The design's emoji cell and the distance of the first and last one from the sheet's edges.
  private static let cell: CGFloat = 32
  private static let edge: CGFloat = 20
  /// Android's 20dp emoji text, which draws the design's 24px emoji.
  private static let emojiSize: CGFloat = 20
  private static let rowHeight: CGFloat = 44
  private static let chipsID = "chips"
  private static let space = "expressReactionPicker"

  var body: some View {
    VStack(spacing: 0) {
      header
      ScrollViewReader { proxy in
        VStack(spacing: 0) {
          list
          Rectangle()
            .fill(Color(ExpressColors.reactionPickerDivider))
            .frame(height: 1)
          tabBar(proxy)
        }
      }
    }
    .background(Color(ExpressColors.reactionPickerSheet).ignoresSafeArea())
    .presentationBackground(Color(ExpressColors.reactionPickerSheet))
    .presentationDragIndicator(.visible)
    .task { await loadSections() }
  }

  // MARK: Header

  private var header: some View {
    HStack(spacing: 0) {
      Text("Add a reaction")
        .font(.system(size: 20))
        .foregroundColor(Color(ExpressColors.reactionPickerTitle))
        .lineLimit(1)
        .accessibilityAddTraits(.isHeader)
      Spacer(minLength: 8)
      Button {
        dismiss()
      } label: {
        // Android's `ic_browser_express_reaction_picker_close`: a thin cross in a 20pt box.
        Path { path in
          path.move(to: CGPoint(x: 5, y: 5))
          path.addLine(to: CGPoint(x: 15, y: 15))
          path.move(to: CGPoint(x: 15, y: 5))
          path.addLine(to: CGPoint(x: 5, y: 15))
        }
        .stroke(
          Color(ExpressColors.reactionPickerClose),
          style: StrokeStyle(lineWidth: 1.7, lineCap: .round)
        )
        .frame(width: 20, height: 20)
        .frame(width: 44, height: 44)
        .contentShape(Rectangle())
      }
      .buttonStyle(.plain)
      .accessibilityLabel("Close")
    }
    .frame(height: 44)
    .padding(.horizontal, 31)
    // Room for the sheet's grabber, as Android's handle and the 10dp under it.
    .padding(.top, 22)
  }

  // MARK: List

  private var list: some View {
    ScrollView {
      LazyVStack(alignment: .leading, spacing: 0) {
        ForEach(rows) { row in
          switch row {
          case .chips:
            chipsHeader
          case .section(let id, let title, let emoji):
            section(id: id, title: title, emoji: emoji)
          }
        }
      }
      .padding(.bottom, 8)
    }
    .coordinateSpace(.named(Self.space))
    .onPreferenceChange(ExpressReactionPickerSectionTops.self) { tops in
      updateSelection(tops)
    }
    .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width in
      listWidth = width
    }
  }

  /// The chips, then Recents and the sections this device can draw (Android's `PickerAdapter`).
  private var rows: [ExpressReactionPickerRow] {
    var rows: [ExpressReactionPickerRow] = [
      .chips,
      .section(
        id: ExpressEmojiCatalog.recentsID,
        title: ExpressEmojiCatalog.recentsTitle,
        emoji: recents
      ),
    ]
    for section in sections ?? [] {
      rows.append(.section(id: section.id, title: section.title, emoji: section.emoji))
    }
    return rows
  }

  /// The comment's reactions, most used first, or the classic six as quick picks when nobody has
  /// reacted yet (their count, zero, isn't shown).
  private var chips: [ExpressReaction] {
    if comment.reactions.isEmpty {
      return ExpressEmojiCatalog.classicReactions.map { ExpressReaction(emoji: $0, count: 0) }
    }
    return comment.reactions
  }

  /// The chips (Figma 483:3263) and the line under them.
  private var chipsHeader: some View {
    VStack(alignment: .leading, spacing: 0) {
      ExpressReactionChipFlow(minGap: 5, rowGap: 8) {
        ForEach(Array(chips.enumerated()), id: \.offset) { _, reaction in
          chip(reaction)
        }
      }
      .padding(.top, 16)
      Rectangle()
        .fill(Color(ExpressColors.reactionPickerDivider))
        .frame(height: 1)
        .padding(.top, 16)
    }
    .padding(.horizontal, Self.edge)
  }

  /// One of the comment's reactions (Figma 483:3264): the emoji and its count, highlighted when
  /// it is the reader's.
  private func chip(_ reaction: ExpressReaction) -> some View {
    let mine = isMine(reaction.emoji)
    let shape = RoundedRectangle(cornerRadius: 12, style: .continuous)
    return Button {
      pick(reaction.emoji)
    } label: {
      HStack(spacing: 4) {
        Text(reaction.emoji)
          .font(.system(size: Self.emojiSize))
          .frame(height: 24)
        if reaction.count > 0 {
          Text(String(reaction.count))
            .font(.system(size: 14, weight: .medium))
            .foregroundColor(
              Color(mine ? ExpressColors.reactionPickerMineCount : ExpressColors.reactionPickerCount)
            )
        }
      }
      .padding(.horizontal, 10)
      .frame(height: 36)
      .background(
        shape.fill(Color(mine ? ExpressColors.reactionPickerMine : ExpressColors.reactionPickerChip))
      )
      .overlay(
        shape.strokeBorder(
          Color(mine ? ExpressColors.reactionPickerMineStroke : ExpressColors.reactionPickerChipStroke),
          lineWidth: 1
        )
      )
      .contentShape(shape)
    }
    .buttonStyle(.plain)
    .accessibilityLabel(
      (reaction.count > 0 ? "\(reaction.emoji) \(reaction.count)" : reaction.emoji)
        + (mine ? ", your reaction" : "")
    )
    .accessibilityHint(mine ? "Removes your reaction" : "")
    .accessibilityAddTraits(mine ? .isSelected : [])
  }

  /// A section's title over its emoji, eight to a row. It reports where its top is, so the bar
  /// can light up the section at the top of the list.
  private func section(id: String, title: String, emoji: [String]) -> some View {
    VStack(alignment: .leading, spacing: 0) {
      // The cells below add their own space, making the design's 8pt gap.
      Text(title)
        .font(.system(size: 12, weight: .medium))
        .foregroundColor(Color(ExpressColors.reactionPickerLabel))
        .lineLimit(1)
        .padding(.horizontal, Self.edge)
        .padding(.top, 20)
        .padding(.bottom, 2)
        .accessibilityAddTraits(.isHeader)
      LazyVGrid(
        columns: Array(repeating: GridItem(.flexible(), spacing: 0), count: Self.columnCount),
        spacing: 0
      ) {
        ForEach(emoji, id: \.self) { one in
          cell(one)
        }
      }
      .padding(.horizontal, gridInset)
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .background(
      GeometryReader { geometry in
        Color.clear.preference(
          key: ExpressReactionPickerSectionTops.self,
          value: [id: geometry.frame(in: .named(Self.space)).minY]
        )
      }
    )
  }

  /// One emoji in the grid; the whole row height is its touch target.
  private func cell(_ emoji: String) -> some View {
    let mine = isMine(emoji)
    return Button {
      pick(emoji)
    } label: {
      Text(emoji)
        .font(.system(size: Self.emojiSize))
        .frame(maxWidth: .infinity)
        .frame(height: Self.rowHeight)
        .contentShape(Rectangle())
    }
    .buttonStyle(ExpressReactionCellStyle())
    .accessibilityLabel(mine ? "\(emoji), your reaction" : emoji)
    .accessibilityHint(mine ? "Removes your reaction" : "")
  }

  /// Android's `spreadColumns`: eight equal columns, the first and last 32pt cells 20pt from the
  /// sheet's edges, so the grid lines up with the titles and chips.
  private var gridInset: CGFloat {
    guard listWidth > 0 else { return 14 }
    let columns = CGFloat(Self.columnCount)
    let inset = (Self.edge + Self.cell / 2 - listWidth / (2 * columns)) * columns / (columns - 1)
    return max(0, inset)
  }

  // MARK: Section bar

  private struct SectionTab: Identifiable {
    let id: String
    let title: String
    let icon: String
  }

  /// Recents, then each section this device can draw (Android hides the others' tabs).
  private var tabs: [SectionTab] {
    var tabs = [
      SectionTab(
        id: ExpressEmojiCatalog.recentsID,
        title: ExpressEmojiCatalog.recentsTitle,
        icon: ExpressEmojiCatalog.recentsIcon
      )
    ]
    for section in sections ?? [] {
      tabs.append(SectionTab(id: section.id, title: section.title, icon: section.icon))
    }
    return tabs
  }

  private func tabBar(_ proxy: ScrollViewProxy) -> some View {
    HStack(spacing: 0) {
      ForEach(tabs) { tab in
        let isSelected = tab.id == selected
        Button {
          jump(to: tab.id, proxy: proxy)
        } label: {
          Image(systemName: tab.icon)
            .font(.system(size: 17))
            .foregroundColor(
              Color(isSelected ? ExpressColors.reactionPickerMineCount : ExpressColors.reactionPickerTab)
            )
            .frame(maxWidth: .infinity)
            .frame(height: 36)
            .background(
              RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(isSelected ? Color(ExpressColors.reactionPickerTabSelected) : Color.clear)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 2)
        .accessibilityLabel(tab.title)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
      }
    }
    .padding(.horizontal, 12)
    .padding(.vertical, 6)
  }

  /// Brings a section's title to the top of the list at once, as WhatsApp does. Recents goes to
  /// the very top, so the comment's own reactions show with it.
  private func jump(to id: String, proxy: ScrollViewProxy) {
    selected = id
    proxy.scrollTo(id == ExpressEmojiCatalog.recentsID ? Self.chipsID : id, anchor: .top)
  }

  /// The section whose block covers the top of the list; the chips above them all count as
  /// Recents.
  private func updateSelection(_ tops: [String: CGFloat]) {
    let top =
      tops.filter { $0.value <= 1 }.max { $0.value < $1.value }?.key
      ?? ExpressEmojiCatalog.recentsID
    if top != selected { selected = top }
  }

  // MARK: Picking

  private func isMine(_ emoji: String) -> Bool {
    comment.myReaction.map { ExpressReaction.same($0, emoji) } ?? false
  }

  /// The reader's own reaction is a toggle wherever they tap it: the caller removes it, and a
  /// removal isn't recorded as a recent pick (Android's `pick`).
  private func pick(_ emoji: String) {
    if !isMine(emoji) { ExpressRecentReactions.record(emoji) }
    onPick(emoji)
  }

  /// Finds which emoji this device can draw off the main thread, once per launch (Android's
  /// `EmojiCatalog.load`); Recents and the chips show meanwhile.
  private func loadSections() async {
    guard sections == nil else { return }
    let drawable = await Task.detached(priority: .userInitiated) {
      ExpressEmojiCatalog.drawablePickerSections
    }.value
    sections = drawable
  }
}

// MARK: - Pieces

private enum ExpressReactionPickerRow: Identifiable {
  case chips
  case section(id: String, title: String, emoji: [String])

  var id: String {
    switch self {
    case .chips: return "chips"
    case .section(let id, _, _): return id
    }
  }
}

/// Where each section on screen starts, in the list's coordinates.
private struct ExpressReactionPickerSectionTops: PreferenceKey {
  static var defaultValue: [String: CGFloat] { [:] }

  static func reduce(value: inout [String: CGFloat], nextValue: () -> [String: CGFloat]) {
    value.merge(nextValue()) { $1 }
  }
}

/// A soft round highlight behind an emoji while it's pressed (Android's borderless ripple).
private struct ExpressReactionCellStyle: ButtonStyle {
  func makeBody(configuration: Configuration) -> some View {
    configuration.label
      .background(
        Circle()
          .fill(Color(ExpressColors.reactionPickerTabSelected))
          .frame(width: 40, height: 40)
          .opacity(configuration.isPressed ? 1 : 0)
      )
  }
}

/// Android's `ReactionChipFlowLayout`: chips fill rows at least 5pt apart; every full row spreads
/// its chips edge to edge, and the last row keeps them 5pt apart from the leading edge.
private struct ExpressReactionChipFlow: Layout {
  let minGap: CGFloat
  let rowGap: CGFloat

  private struct Row {
    var indices: [Int] = []
    var widths: CGFloat = 0
    var height: CGFloat = 0
  }

  func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
    let available = proposal.width ?? .infinity
    let sizes = subviews.map { $0.sizeThatFits(.unspecified) }
    let rows = makeRows(sizes: sizes, available: available)
    let height =
      rows.reduce(0) { $0 + $1.height } + CGFloat(max(0, rows.count - 1)) * rowGap
    let widest = rows.map { $0.widths + CGFloat(max(0, $0.indices.count - 1)) * minGap }.max() ?? 0
    return CGSize(width: proposal.width ?? widest, height: height)
  }

  func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
    let sizes = subviews.map { $0.sizeThatFits(.unspecified) }
    let rows = makeRows(sizes: sizes, available: bounds.width)
    var y = bounds.minY
    for (number, row) in rows.enumerated() {
      let isLast = number == rows.count - 1
      let gap =
        !isLast && row.indices.count > 1
        ? (bounds.width - row.widths) / CGFloat(row.indices.count - 1)
        : minGap
      var x = bounds.minX
      for index in row.indices {
        let size = sizes[index]
        subviews[index].place(
          at: CGPoint(x: x, y: y + (row.height - size.height) / 2),
          proposal: ProposedViewSize(size)
        )
        x += size.width + gap
      }
      y += row.height + rowGap
    }
  }

  private func makeRows(sizes: [CGSize], available: CGFloat) -> [Row] {
    var rows: [Row] = []
    var current = Row()
    for (index, size) in sizes.enumerated() {
      let width = min(size.width, available)
      if !current.indices.isEmpty,
        current.widths + CGFloat(current.indices.count) * minGap + width > available
      {
        rows.append(current)
        current = Row()
      }
      current.indices.append(index)
      current.widths += width
      current.height = max(current.height, size.height)
    }
    if !current.indices.isEmpty { rows.append(current) }
    return rows
  }
}

// MARK: - Drawable emoji

extension ExpressEmojiCatalog {
  /// `pickerSections` without the emoji this device can't draw as one glyph (Android's
  /// `EmojiCatalog.load`), so an older iOS shows neither tofu nor a newer emoji in pieces.
  /// Sections left empty are dropped. Built once, on first use; slow enough (a CoreText line per
  /// emoji) to keep off the main thread.
  static let drawablePickerSections: [PickerSection] = ExpressEmojiGlyphs.drawable(pickerSections)
}

private enum ExpressEmojiGlyphs {
  static func drawable(
    _ sections: [ExpressEmojiCatalog.PickerSection]
  ) -> [ExpressEmojiCatalog.PickerSection] {
    let font = CTFontCreateWithName("AppleColorEmoji" as CFString, 20, nil)
    guard isEmojiFont(font) else { return sections }
    var total = 0
    var kept = 0
    let checked = sections.map { section -> ExpressEmojiCatalog.PickerSection in
      let emoji = section.emoji.filter { canDraw($0, with: font) }
      total += section.emoji.count
      kept += emoji.count
      return ExpressEmojiCatalog.PickerSection(
        id: section.id,
        title: section.title,
        icon: section.icon,
        emoji: emoji
      )
    }
    // Every iOS this app supports draws all but the newest few dozen. A check that turns down
    // much more than that is wrong about the font, not the emoji: show everything instead.
    guard kept * 5 >= total * 4 else { return sections }
    return checked.filter { !$0.emoji.isEmpty }
  }

  private static func isEmojiFont(_ font: CTFont) -> Bool {
    (CTFontCopyPostScriptName(font) as String).contains("AppleColorEmoji")
  }

  /// True when `emoji` shapes into exactly one visible glyph from the emoji font. A sequence the
  /// font lacks comes out as its parts (several glyphs), and an emoji it lacks comes out as
  /// `.notdef` or from a fallback font. Zero-width glyphs (joiners, variation selectors, tags)
  /// don't count.
  private static func canDraw(_ emoji: String, with font: CTFont) -> Bool {
    let text = NSAttributedString(
      string: emoji,
      attributes: [NSAttributedString.Key(kCTFontAttributeName as String): font]
    )
    let line = CTLineCreateWithAttributedString(text as CFAttributedString)
    guard let runs = CTLineGetGlyphRuns(line) as? [CTRun] else { return false }
    var visible = 0
    for run in runs {
      let count = CTRunGetGlyphCount(run)
      guard count > 0 else { continue }
      var glyphs = [CGGlyph](repeating: 0, count: count)
      var advances = [CGSize](repeating: .zero, count: count)
      CTRunGetGlyphs(run, CFRange(location: 0, length: 0), &glyphs)
      CTRunGetAdvances(run, CFRange(location: 0, length: 0), &advances)
      let attributes = CTRunGetAttributes(run) as NSDictionary
      let runFont = attributes[kCTFontAttributeName as String].map { $0 as! CTFont }
      for index in 0..<count where advances[index].width > 0 {
        guard glyphs[index] != 0, let runFont, isEmojiFont(runFont) else { return false }
        visible += 1
      }
    }
    return visible == 1
  }
}
