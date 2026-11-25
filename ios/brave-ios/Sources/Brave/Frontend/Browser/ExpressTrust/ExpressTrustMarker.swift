// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import SwiftUI
import UIKit

// MARK: - Status

/// A site's trust status for the URL bar's marker and the site sheet (Android's `SiteTrust`,
/// Figma "Trust Markers": green, amber, red). It comes from the connection alone, never from
/// the reader's ad-blocking choice: a site with ads allowed is exactly as secure.
///
/// - Secure: HTTPS with a valid certificate.
/// - Dangerous: a broken HTTPS connection (an invalid certificate).
/// - Warning: every other web page: plain HTTP, mixed content.
enum ExpressTrustStatus: Equatable {
  case secure, warning, dangerous

  /// The accessible name, so the meaning never rests on colour alone.
  var label: String {
    switch self {
    case .secure: return "Connection is secure"
    case .warning: return "Connection is not secure"
    case .dangerous: return "This site may be dangerous"
    }
  }

  /// The disc behind the sheet's status glyph.
  var discColor: Color {
    switch self {
    case .secure: return Color(expressARGB: 0xFF21_B948)
    case .warning: return Color(expressARGB: 0xFFB9_7A12)
    case .dangerous: return Color(expressARGB: 0xFFD6_3031)
    }
  }
}

// MARK: - Animation state

/// Drives one URL bar's marker (Android's `TrustMarkerDrawable`). While a page loads it loops
/// the design's scan: trackers, ads, cookies, then fingerprinting, each swept by a scan line.
/// When the load settles the result plays once: a tick, clock or cross pops and the shield
/// lands; a secure shield gets one shine, a dangerous one shakes, a not-secure one pulses. The
/// timings are the design's 6.5 s timeline, split where the result begins. With Reduce Motion
/// on it shows the resting shield only.
@MainActor
final class ExpressTrustMarkerModel: ObservableObject {
  /// The whole timeline, in seconds.
  static let timeline: Double = 6.5
  /// Where the result starts (the tick popping in), as a fraction of the timeline.
  static let resultStart: Double = 0.56923
  /// Where the scan ends: the fingerprint glyph has gone.
  static let scanEnd: Double = 0.61538

  enum Phase: Equatable {
    case resting
    case scanning(since: Date)
    case result(since: Date)
  }

  @Published private(set) var status: ExpressTrustStatus = .warning
  @Published private(set) var phase: Phase = .resting
  private var settleTask: Task<Void, Never>?

  var isScanning: Bool {
    if case .scanning = phase { return true }
    return false
  }

  var isAnimating: Bool { phase != .resting }

  private var animationsEnabled: Bool { !UIAccessibility.isReduceMotionEnabled }

  /// The shield at rest for `status`, with no animation.
  func setStatus(_ status: ExpressTrustStatus) {
    settleTask?.cancel()
    self.status = status
    phase = .resting
  }

  /// The status of a page still loading; the scan goes on and the result plays when it settles.
  func setPendingStatus(_ status: ExpressTrustStatus) {
    self.status = status
  }

  /// Starts (or keeps) the scan loop: a page is loading.
  func startScan() {
    guard !isScanning, animationsEnabled else { return }
    settleTask?.cancel()
    phase = .scanning(since: Date())
  }

  /// The load settled on `status`: the result plays once, ending on the shield.
  func showResult(_ status: ExpressTrustStatus) {
    settleTask?.cancel()
    self.status = status
    guard animationsEnabled else {
      phase = .resting
      return
    }
    phase = .result(since: Date())
    let duration = Self.timeline * (1 - Self.resultStart)
    settleTask = Task { [weak self] in
      try? await Task.sleep(for: .seconds(duration))
      guard !Task.isCancelled, let self, case .result = self.phase else { return }
      self.phase = .resting
    }
  }

  /// Progress on the design's timeline, in percent; 100 is the resting shield.
  func time(at date: Date) -> Double {
    switch phase {
    case .resting:
      return 100
    case .scanning(let since):
      let loop = Self.timeline * Self.scanEnd
      let elapsed = date.timeIntervalSince(since).truncatingRemainder(dividingBy: loop)
      return max(0, elapsed) / Self.timeline * 100
    case .result(let since):
      let elapsed = max(0, date.timeIntervalSince(since))
      return min(100, Self.resultStart * 100 + elapsed / Self.timeline * 100)
    }
  }
}

// MARK: - Drawing

/// The 24 pt marker. Glyphs are the Android vector drawables, drawn in their 24×24 viewport.
struct ExpressTrustMarkerView: View {
  @ObservedObject var model: ExpressTrustMarkerModel
  var size: CGFloat = 24

  var body: some View {
    TimelineView(.animation(paused: !model.isAnimating)) { context in
      let t = model.time(at: context.date)
      let status = model.status
      Canvas { graphics, canvasSize in
        ExpressTrustMarkerRenderer.draw(
          in: graphics,
          size: min(canvasSize.width, canvasSize.height),
          t: t,
          status: status
        )
      }
    }
    .frame(width: size, height: size)
    .accessibilityElement()
    .accessibilityLabel(model.status.label)
  }
}

/// A shield or result glyph on its own, at rest (for the sheet and previews).
struct ExpressTrustGlyphView: View {
  let glyph: ExpressTrustGlyph
  var size: CGFloat = 24

  var body: some View {
    Canvas { graphics, canvasSize in
      ExpressTrustMarkerRenderer.drawLayer(
        graphics,
        glyph,
        size: min(canvasSize.width, canvasSize.height),
        alpha: 1,
        scale: 1,
        dx: 0,
        dy: 0
      )
    }
    .frame(width: size, height: size)
  }
}

enum ExpressTrustMarkerRenderer {
  static func draw(in graphics: GraphicsContext, size: CGFloat, t: Double, status: ExpressTrustStatus) {
    guard size > 0 else { return }
    var graphics = graphics
    graphics.clip(to: Path(CGRect(x: 0, y: 0, width: size, height: size)))
    let unit = size / 24

    // The four scan glyphs, one after another, each swept by the scan line.
    for (i, glyph) in ExpressTrustGlyph.scanGlyphs.enumerated() {
      let start = 15.385 * Double(i)
      let alpha = glyphAlpha(t, start)
      if alpha > 0 {
        drawLayer(graphics, glyph, size: size, alpha: alpha, scale: glyphScale(t, start), dx: 0, dy: 0)
      }
      let scanStart = 2.308 + 15.385 * Double(i)
      let scanAlpha = scanAlpha(t, scanStart)
      if scanAlpha > 0 {
        let y: CGFloat = lerp(-9.6, 9.6, easeInOut(progress(t, scanStart, scanStart + 8.461)))
        drawLayer(graphics, .scanFrame, size: size, alpha: scanAlpha, scale: 1, dx: 0, dy: y * unit)
      }
    }

    // The result glyph pops, then the shield lands.
    let resultAlpha = resultAlpha(t)
    if resultAlpha > 0 {
      drawLayer(graphics, .result(status), size: size, alpha: resultAlpha, scale: resultScale(t), dx: 0, dy: 0)
    }
    let shieldAlpha = shieldAlpha(t, status)
    if shieldAlpha > 0 {
      let shake = status == .dangerous ? shakeX(t) * unit : 0
      drawLayer(graphics, .shield(status), size: size, alpha: shieldAlpha, scale: shieldScale(t), dx: shake, dy: 0)
    }
    if status == .secure {
      let shine = shimmerAlpha(t)
      if shine > 0 {
        let x: CGFloat = lerp(-24, 24, easeInOut(progress(t, 80, 95.385)))
        drawLayer(graphics, .shimmer, size: size, alpha: shine, scale: 1, dx: x * unit, dy: 0)
      }
    }
  }

  static func drawLayer(
    _ graphics: GraphicsContext,
    _ glyph: ExpressTrustGlyph,
    size: CGFloat,
    alpha: Double,
    scale: CGFloat,
    dx: CGFloat,
    dy: CGFloat
  ) {
    var context = graphics
    context.opacity = max(0, min(1, alpha))
    context.translateBy(x: dx + size / 2, y: dy + size / 2)
    context.scaleBy(x: scale, y: scale)
    context.translateBy(x: -size / 2, y: -size / 2)
    let unit = size / 24
    context.scaleBy(x: unit, y: unit)
    for layer in glyph.layers {
      switch layer.paint {
      case .fill(let color):
        context.fill(layer.path, with: .color(color))
      case .stroke(let color, let width, let cap, let join):
        context.stroke(
          layer.path,
          with: .color(color),
          style: StrokeStyle(lineWidth: width, lineCap: cap, lineJoin: join)
        )
      case .shine:
        context.fill(
          layer.path,
          with: .linearGradient(
            Gradient(stops: [
              .init(color: .white.opacity(0), location: 0),
              .init(color: .white.opacity(0), location: 0.4),
              .init(color: .white.opacity(0.6), location: 0.5),
              .init(color: .white.opacity(0), location: 0.6),
              .init(color: .white.opacity(0), location: 1),
            ]),
            startPoint: CGPoint(x: 4, y: 2),
            endPoint: CGPoint(x: 24.2468, y: 17.4261)
          )
        )
      }
    }
  }

  // MARK: The design's keyframe tracks, in percent of the 6.5 s timeline.

  private static func glyphAlpha(_ t: Double, _ start: Double) -> Double {
    let fadeIn = start + 0.769, holdEnd = start + 13.077, fadeOut = start + 15.385
    if t < start || t > fadeOut { return 0 }
    if t < fadeIn { return easeInOut(progress(t, start, fadeIn)) }
    if t < holdEnd { return 1 }
    return 1 - easeInOut(progress(t, holdEnd, fadeOut))
  }

  private static func glyphScale(_ t: Double, _ start: Double) -> CGFloat {
    let grown = start + 1.846, shrinkStart = start + 13.846, shrunk = start + 15.385
    if t < grown { return lerp(0.6, 1, overshoot(progress(t, start, grown))) }
    if t < shrinkStart { return 1 }
    return lerp(1, 0.6, easeIn(progress(t, shrinkStart, shrunk)))
  }

  private static func scanAlpha(_ t: Double, _ start: Double) -> Double {
    let fadeIn = start + 0.769, holdEnd = start + 7.692, fadeOut = start + 8.461
    if t < start || t > fadeOut { return 0 }
    if t < fadeIn { return 0.8 * progress(t, start, fadeIn) }
    if t < holdEnd { return 0.8 }
    return 0.8 * (1 - easeOut(progress(t, holdEnd, fadeOut)))
  }

  private static func resultAlpha(_ t: Double) -> Double {
    if t < 56.923 || t > 69.231 { return 0 }
    if t < 58.462 { return easeOut(progress(t, 56.923, 58.462)) }
    if t < 66.154 { return 1 }
    return 1 - easeIn(progress(t, 66.154, 69.231))
  }

  private static func resultScale(_ t: Double) -> CGFloat {
    if t < 60 { return lerp(0.5, 1.1, overshoot(progress(t, 56.923, 60))) }
    return lerp(1.1, 1, easeOut(progress(t, 60, 63.077)))
  }

  private static func shieldAlpha(_ t: Double, _ status: ExpressTrustStatus) -> Double {
    if t < 69.231 { return 0 }
    if status == .warning {
      // Two pulses before settling.
      if t < 72.308 { return easeIn(progress(t, 69.231, 72.308)) }
      if t < 76.923 { return lerp(1, 0.3, easeOut(progress(t, 72.308, 76.923))) }
      if t < 80 { return lerp(0.3, 1, easeIn(progress(t, 76.923, 80))) }
      if t < 84.615 { return lerp(1, 0.3, easeOut(progress(t, 80, 84.615))) }
      if t < 87.692 { return lerp(0.3, 1, progress(t, 84.615, 87.692)) }
      return 1
    }
    if t < 73.846 { return easeOut(progress(t, 69.231, 73.846)) }
    return 1
  }

  private static func shieldScale(_ t: Double) -> CGFloat {
    if t < 69.231 { return 0.3 }
    return lerp(0.3, 1, overshoot(progress(t, 69.231, 76.923)))
  }

  private static func shakeX(_ t: Double) -> CGFloat {
    let times: [Double] = [78.462, 80, 81.538, 83.077, 84.615, 86.154, 87.692, 90]
    let values: [CGFloat] = [0, -3, 3, -3, 3, -2, 2, 0]
    guard t > times[0], t < times[times.count - 1] else { return 0 }
    for i in 1..<times.count where t < times[i] {
      return lerp(values[i - 1], values[i], progress(t, times[i - 1], times[i]))
    }
    return 0
  }

  private static func shimmerAlpha(_ t: Double) -> Double {
    if t < 80 || t > 95.385 { return 0 }
    if t < 83.077 { return 0.9 * easeIn(progress(t, 80, 83.077)) }
    if t < 89.231 { return 0.9 }
    return 0.9 * (1 - easeOut(progress(t, 89.231, 95.385)))
  }

  // MARK: Easing

  private static func progress(_ t: Double, _ from: Double, _ to: Double) -> Double {
    guard to > from else { return 1 }
    return max(0, min(1, (t - from) / (to - from)))
  }

  private static func lerp(_ from: CGFloat, _ to: CGFloat, _ fraction: Double) -> CGFloat {
    from + (to - from) * CGFloat(fraction)
  }

  private static func lerp(_ from: Double, _ to: Double, _ fraction: Double) -> Double {
    from + (to - from) * fraction
  }

  private static func easeIn(_ x: Double) -> Double { x * x }
  private static func easeOut(_ x: Double) -> Double { 1 - (1 - x) * (1 - x) }
  private static func easeInOut(_ x: Double) -> Double {
    x < 0.5 ? 2 * x * x : 1 - pow(-2 * x + 2, 2) / 2
  }
  /// cubic-bezier(0.34, 1.56, 0.64, 1): a slight overshoot, back-out style.
  private static func overshoot(_ x: Double) -> Double {
    let c1 = 1.70158, c3 = c1 + 1
    return 1 + c3 * pow(x - 1, 3) + c1 * pow(x - 1, 2)
  }
}

// MARK: - Glyphs

/// The marker's vector glyphs, from Android's `ic_browser_express_trust_*` drawables.
struct ExpressTrustGlyph {
  enum Paint {
    case fill(Color)
    case stroke(Color, width: CGFloat, cap: CGLineCap = .butt, join: CGLineJoin = .miter)
    /// The diagonal shine that sweeps a secure shield.
    case shine
  }

  struct Layer {
    let path: Path
    let paint: Paint
  }

  let layers: [Layer]

  private init(_ layers: [(String, Paint)]) {
    self.layers = layers.map { Layer(path: ExpressSVGPath.path($0.0), paint: $0.1) }
  }

  private static let shieldOutline =
    "M12 2L4 6V11C4 16.55 7.84 21.74 12 23C16.16 21.74 20 16.55 20 11V6L12 2Z"
  private static let disc =
    "M12 22C17.5228 22 22 17.5228 22 12C22 6.47715 17.5228 2 12 2C6.47715 2 2 6.47715 2 12C2 17.5228 6.47715 22 12 22Z"
  private static let green = Color(expressARGB: 0xFF4C_AF50)
  private static let amber = Color(expressARGB: 0xFFFF_9800)
  private static let red = Color(expressARGB: 0xFFF4_4336)
  /// The scan glyphs' grey.
  private static let glyph = Color(UIColor(expressLight: 0x666666, expressDark: 0xA8ABB5))

  static let secureShield = ExpressTrustGlyph([
    (shieldOutline, .fill(green)),
    ("M10 12L12 14L16 10", .stroke(.white, width: 2, cap: .round, join: .round)),
  ])
  static let warningShield = ExpressTrustGlyph([
    (shieldOutline, .fill(amber)),
    ("M12 8V12", .stroke(.white, width: 2, cap: .round)),
    (
      "M12 16C12.5523 16 13 15.5523 13 15C13 14.4477 12.5523 14 12 14C11.4477 14 11 14.4477 11 15C11 15.5523 11.4477 16 12 16Z",
      .fill(.white)
    ),
  ])
  static let dangerousShield = ExpressTrustGlyph([
    (shieldOutline, .fill(red)),
    ("M9 9L15 15M15 9L9 15", .stroke(.white, width: 2, cap: .round)),
  ])

  static func shield(_ status: ExpressTrustStatus) -> ExpressTrustGlyph {
    switch status {
    case .secure: return secureShield
    case .warning: return warningShield
    case .dangerous: return dangerousShield
    }
  }

  private static let secureResult = ExpressTrustGlyph([
    (disc, .fill(green)),
    ("M9 12L11 14L15 10", .stroke(.white, width: 2, cap: .round, join: .round)),
  ])
  private static let warningResult = ExpressTrustGlyph([
    (disc, .stroke(amber, width: 1.5)),
    ("M12 8V12L14.5 13.5", .stroke(amber, width: 1.5, cap: .round)),
  ])
  private static let dangerousResult = ExpressTrustGlyph([
    (disc, .fill(red)),
    ("M9 9L15 15M15 9L9 15", .stroke(.white, width: 2, cap: .round)),
  ])

  static func result(_ status: ExpressTrustStatus) -> ExpressTrustGlyph {
    switch status {
    case .secure: return secureResult
    case .warning: return warningResult
    case .dangerous: return dangerousResult
    }
  }

  static let shimmer = ExpressTrustGlyph([(shieldOutline, .shine)])

  /// The white glyphs on the sheet's status disc: a checked shield, a warning sign, a slashed
  /// shield.
  static func disc(_ status: ExpressTrustStatus) -> ExpressTrustGlyph {
    switch status {
    case .secure: return discSecure
    case .warning: return discWarning
    case .dangerous: return discDangerous
    }
  }

  private static let discSecure = ExpressTrustGlyph([
    ("M19.5 3.75H4.5C4.10218 3.75 3.72064 3.90804 3.43934 4.18934C3.15804 4.47064 3 4.85218 3 5.25V10.5C3 15.4425 5.3925 18.4378 7.39969 20.0803C9.56156 21.8484 11.7122 22.4484 11.8059 22.4738C11.9348 22.5088 12.0708 22.5088 12.1997 22.4738C12.2934 22.4484 14.4413 21.8484 16.6059 20.0803C18.6075 18.4378 21 15.4425 21 10.5V5.25C21 4.85218 20.842 4.47064 20.5607 4.18934C20.2794 3.90804 19.8978 3.75 19.5 3.75ZM16.2825 10.2806L11.0325 15.5306C10.9628 15.6004 10.8801 15.6557 10.7891 15.6934C10.698 15.7312 10.6004 15.7506 10.5019 15.7506C10.4033 15.7506 10.3057 15.7312 10.2147 15.6934C10.1236 15.6557 10.0409 15.6004 9.97125 15.5306L7.72125 13.2806C7.58052 13.1399 7.50146 12.949 7.50146 12.75C7.50146 12.551 7.58052 12.3601 7.72125 12.2194C7.86198 12.0786 8.05285 11.9996 8.25187 11.9996C8.4509 11.9996 8.64177 12.0786 8.7825 12.2194L10.5 13.9397L15.2194 9.21937C15.2891 9.14969 15.3718 9.09442 15.4628 9.0567C15.5539 9.01899 15.6515 8.99958 15.75 8.99958C15.8485 8.99958 15.9461 9.01899 16.0372 9.0567C16.1282 9.09442 16.2109 9.14969 16.2806 9.21937C16.3503 9.28906 16.4056 9.37178 16.4433 9.46283C16.481 9.55387 16.5004 9.65145 16.5004 9.75C16.5004 9.84855 16.481 9.94613 16.4433 10.0372C16.4056 10.1282 16.3503 10.2109 16.2806 10.2806H16.2825Z", .fill(.white))
  ])
  private static let discWarning = ExpressTrustGlyph([
    ("M22.2 17.6334L14.0016 3.39563C13.7967 3.0468 13.5042 2.75758 13.1531 2.55662C12.802 2.35566 12.4045 2.24994 12 2.24994C11.5955 2.24994 11.198 2.35566 10.8469 2.55662C10.4958 2.75758 10.2033 3.0468 9.99844 3.39563L1.8 17.6334C1.60288 17.9708 1.49899 18.3546 1.49899 18.7453C1.49899 19.1361 1.60288 19.5198 1.8 19.8572C2.00225 20.2081 2.29422 20.4989 2.64596 20.6997C2.99771 20.9006 3.39655 21.0042 3.80156 21H20.1984C20.6031 21.0039 21.0016 20.9001 21.353 20.6993C21.7044 20.4985 21.996 20.2078 22.1981 19.8572C22.3955 19.52 22.4997 19.1363 22.5001 18.7456C22.5004 18.3548 22.3968 17.971 22.2 17.6334ZM11.25 9.75C11.25 9.55109 11.329 9.36032 11.4697 9.21967C11.6103 9.07902 11.8011 9 12 9C12.1989 9 12.3897 9.07902 12.5303 9.21967C12.671 9.36032 12.75 9.55109 12.75 9.75V13.5C12.75 13.6989 12.671 13.8897 12.5303 14.0303C12.3897 14.171 12.1989 14.25 12 14.25C11.8011 14.25 11.6103 14.171 11.4697 14.0303C11.329 13.8897 11.25 13.6989 11.25 13.5V9.75ZM12 18C11.7775 18 11.56 17.934 11.375 17.8104C11.19 17.6868 11.0458 17.5111 10.9606 17.3055C10.8755 17.1 10.8532 16.8738 10.8966 16.6555C10.94 16.4373 11.0472 16.2368 11.2045 16.0795C11.3618 15.9222 11.5623 15.815 11.7805 15.7716C11.9988 15.7282 12.225 15.7505 12.4305 15.8356C12.6361 15.9208 12.8118 16.065 12.9354 16.25C13.059 16.435 13.125 16.6525 13.125 16.875C13.125 17.1734 13.0065 17.4595 12.7955 17.6705C12.5845 17.8815 12.2984 18 12 18Z", .fill(.white))
  ])
  private static let discDangerous = ExpressTrustGlyph([
    ("M21 5.25V10.5C21 12.8662 20.4516 14.7863 19.6594 16.3256C19.6318 16.3798 19.5915 16.4264 19.5419 16.4615C19.4923 16.4967 19.4349 16.5192 19.3747 16.5272C19.3145 16.5352 19.2532 16.5285 19.1961 16.5076C19.1391 16.4867 19.088 16.4522 19.0472 16.4072L8.11125 4.37719C8.06218 4.32319 8.02993 4.25606 8.01844 4.184C8.00696 4.11195 8.01674 4.03812 8.0466 3.97154C8.07645 3.90497 8.12508 3.84855 8.18652 3.8092C8.24796 3.76985 8.31954 3.74927 8.3925 3.75H19.5C19.8978 3.75 20.2794 3.90804 20.5607 4.18934C20.842 4.47064 21 4.85218 21 5.25ZM5.055 3.24562C4.94222 3.12194 4.7913 3.03954 4.62627 3.01154C4.46124 2.98354 4.29158 3.01155 4.14431 3.09111C3.99704 3.17068 3.88062 3.29722 3.81358 3.4506C3.74655 3.60398 3.73274 3.77537 3.77437 3.9375C3.53982 4.06715 3.34428 4.25726 3.2081 4.48809C3.07191 4.71891 3.00006 4.982 3 5.25V10.5C3 15.4425 5.3925 18.4378 7.39969 20.0803C9.56156 21.8484 11.7122 22.4494 11.8059 22.4738C11.9348 22.5088 12.0708 22.5088 12.1997 22.4738C12.2934 22.4494 14.4413 21.8484 16.6059 20.0803C16.9346 19.8095 17.2491 19.522 17.5481 19.2188L18.945 20.7544C19.0109 20.8286 19.0908 20.8891 19.1802 20.9322C19.2696 20.9753 19.3667 21.0002 19.4658 21.0056C19.5649 21.0109 19.664 20.9965 19.7575 20.9632C19.851 20.93 19.937 20.8784 20.0105 20.8117C20.0839 20.7449 20.1434 20.6643 20.1854 20.5744C20.2275 20.4845 20.2513 20.3871 20.2554 20.288C20.2595 20.1888 20.244 20.0898 20.2096 19.9967C20.1752 19.9036 20.1226 19.8183 20.055 19.7456L5.055 3.24562Z", .fill(.white))
  ])

  static let scanFrame = ExpressTrustGlyph([
    ("M2 12H22", .stroke(green, width: 2, cap: .round)),
    (
      "M6 4H3C2.44772 4 2 4.44772 2 5V7C2 7.55228 2.44772 8 3 8H6C6.55228 8 7 7.55228 7 7V5C7 4.44772 6.55228 4 6 4Z",
      .stroke(green, width: 1.2)
    ),
    (
      "M21 4H18C17.4477 4 17 4.44772 17 5V7C17 7.55228 17.4477 8 18 8H21C21.5523 8 22 7.55228 22 7V5C22 4.44772 21.5523 4 21 4Z",
      .stroke(green, width: 1.2)
    ),
    (
      "M6 16H3C2.44772 16 2 16.4477 2 17V19C2 19.5523 2.44772 20 3 20H6C6.55228 20 7 19.5523 7 19V17C7 16.4477 6.55228 16 6 16Z",
      .stroke(green, width: 1.2)
    ),
    (
      "M21 16H18C17.4477 16 17 16.4477 17 17V19C17 19.5523 17.4477 20 18 20H21C21.5523 20 22 19.5523 22 19V17C22 16.4477 21.5523 16 21 16Z",
      .stroke(green, width: 1.2)
    ),
  ])

  /// Trackers, ads, cookies, fingerprinting, in the order the scan shows them.
  static let scanGlyphs: [ExpressTrustGlyph] = [
    ExpressTrustGlyph([
      (
        "M12 15C13.6569 15 15 13.6569 15 12C15 10.3431 13.6569 9 12 9C10.3431 9 9 10.3431 9 12C9 13.6569 10.3431 15 12 15Z",
        .fill(glyph)
      ),
      (
        "M12 5C7.45 5 3.57 7.95 2 12C3.57 16.05 7.45 19 12 19C16.55 19 20.43 16.05 22 12C20.43 7.95 16.55 5 12 5ZM12 16.5C9.52 16.5 7.5 14.48 7.5 12C7.5 9.52 9.52 7.5 12 7.5C14.48 7.5 16.5 9.52 16.5 12C16.5 14.48 14.48 16.5 12 16.5Z",
        .fill(glyph)
      ),
    ]),
    ExpressTrustGlyph([
      (
        "M19 5H5C3.89543 5 3 5.89543 3 7V17C3 18.1046 3.89543 19 5 19H19C20.1046 19 21 18.1046 21 17V7C21 5.89543 20.1046 5 19 5Z",
        .stroke(glyph, width: 1.5)
      ),
      ("M8 15L10.5 11L13 14L15 11.5L18 15H8Z", .fill(glyph)),
      (
        "M8.5 11C9.32843 11 10 10.3284 10 9.5C10 8.67157 9.32843 8 8.5 8C7.67157 8 7 8.67157 7 9.5C7 10.3284 7.67157 11 8.5 11Z",
        .fill(glyph)
      ),
    ]),
    ExpressTrustGlyph([
      (
        "M12 21C16.9706 21 21 16.9706 21 12C21 7.02944 16.9706 3 12 3C7.02944 3 3 7.02944 3 12C3 16.9706 7.02944 21 12 21Z",
        .stroke(glyph, width: 1.5)
      ),
      (
        "M9 10.2C9.66274 10.2 10.2 9.66274 10.2 9C10.2 8.33726 9.66274 7.8 9 7.8C8.33726 7.8 7.8 8.33726 7.8 9C7.8 9.66274 8.33726 10.2 9 10.2Z",
        .fill(glyph)
      ),
      (
        "M14 8.9C14.4971 8.9 14.9 8.49706 14.9 8C14.9 7.50294 14.4971 7.1 14 7.1C13.5029 7.1 13.1 7.50294 13.1 8C13.1 8.49706 13.5029 8.9 14 8.9Z",
        .fill(glyph)
      ),
      (
        "M10 15C10.5523 15 11 14.5523 11 14C11 13.4477 10.5523 13 10 13C9.44772 13 9 13.4477 9 14C9 14.5523 9.44772 15 10 15Z",
        .fill(glyph)
      ),
      (
        "M15 14.3C15.718 14.3 16.3 13.718 16.3 13C16.3 12.282 15.718 11.7 15 11.7C14.282 11.7 13.7 12.282 13.7 13C13.7 13.718 14.282 14.3 15 14.3Z",
        .fill(glyph)
      ),
      (
        "M12 11.8C12.4418 11.8 12.8 11.4418 12.8 11C12.8 10.5582 12.4418 10.2 12 10.2C11.5582 10.2 11.2 10.5582 11.2 11C11.2 11.4418 11.5582 11.8 12 11.8Z",
        .fill(glyph)
      ),
    ]),
    ExpressTrustGlyph([
      (
        "M12 2C9.24 2 7 4.24 7 7V10C7 12.76 9.24 15 12 15C14.76 15 17 12.76 17 10V7C17 4.24 14.76 2 12 2ZM12 4C13.66 4 15 5.34 15 7V10C15 11.66 13.66 13 12 13C10.34 13 9 11.66 9 10V7C9 5.34 10.34 4 12 4Z",
        .fill(glyph)
      ),
      (
        "M12 8C11.45 8 11 8.45 11 9V10C11 10.55 11.45 11 12 11C12.55 11 13 10.55 13 10V9C13 8.45 12.55 8 12 8Z",
        .fill(glyph)
      ),
      (
        "M19 15V18C19 20.21 17.21 22 15 22H9C6.79 22 5 20.21 5 18V15H7V18C7 19.1 7.9 20 9 20H15C16.1 20 17 19.1 17 18V15H19Z",
        .fill(glyph)
      ),
    ]),
  ]
}

// MARK: - SVG path data

/// Turns Android vector `pathData` into a SwiftUI path. Covers the commands the trust glyphs
/// use: M, L, H, V, C, S, Q, Z (absolute and relative).
enum ExpressSVGPath {
  static func path(_ data: String) -> Path {
    var path = Path()
    var tokens = tokenize(data)[...]
    var current = CGPoint.zero
    var start = CGPoint.zero
    var lastControl: CGPoint?
    var command: Character = "M"

    func number() -> CGFloat? {
      guard case .number(let value)? = tokens.first else { return nil }
      tokens.removeFirst()
      return CGFloat(value)
    }
    func point(relative: Bool) -> CGPoint? {
      guard let x = number(), let y = number() else { return nil }
      return relative ? CGPoint(x: current.x + x, y: current.y + y) : CGPoint(x: x, y: y)
    }

    while let token = tokens.first {
      if case .command(let c) = token {
        command = c
        tokens.removeFirst()
        if c == "Z" || c == "z" {
          path.closeSubpath()
          current = start
          lastControl = nil
          continue
        }
      }
      let relative = command.isLowercase
      switch command.uppercased().first ?? "M" {
      case "M":
        guard let p = point(relative: relative) else { return path }
        path.move(to: p)
        current = p
        start = p
        lastControl = nil
        // Further pairs after a move are lines.
        command = relative ? "l" : "L"
      case "L":
        guard let p = point(relative: relative) else { return path }
        path.addLine(to: p)
        current = p
        lastControl = nil
      case "H":
        guard let x = number() else { return path }
        current = CGPoint(x: relative ? current.x + x : x, y: current.y)
        path.addLine(to: current)
        lastControl = nil
      case "V":
        guard let y = number() else { return path }
        current = CGPoint(x: current.x, y: relative ? current.y + y : y)
        path.addLine(to: current)
        lastControl = nil
      case "C":
        guard let c1 = point(relative: relative), let c2 = point(relative: relative),
          let p = point(relative: relative)
        else { return path }
        path.addCurve(to: p, control1: c1, control2: c2)
        current = p
        lastControl = c2
      case "S":
        let c1 = lastControl.map { CGPoint(x: 2 * current.x - $0.x, y: 2 * current.y - $0.y) } ?? current
        guard let c2 = point(relative: relative), let p = point(relative: relative) else { return path }
        path.addCurve(to: p, control1: c1, control2: c2)
        current = p
        lastControl = c2
      case "Q":
        guard let c = point(relative: relative), let p = point(relative: relative) else { return path }
        path.addQuadCurve(to: p, control: c)
        current = p
        lastControl = nil
      default:
        // An unsupported command: stop rather than draw something wrong.
        return path
      }
    }
    return path
  }

  private enum Token {
    case command(Character)
    case number(Double)
  }

  private static func tokenize(_ data: String) -> [Token] {
    var tokens: [Token] = []
    var buffer = ""
    var hasDot = false
    func flush() {
      if let value = Double(buffer) { tokens.append(.number(value)) }
      buffer = ""
      hasDot = false
    }
    for character in data {
      if character.isLetter && character != "e" && character != "E" {
        flush()
        tokens.append(.command(character))
      } else if character == "-" {
        // A minus starts a new number unless it follows an exponent.
        if let last = buffer.last, last == "e" || last == "E" {
          buffer.append(character)
        } else {
          flush()
          buffer.append(character)
        }
      } else if character == "." {
        if hasDot { flush() }
        hasDot = true
        buffer.append(character)
      } else if character.isNumber || character == "e" || character == "E" {
        buffer.append(character)
      } else {
        flush()
      }
    }
    flush()
    return tokens
  }
}
