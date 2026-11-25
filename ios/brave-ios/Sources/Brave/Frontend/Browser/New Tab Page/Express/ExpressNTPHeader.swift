// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import AVFoundation
import BraveUI
import ExpressCore
import Favicon
import SwiftUI
import UIKit

// The new tab page header from Android's `ntp_header.xml`: logo, search box (with voice search),
// a card of four sites with YouTube first, and the optional tutorial video.

extension ExpressColors {
  static let ntpSitesCard = UIColor(expressLight: 0xF9F8FE, expressDark: 0x1E1F24)
  static let ntpSiteCircle = UIColor(expressLight: 0xEEEDF3, expressDark: 0x292A2F)
  static let ntpSearchField = UIColor(expressLight: 0xD0D3E2, expressDark: 0x313747)
  static let ntpSearchText = UIColor(expressLight: 0x1A1B20, expressDark: 0xE2E3E8)
}

/// One tile on the new tab page.
struct ExpressNTPSite: Codable, Hashable, Identifiable {
  let url: URL
  let name: String
  var id: URL { url }
}

/// Android's top sites (`DatabaseHelper.getTopSitesForDisplay`): YouTube always first, then the
/// sites most recently opened (by first visit, newest first), one per host.
enum ExpressNTPSites {
  private static let key = "express.ntp.recent_sites"
  private static let maxStored = 12

  /// Android's YouTube tile goes through Google sign-in, so YouTube opens signed in.
  static let youTube = ExpressNTPSite(
    url: URL(
      string:
        "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fm.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Dm%26hl%3Den-GB%26next%3D%252F&hl=en-GB"
    )!,
    name: "YouTube"
  )

  static var stored: [ExpressNTPSite] {
    guard let data = UserDefaults.standard.data(forKey: key),
      let sites = try? JSONDecoder().decode([ExpressNTPSite].self, from: data)
    else { return [] }
    return sites
  }

  /// YouTube and the three newest sites.
  static var display: [ExpressNTPSite] {
    [youTube] + stored.prefix(3)
  }

  /// A page started loading in a regular tab. New hosts go to the front; known ones keep their
  /// place (Android orders by when a host was first added). YouTube links aren't added.
  static func record(_ url: URL?) {
    guard let url, url.scheme == "http" || url.scheme == "https",
      let host = url.host?.lowercased(), !host.isEmpty,
      !host.contains("youtube.com"), !host.contains("youtu.be"), !host.hasSuffix("accounts.google.com"),
      let origin = URL(string: "\(url.scheme!)://\(host)")
    else { return }
    var sites = stored
    guard !sites.contains(where: { $0.url == origin }) else { return }
    sites.insert(ExpressNTPSite(url: origin, name: name(for: host)), at: 0)
    if let data = try? JSONEncoder().encode(Array(sites.prefix(maxStored))) {
      UserDefaults.standard.set(data, forKey: key)
    }
  }

  /// Android names a site by its second-to-last host label ("www.google.com" → "google").
  static func name(for host: String) -> String {
    let labels = host.split(separator: ".")
    let name = labels.count >= 2 ? String(labels[labels.count - 2]) : host
    return name.count > 30 ? String(name.prefix(27)) + "..." : name
  }
}

struct ExpressNTPHeaderView: View {
  var sites: [ExpressNTPSite]
  var tutorial: ExpressTutorialVideo?
  var showsVoiceSearch: Bool
  var onSearch: () -> Void
  var onVoiceSearch: () -> Void
  var onOpenSite: (URL) -> Void
  var onTutorialTap: () -> Void
  var onDismissTutorial: () -> Void

  var body: some View {
    VStack(spacing: 0) {
      Image("express-ntp-logo", bundle: .module)
        .resizable()
        .scaledToFit()
        .frame(width: 200, height: 54)
        .padding(.top, 20)
        .accessibilityLabel("Express")
      searchBox
        .expressNTPSearchBox()
        .padding(.top, 24)
      if !sites.isEmpty {
        sitesCard
          .padding(.top, 32)
      }
      if let tutorial {
        ExpressTutorialCard(video: tutorial, onTap: onTutorialTap, onDismiss: onDismissTutorial)
          .padding(.top, 8)
          .padding(.bottom, 8)
      }
    }
    .frame(maxWidth: .infinity)
  }

  private var searchBox: some View {
    HStack(spacing: 0) {
      Button(action: onSearch) {
        HStack(spacing: 20) {
          Image("express-ntp-google-g", bundle: .module)
            .resizable()
            .scaledToFit()
            .frame(width: 23, height: 24)
            .accessibilityHidden(true)
          Text("Search Google or type url")
            .font(.system(size: 18, weight: .medium))
            .foregroundColor(Color(ExpressColors.ntpSearchText))
            .lineLimit(1)
          Spacer(minLength: 0)
        }
        .frame(maxHeight: .infinity)
        .contentShape(Rectangle())
      }
      .buttonStyle(.plain)
      .accessibilityHint("Opens the address bar")
      if showsVoiceSearch {
        Button(action: onVoiceSearch) {
          Image(systemName: "mic.fill")
            .font(.system(size: 20))
            .foregroundColor(Color(ExpressColors.ntpSearchText))
            .frame(width: 40, height: 40)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Voice search")
      }
    }
    .padding(.leading, 20)
    .padding(.trailing, 12)
    .frame(height: 72)
    .background(Capsule().fill(Color(ExpressColors.ntpSearchField)))
  }

  private var sitesCard: some View {
    HStack(spacing: 0) {
      ForEach(Array(sites.enumerated()), id: \.element.id) { index, site in
        if index > 0 { Spacer(minLength: 0) }
        Button {
          onOpenSite(site.url)
        } label: {
          VStack(spacing: 8) {
            Circle()
              .fill(Color(ExpressColors.ntpSiteCircle))
              .frame(width: 48, height: 48)
              .overlay { siteIcon(site).frame(width: 32, height: 32).clipShape(Circle()) }
            Text(site.name)
              .font(.system(size: 13, weight: .medium))
              .tracking(-0.26)
              .foregroundColor(Color(white: 0x80 / 255.0))
              .lineLimit(1)
              .frame(width: 65)
          }
          .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(site.name)
      }
    }
    .padding(.horizontal, 20)
    .padding(.vertical, 12)
    .frame(height: 108)
    .background(RoundedRectangle(cornerRadius: 28).fill(Color(ExpressColors.ntpSitesCard)))
  }

  @ViewBuilder private func siteIcon(_ site: ExpressNTPSite) -> some View {
    if site == ExpressNTPSites.youTube {
      Image("express-golden-youtube", bundle: .module).resizable().scaledToFill()
    } else {
      FaviconImage(url: site.url, isPrivateBrowsing: false)
    }
  }

  /// The header's height for the layout: logo, search, sites, and the tutorial when shown.
  static func height(hasSites: Bool, hasTutorial: Bool) -> CGFloat {
    var height: CGFloat = 20 + 54 + 24 + 72
    if hasSites { height += 32 + 108 }
    if hasTutorial { height += 8 + 200 + 8 }
    return height + 8
  }
}

/// Android's tutorial card (`ntp_tutorial_video.xml`): the video muted and looping inline (or the
/// image), a close button, and a tap that goes to YouTube sign-in. Closing lasts until the next
/// new tab page, as on Android.
private struct ExpressTutorialCard: View {
  let video: ExpressTutorialVideo
  var onTap: () -> Void
  var onDismiss: () -> Void

  var body: some View {
    ZStack(alignment: .topTrailing) {
      Group {
        if let url = video.videoURL {
          ExpressTutorialVideoView(url: url)
        } else if let image = video.imageURL {
          ExpressAnimatedImage(url: image)
        }
      }
      .frame(maxWidth: .infinity)
      .frame(height: 200)
      .clipped()
      .contentShape(Rectangle())
      .onTapGesture(perform: onTap)
      Button(action: onDismiss) {
        Image(systemName: "xmark")
          .font(.system(size: 12, weight: .bold))
          .foregroundColor(.white)
          .frame(width: 28, height: 28)
          .background(Circle().fill(Color.black.opacity(0.45)))
      }
      .buttonStyle(.plain)
      .padding(8)
      .accessibilityLabel("Dismiss")
    }
    .background(Color(UIColor(expressRGB: 0x2A2A2E)))
    .clipShape(RoundedRectangle(cornerRadius: 12))
    .shadow(color: .black.opacity(0.18), radius: 4, y: 2)
  }
}

/// The tutorial video, muted and looping. Like Android's header video (`PostListAdapter
/// .updatePlayingVideo`), it's one candidate among the feed's videos: it plays only when it's the
/// most visible video, so never alongside a playing feed video, and never while the comments
/// sheet covers the page, the page is hidden or the app is in the background. It reports its
/// box's place on screen the way the cards do.
private struct ExpressTutorialVideoView: View {
  let url: URL
  @StateObject private var player = ExpressTutorialPlayer()

  var body: some View {
    ExpressLoopingVideo(url: url, player: player)
      .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { frame in
        player.frameChanged(frame)
      }
  }
}

/// The tutorial's own player, started and paused by the feed's `ExpressFeedVideoPlayback`.
@MainActor
private final class ExpressTutorialPlayer: ObservableObject {
  let player = AVQueuePlayer()
  private var looper: AVPlayerLooper?
  private var url: URL?
  /// One candidate per card: an old new tab page and its replacement can overlap for a moment.
  private let candidateID = "ntp-tutorial-\(UUID().uuidString)"
  private weak var playback: ExpressFeedVideoPlayback?
  private var frame: CGRect = .zero

  init() {
    player.isMuted = true
  }

  func load(_ url: URL) {
    guard url != self.url else { return }
    self.url = url
    looper?.disableLooping()
    looper = AVPlayerLooper(
      player: player,
      templateItem: AVPlayerItem(asset: ExpressMediaCache.shared.asset(for: url))
    )
  }

  /// The video joined a window (`view`) or left it (nil).
  func attach(_ view: UIView?) {
    guard let view else {
      playback?.removeExternal(candidateID)
      playback = nil
      player.pause()
      return
    }
    guard let found = Self.feedPlayback(above: view) else {
      // Not inside the browser (not expected): play while on screen, as before.
      player.play()
      return
    }
    if let playback, playback !== found {
      playback.removeExternal(candidateID)
    }
    playback = found
    report()
  }

  func frameChanged(_ frame: CGRect) {
    self.frame = frame
    report()
  }

  private func report() {
    guard let playback, !frame.isEmpty else { return }
    playback.reportExternal(candidateID, frame: frame) { [weak self] playing in
      guard let self else { return }
      if playing { self.player.play() } else { self.player.pause() }
    }
  }

  /// The feed's player for the window the video is in: the new tab page is a child of the
  /// browser, whose feed data source owns it.
  private static func feedPlayback(above view: UIView) -> ExpressFeedVideoPlayback? {
    var responder: UIResponder? = view
    while let current = responder {
      if let browser = current as? BrowserViewController {
        return browser.expressFeedDataSource.videos
      }
      responder = current.next
    }
    return nil
  }
}

/// Shows the tutorial's player; joining or leaving a window registers or removes the video.
private struct ExpressLoopingVideo: UIViewRepresentable {
  let url: URL
  let player: ExpressTutorialPlayer

  func makeUIView(context: Context) -> LoopingView {
    player.load(url)
    return LoopingView(player: player)
  }

  func updateUIView(_ view: LoopingView, context: Context) {
    player.load(url)
  }

  final class LoopingView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    private let player: ExpressTutorialPlayer

    init(player: ExpressTutorialPlayer) {
      self.player = player
      super.init(frame: .zero)
      isUserInteractionEnabled = false
      (layer as! AVPlayerLayer).player = player.player
      (layer as! AVPlayerLayer).videoGravity = .resizeAspectFill
    }

    required init?(coder: NSCoder) { fatalError() }

    override func didMoveToWindow() {
      super.didMoveToWindow()
      player.attach(window != nil ? self : nil)
    }
  }
}
