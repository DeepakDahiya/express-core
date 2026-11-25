// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import SwiftUI
import UIKit

// The emoji explosion played when the reader reacts to a comment, ported from the Android app's
// `EmojiBurst` (itself from the design handoff's emoji-burst.js "Motion spec").
//
// On a card the burst fills the card and is clipped to it; on a reel page it pops from the
// tapped chip, explodes from a centre near it in 2–3 random lobes and fills the page. Every
// emoji works: about 60 hand-tuned families (❤️ brings 💖💕💘🌹, 🔥 brings 💥⚡🧨), a flag bursts
// as copies of itself with 🎉✨🎊🏆, and any other emoji brings its neighbours in Unicode's order
// with a ring colour sampled from its own pixels. With Reduce Motion only the pop plays.
// Distances are in points, times in seconds, drag is exponential per second.

/// Runs one container's particles. Owned by the view that shows them.
@MainActor
final class ExpressEmojiBurstEngine: ObservableObject {
  fileprivate enum Kind {
    case hero, ring, emoji, dot
  }

  fileprivate struct Particle {
    let kind: Kind
    var emoji: String?
    var x0: CGFloat = 0, y0: CGFloat = 0, x1: CGFloat = 0, y1: CGFloat = 0
    var x: CGFloat = 0, y: CGFloat = 0, vx: CGFloat = 0, vy: CGFloat = 0
    var drag: CGFloat = 0, gravity: CGFloat = 0
    var rot: CGFloat = 0, spin: CGFloat = 0, size: CGFloat = 0, hold: CGFloat = 0
    var radius: CGFloat = 0, r0: CGFloat = 0, r1: CGFloat = 0, lineWidth: CGFloat = 0
    var color: UIColor = .white
    var life: CGFloat = 0, delay: CGFloat = 0, t: CGFloat = 0

    init(_ kind: Kind) { self.kind = kind }
  }

  private static let maxParticles = 600

  fileprivate private(set) var particles: [Particle] = []
  /// Changes on every burst, so the view wakes its frame clock.
  @Published private(set) var generation = 0
  private var lastStep: Date?

  var isEmpty: Bool { particles.isEmpty }

  /// Plays `emoji` in a container of `size`, popping from `origin` (the centre when nil).
  /// `fullscreen` is the reel page's burst; otherwise it's a card's.
  func play(
    _ emoji: String,
    origin: CGPoint?,
    in size: CGSize,
    fullscreen: Bool,
    reduceMotion: Bool
  ) {
    guard !emoji.isEmpty, size.width > 0, size.height > 0 else { return }
    let o = origin ?? CGPoint(x: size.width / 2, y: size.height / 2)
    // A burst into an idle engine starts its clock afresh.
    if particles.isEmpty { lastStep = nil }
    var added: [Particle]
    if reduceMotion {
      // Only the pop on the chip.
      added = [Self.hero(emoji, from: o, to: CGPoint(x: o.x, y: o.y - 8), size: fullscreen ? 30 : 22, hold: 0.25, life: 0.45)]
    } else {
      let family = ExpressEmojiFamily.resolve(emoji)
      added =
        fullscreen
        ? Self.fullscreen(emoji, family: family, origin: o, size: size)
        : Self.contained(emoji, family: family, origin: o, size: size)
    }
    particles.append(contentsOf: added)
    if particles.count > Self.maxParticles {
      particles.removeFirst(particles.count - Self.maxParticles)
    }
    generation += 1
  }

  func cancel() {
    particles.removeAll()
    lastStep = nil
  }

  /// Advances the particles to `date`; dropped frames step at most 33 ms.
  fileprivate func step(to date: Date) {
    let dt: CGFloat
    if let lastStep {
      dt = min(0.033, max(0, CGFloat(date.timeIntervalSince(lastStep))))
    } else {
      dt = 1 / 60
    }
    lastStep = date
    var index = particles.count - 1
    while index >= 0 {
      var p = particles[index]
      p.t += dt
      if p.t >= p.life + p.delay {
        particles.remove(at: index)
        index -= 1
        continue
      }
      if p.t >= p.delay, p.kind == .emoji || p.kind == .dot {
        let f = CGFloat(exp(Double(-p.drag * dt)))
        p.vx *= f
        p.vy = p.vy * f + p.gravity * dt
        p.x += p.vx * dt
        p.y += p.vy * dt
        if p.kind == .emoji { p.rot += p.spin * dt }
      }
      particles[index] = p
      index -= 1
    }
    if particles.isEmpty { lastStep = nil }
  }

  // MARK: The two shapes

  /// A card: 30 emoji and 18 sparks stretched sideways to fill the wide card.
  private static func contained(
    _ emoji: String,
    family: ExpressEmojiFamily,
    origin o: CGPoint,
    size: CGSize
  ) -> [Particle] {
    let w = size.width
    let h = size.height
    let cx = w * 0.5
    let cy = h * 0.48
    let hx = cx + (o.x - cx) * 0.25
    let hy = min(cy, o.y - 24)

    var list = [hero(emoji, from: o, to: CGPoint(x: o.x, y: o.y - 10), size: 22, hold: 0.28, life: 0.5)]
    list.append(ring(at: CGPoint(x: hx, y: hy), from: 6, to: max(w, h) * 0.55, lineWidth: 6, color: family.color))
    for i in 0..<30 {
      let a = rnd(0, .pi * 2)
      let speed = rnd(160, 520)
      var p = particle(family.set[i % family.set.count], at: CGPoint(x: hx, y: hy))
      p.vx = cos(a) * speed * 1.5
      p.vy = sin(a) * speed * 0.8
      p.drag = 2.6
      p.gravity = 90
      p.size = rnd(12, 24)
      list.append(p)
    }
    for _ in 0..<18 {
      let a = rnd(0, .pi * 2)
      let speed = rnd(120, 420)
      list.append(
        dot(
          at: CGPoint(x: hx, y: hy),
          velocity: CGVector(dx: cos(a) * speed * 1.4, dy: sin(a) * speed * 0.8),
          drag: 3,
          gravity: 60,
          radius: rnd(1.5, 3),
          color: family.color
        )
      )
    }
    return list
  }

  /// A reel: the pop on the chip, then a burst from a centre near it in 2–3 lobes of random
  /// direction, width and reach, so no two taps fill the screen alike.
  private static func fullscreen(
    _ emoji: String,
    family: ExpressEmojiFamily,
    origin o: CGPoint,
    size: CGSize
  ) -> [Particle] {
    let w = size.width
    let h = size.height
    let margin: CGFloat = 18
    let drag: CGFloat = 4
    let cx = clamp(o.x - rnd(50, 160), margin + 40, w - margin - 20)
    let cy = clamp(o.y + rnd(-170, 70), margin + 60, h - margin - 60)

    struct Lobe {
      let direction: CGFloat
      let spread: CGFloat
      let reach: CGFloat
      let weight: CGFloat
    }
    let lobeCount = CGFloat.random(in: 0..<1) < 0.45 ? 3 : 2
    let lobes: [Lobe] = (0..<lobeCount).map { _ in
      // Mostly towards the screen (the rail is on the right), sometimes anywhere.
      let direction =
        CGFloat.random(in: 0..<1) < 0.75 ? rnd(.pi * 0.55, .pi * 1.45) : rnd(0, .pi * 2)
      return Lobe(direction: direction, spread: rnd(0.3, 0.75), reach: rnd(0.55, 1), weight: rnd(0.6, 1.4))
    }
    let weightSum = lobes.reduce(0) { $0 + $1.weight }
    func pickLobe() -> Lobe {
      var r = CGFloat.random(in: 0..<1) * weightSum
      for lobe in lobes {
        r -= lobe.weight
        if r <= 0 { return lobe }
      }
      return lobes[0]
    }
    func reach(_ a: CGFloat) -> CGFloat {
      let dx = cos(a)
      let dy = sin(a)
      let tx = dx < 0 ? (cx - margin) / -dx : (w - margin - cx) / max(dx, 1e-3)
      let ty = dy < 0 ? (cy - margin) / -dy : (h - margin - cy) / max(dy, 1e-3)
      return max(0, min(tx, ty))
    }

    let centre = CGPoint(x: cx, y: cy)
    var list = [hero(emoji, from: o, to: CGPoint(x: o.x - 6, y: o.y - 14), size: 30, hold: 0.3, life: 0.55)]
    list.append(ring(at: centre, from: 8, to: rnd(0.5, 0.75) * hypot(w, h), lineWidth: 7, color: family.color))
    let count = Int(rnd(48, 62).rounded())
    for i in 0..<count {
      var a: CGFloat
      var distance: CGFloat
      if CGFloat.random(in: 0..<1) < 0.72 {
        let lobe = pickLobe()
        a = lobe.direction + gauss() * lobe.spread
        distance = reach(a) * lobe.reach * pow(CGFloat.random(in: 0..<1), 0.65)
      } else {
        // The tight core around the centre.
        a = rnd(0, .pi * 2)
        distance = min(reach(a), rnd(24, 130))
      }
      distance = max(14, distance)
      var p = particle(family.set[i % family.set.count], at: centre)
      // With drag d, an emoji launched at distance × d comes to rest at that distance.
      p.vx = cos(a) * distance * drag
      p.vy = sin(a) * distance * drag
      p.drag = drag
      p.gravity = 40
      p.size = rnd(16, 30) * (1 - 0.3 * min(1, distance / 420))
      list.append(p)
    }
    for i in 0..<24 {
      let lobe = pickLobe()
      let a = lobe.direction + gauss() * lobe.spread * 1.3
      let distance = reach(a) * rnd(0.15, 0.6)
      var p = dot(
        at: centre,
        velocity: CGVector(dx: cos(a) * distance * 3.2, dy: sin(a) * distance * 3.2),
        drag: 3.2,
        gravity: 60,
        radius: rnd(1.8, 3.4),
        color: i % 3 != 0 ? family.color : .white
      )
      p.life = rnd(0.55, 0.9)
      list.append(p)
    }
    return list
  }

  private static func hero(
    _ emoji: String,
    from start: CGPoint,
    to end: CGPoint,
    size: CGFloat,
    hold: CGFloat,
    life: CGFloat
  ) -> Particle {
    var p = Particle(.hero)
    p.emoji = emoji
    p.x0 = start.x
    p.y0 = start.y
    p.x1 = end.x
    p.y1 = end.y
    p.size = size
    p.hold = hold
    p.life = life
    return p
  }

  private static func ring(
    at centre: CGPoint,
    from r0: CGFloat,
    to r1: CGFloat,
    lineWidth: CGFloat,
    color: UIColor
  ) -> Particle {
    var p = Particle(.ring)
    p.x = centre.x
    p.y = centre.y
    p.r0 = r0
    p.r1 = r1
    p.lineWidth = lineWidth
    p.color = color
    p.life = 0.7
    p.delay = 0.12
    return p
  }

  private static func particle(_ emoji: String, at point: CGPoint) -> Particle {
    var p = Particle(.emoji)
    p.emoji = emoji
    p.x = point.x
    p.y = point.y
    p.rot = rnd(-0.5, 0.5)
    p.spin = rnd(-4, 4)
    p.life = rnd(0.9, 1.5)
    p.delay = 0.14 + rnd(0, 0.12)
    return p
  }

  private static func dot(
    at point: CGPoint,
    velocity: CGVector,
    drag: CGFloat,
    gravity: CGFloat,
    radius: CGFloat,
    color: UIColor
  ) -> Particle {
    var p = Particle(.dot)
    p.x = point.x
    p.y = point.y
    p.vx = velocity.dx
    p.vy = velocity.dy
    p.drag = drag
    p.gravity = gravity
    p.radius = radius
    p.color = color
    p.life = rnd(0.5, 0.9)
    p.delay = 0.14
    return p
  }
}

// MARK: - Drawing

/// Draws an engine's particles: no layout and no touches. Put it in an overlay of the container
/// the burst should fill (clip that container for a card).
struct ExpressEmojiBurstView: View {
  @ObservedObject var engine: ExpressEmojiBurstEngine

  /// True from a burst until its last particle is gone, so the frame clock only runs then.
  @State private var running = false

  var body: some View {
    TimelineView(.animation(paused: !running)) { timeline in
      Canvas { context, _ in
        engine.step(to: timeline.date)
        draw(in: &context)
      }
    }
    .allowsHitTesting(false)
    .accessibilityHidden(true)
    .task(id: engine.generation) {
      guard !engine.isEmpty else { return }
      running = true
      // Particles live at most ~1.7 s; check until the engine has emptied.
      while !Task.isCancelled && !engine.isEmpty {
        try? await Task.sleep(nanoseconds: 250_000_000)
      }
      if !Task.isCancelled { running = false }
    }
  }

  private func draw(in context: inout GraphicsContext) {
    // In the order added: the pop under the ring, the ring under the particles.
    for p in engine.particles {
      let t = p.t - p.delay
      guard t >= 0 else { continue }
      let k = t / p.life
      switch p.kind {
      case .ring:
        let radius = p.r0 + (p.r1 - p.r0) * expressEaseOut(k)
        let rect = CGRect(x: p.x - radius, y: p.y - radius, width: radius * 2, height: radius * 2)
        context.stroke(
          Path(ellipseIn: rect),
          with: .color(Color(p.color).opacity(Double(clamp((1 - k) * 0.55, 0, 1)))),
          lineWidth: p.lineWidth * (1 - k) + 0.5
        )
      case .dot:
        let radius = p.radius * (1 - k * 0.6)
        let rect = CGRect(x: p.x - radius, y: p.y - radius, width: radius * 2, height: radius * 2)
        context.fill(
          Path(ellipseIn: rect),
          with: .color(Color(p.color).opacity(Double(clamp(1 - k, 0, 1))))
        )
      case .hero:
        let into = min(1, t / 0.18)
        let out = max(0, (t - p.hold) / (p.life - p.hold))
        let travel = expressEaseOut(into) - expressEaseOut(out)
        let back = expressEaseOut(out)
        let size = p.size * (easeOutBack(into) * (1 - back) + 0.3 * back)
        let rot = sin(t * 14) * 0.08 * (1 - out)
        drawEmoji(
          p.emoji,
          in: &context,
          at: CGPoint(x: p.x0 + (p.x1 - p.x0) * travel, y: p.y0 + (p.y1 - p.y0) * travel),
          size: size,
          rotation: rot,
          alpha: 1 - out * out
        )
      case .emoji:
        let pop = min(1, t / 0.12)
        let size = p.size * (0.4 + 0.6 * easeOutBack(pop))
        let alpha = k > 0.55 ? 1 - (k - 0.55) / 0.45 : 1
        drawEmoji(p.emoji, in: &context, at: CGPoint(x: p.x, y: p.y), size: size, rotation: p.rot, alpha: alpha)
      }
    }
  }

  /// `size` is the emoji's font size, as on Android; the sprite's box is a little larger.
  private func drawEmoji(
    _ emoji: String?,
    in context: inout GraphicsContext,
    at point: CGPoint,
    size: CGFloat,
    rotation: CGFloat,
    alpha: CGFloat
  ) {
    guard let emoji, alpha > 0, size > 0.5, let sprite = ExpressEmojiSprites.sprite(emoji) else {
      return
    }
    let box = size / ExpressEmojiSprites.textFraction
    var layer = context
    layer.opacity = Double(min(1, alpha))
    layer.translateBy(x: point.x, y: point.y)
    layer.rotate(by: .radians(Double(rotation)))
    layer.draw(Image(uiImage: sprite), in: CGRect(x: -box / 2, y: -box / 2, width: box, height: box))
  }
}

// MARK: - Sprites

/// Each emoji rasterised once and then drawn as an image, so a reel burst (~90 draws a frame)
/// stays light.
@MainActor
enum ExpressEmojiSprites {
  /// The emoji fills 78% of its sprite, as in the design.
  static let textFraction: CGFloat = 0.78
  private static let side: CGFloat = 64
  private static let cache: NSCache<NSString, UIImage> = {
    let cache = NSCache<NSString, UIImage>()
    cache.countLimit = 48
    return cache
  }()

  static func sprite(_ emoji: String) -> UIImage? {
    if let image = cache.object(forKey: emoji as NSString) { return image }
    let format = UIGraphicsImageRendererFormat.preferred()
    format.scale = 3
    let image = UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format)
      .image { _ in
        let text = NSAttributedString(
          string: emoji,
          attributes: [.font: UIFont.systemFont(ofSize: side * textFraction)]
        )
        let bounds = text.boundingRect(
          with: CGSize(width: side * 2, height: side * 2),
          options: [.usesLineFragmentOrigin],
          context: nil
        )
        // Centre the glyph so it spins about its middle.
        text.draw(at: CGPoint(x: (side - bounds.width) / 2, y: (side - bounds.height) / 2))
      }
    cache.setObject(image, forKey: emoji as NSString)
    return image
  }

  /// The average of the emoji's saturated pixels, for emoji without a hand-tuned colour.
  static func sampledColor(_ emoji: String) -> UIColor? {
    let size = 32
    var pixels = [UInt8](repeating: 0, count: size * size * 4)
    let drawn: Bool = pixels.withUnsafeMutableBytes { buffer in
      guard
        let context = CGContext(
          data: buffer.baseAddress,
          width: size,
          height: size,
          bitsPerComponent: 8,
          bytesPerRow: size * 4,
          space: CGColorSpaceCreateDeviceRGB(),
          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )
      else { return false }
      UIGraphicsPushContext(context)
      // UIKit text draws top-down; flip the bitmap context to match.
      context.translateBy(x: 0, y: CGFloat(size))
      context.scaleBy(x: 1, y: -1)
      let text = NSAttributedString(string: emoji, attributes: [.font: UIFont.systemFont(ofSize: 24)])
      let bounds = text.boundingRect(
        with: CGSize(width: 64, height: 64),
        options: [.usesLineFragmentOrigin],
        context: nil
      )
      text.draw(at: CGPoint(x: (CGFloat(size) - bounds.width) / 2, y: (CGFloat(size) - bounds.height) / 2))
      UIGraphicsPopContext()
      return true
    }
    guard drawn else { return nil }
    var r = 0
    var g = 0
    var b = 0
    var n = 0
    for i in stride(from: 0, to: pixels.count, by: 4) {
      let a = Int(pixels[i + 3])
      guard a >= 200 else { continue }
      let pr = Int(pixels[i])
      let pg = Int(pixels[i + 1])
      let pb = Int(pixels[i + 2])
      let maxC = max(pr, pg, pb)
      let minC = min(pr, pg, pb)
      if maxC - minC < 40 || maxC < 60 { continue }
      r += pr
      g += pg
      b += pb
      n += 1
    }
    guard n > 8 else { return nil }
    return UIColor(red: CGFloat(r / n) / 255, green: CGFloat(g / n) / 255, blue: CGFloat(b / n) / 255, alpha: 1)
  }
}

// MARK: - Families

/// The ring and spark colour, and the emoji the burst throws (taken in turn).
@MainActor
struct ExpressEmojiFamily {
  let color: UIColor
  let set: [String]

  private static let gold = UIColor(red: 1, green: 0xC8 / 255, blue: 0x3D / 255, alpha: 1)
  private static let sparkles = "✨"
  private static var cache: [String: ExpressEmojiFamily] = [:]
  private static var catalogIndex: [String: (section: Int, index: Int)]?

  /// A flag, then a hand-tuned theme (skin tones and variation selectors stripped; a ZWJ
  /// sequence falls back to its first emoji), then the emoji's neighbours. The emoji itself is
  /// always in the set 2–3 times so it stays the dominant shape.
  static func resolve(_ emoji: String) -> ExpressEmojiFamily {
    if let family = cache[emoji] { return family }
    let family = build(emoji)
    if cache.count > 64 { cache.removeAll() }
    cache[emoji] = family
    return family
  }

  private static func build(_ emoji: String) -> ExpressEmojiFamily {
    let key = normalize(emoji)
    if isFlag(key) {
      return ExpressEmojiFamily(color: gold, set: [emoji, emoji, emoji] + flagFamily)
    }
    if let theme = themesByEmoji[key] ?? key.unicodeScalars.first.flatMap({ themesByEmoji[String($0)] }) {
      return ExpressEmojiFamily(color: theme.color, set: [emoji, emoji] + theme.set)
    }
    let set = [emoji, emoji, emoji] + neighbours(of: key) + [sparkles]
    return ExpressEmojiFamily(color: ExpressEmojiSprites.sampledColor(emoji) ?? gold, set: set)
  }

  /// Up to six emoji next to this one in Unicode's order, within its picker section.
  private static func neighbours(of key: String) -> [String] {
    if catalogIndex == nil {
      var index: [String: (Int, Int)] = [:]
      for (s, section) in ExpressEmojiCatalog.sections.enumerated() {
        for (i, emoji) in section.enumerated() {
          index[normalize(emoji)] = (s, i)
        }
      }
      catalogIndex = index
    }
    guard
      let at = catalogIndex?[key]
        ?? key.unicodeScalars.first.flatMap({ catalogIndex?[String($0)] })
    else { return [] }
    let section = ExpressEmojiCatalog.sections[at.section]
    var out: [String] = []
    for distance in 1...10 where out.count < 6 {
      for step in [-distance, distance] {
        let i = at.index + step
        if section.indices.contains(i), out.count < 6 { out.append(section[i]) }
      }
    }
    return out
  }

  /// Without variation selectors and skin tones, the form the tables are keyed by.
  static func normalize(_ emoji: String) -> String {
    var scalars = String.UnicodeScalarView()
    for scalar in emoji.unicodeScalars {
      let v = scalar.value
      if v == 0xFE0E || v == 0xFE0F || (0x1F3FB...0x1F3FF).contains(v) { continue }
      scalars.append(scalar)
    }
    return String(scalars)
  }

  /// A country (two regional indicators), a subdivision (🏴 + tags), or 🏁 🚩 🎌.
  private static func isFlag(_ key: String) -> Bool {
    let values = key.unicodeScalars.map(\.value)
    let isRegional: (UInt32) -> Bool = { (0x1F1E6...0x1F1FF).contains($0) }
    if values.count == 2, isRegional(values[0]), isRegional(values[1]) { return true }
    if values.count >= 3, values.first == 0x1F3F4, values.last == 0xE007F {
      return values.dropFirst().dropLast().allSatisfy { (0xE0061...0xE007A).contains($0) }
    }
    return key == "🏁" || key == "🚩" || key == "🎌"
  }

  // MARK: The design's tables (from emoji-burst.js THEMES / assign, via Android)

  private static let flagFamily = ["🎉", "✨", "🎊", "🏆"]

  /// Theme name, ring and spark colour, the family it throws.
  private static let themes: [(String, UInt32, String)] = [
    ("love", 0xFF4D7D, "💖 💕 💗 💘 💞 💓 🌹 ✨"),
    ("laugh", 0xFFC83D, "🤣 😆 😹 💦 😂 ✨"),
    ("happy", 0xFFC83D, "😊 😄 🌼 ☀️ ✨ 🌈"),
    ("cool", 0x3AA3E8, "😎 🕶️ 🔥 ⚡ ✨ 💯"),
    ("fire", 0xFF7A1A, "💥 ⚡ 🧨 🔥 ✨ 🌶️"),
    ("approve", 0x577DFF, "👏 🙌 💪 🎉 ✨ 👍"),
    ("clap", 0xFFB547, "👏 🙌 🎉 ⭐ ✨ 🔥"),
    ("wow", 0xFFC83D, "🤯 😲 💫 ❗ 👀 ✨"),
    ("sad", 0x5AA9E6, "💧 😢 🌧️ 💔 🥀 ☁️"),
    ("angry", 0xE8453C, "💢 😤 🔥 ⚡ 💥 🌋"),
    ("scared", 0x8C7AE6, "👻 😱 ⚡ 🌩️ 💀 🕸️"),
    ("party", 0xA26BFF, "🎊 🥳 🎈 ✨ 🎉 🪅"),
    ("birthday", 0xFF7EB6, "🎂 🎁 🎈 🕯️ 🎉 ✨"),
    ("gift", 0xE8453C, "🎁 🎀 ✨ 🎉 💝 ⭐"),
    ("pray", 0xFFC83D, "✨ 🙏 🕊️ 💫 🌟 ☀️"),
    ("hundred", 0xE8453C, "💯 🔥 ⚡ ✨ 👏 🎯"),
    ("check", 0x2FBF71, "✔️ 💯 🎯 🏆 ✨ 🟢"),
    ("cross", 0xE8453C, "❌ 🚫 ⛔ 💥 ✖️"),
    ("star", 0xFFC83D, "⭐ 🌟 ✨ 💫 🌠"),
    ("money", 0x2FBF71, "💸 💰 🤑 💵 🪙 ✨"),
    ("win", 0xFFB300, "🏆 🥇 🎉 ✨ 🎊 ⭐"),
    ("sport", 0x2FBF71, "🏆 🥇 🎉 💪 🔥 ✨"),
    ("music", 0xA26BFF, "🎵 🎶 🎧 🎤 ✨ 💃"),
    ("dance", 0xFF5C7A, "💃 🕺 🎶 🪩 ✨ 🎉"),
    ("rocket", 0xFF7A1A, "🚀 🌟 💫 🌙 ✨ 🔥"),
    ("think", 0xFFC83D, "🤔 💭 ❓ 💡 🧠 ✨"),
    ("idea", 0xFFC83D, "💡 ✨ 🧠 ⚡ 🌟"),
    ("kiss", 0xFF4D7D, "💋 😘 💕 💖 ✨ 🌹"),
    ("sleep", 0x8C7AE6, "💤 😴 🌙 ⭐ ☁️"),
    ("sick", 0x7BC67E, "🤒 💊 🌡️ 🦠 🤧 💧"),
    ("skull", 0xB8BCC8, "💀 ☠️ 👻 ⚰️ 🦴 ✨"),
    ("poop", 0x8D5B3E, "💩 🪰 💨 🚽 ✨"),
    ("drink", 0xFFB547, "🥂 🍾 🎉 ✨ 🫧"),
    ("flower", 0xFF7EB6, "🌸 🌷 🌼 🌺 🌹 ✨"),
    ("nature", 0x2FBF71, "🍃 🌿 🍀 🌱 ✨"),
    ("sun", 0xFFB300, "☀️ 🌞 ✨ 🌈 🌤️"),
    ("night", 0x8C7AE6, "🌙 ⭐ ✨ 🌌 💫"),
    ("rain", 0x5AA9E6, "💧 ☔ 🌧️ 🌈 ☁️"),
    ("snow", 0x9FD3F2, "❄️ ☃️ 🌨️ ✨ ⛄"),
    ("rainbow", 0xFF5C7A, "🌈 ✨ 🦄 ☁️ 💖 ⭐"),
    ("water", 0x3AA3E8, "💦 🌊 💧 🫧 ✨"),
    ("zap", 0xFFC83D, "⚡ 💥 ✨ 🌩️ 💫"),
    ("boom", 0xFF7A1A, "💥 🔥 ⚡ 💢 ✨"),
    ("ghost", 0xB8BCC8, "👻 🎃 🦇 🕸️ ✨"),
    ("alien", 0x7BC67E, "👽 🛸 🌌 ✨ 👾"),
    ("robot", 0x9AA3B5, "🤖 ⚙️ 🔩 ⚡ ✨"),
    ("cat", 0xFFB547, "🐱 🐾 😺 🧶 ✨"),
    ("dog", 0xC08A5B, "🐶 🐾 🦴 💕 ✨"),
    ("unicorn", 0xFF7EB6, "🦄 🌈 ✨ 💖 ⭐"),
    ("muscle", 0xFFB547, "💪 🔥 ⚡ 🏋️ ✨"),
    ("ok", 0xFFB547, "👌 ✨ 💯 ✔️ 👍"),
    ("wave", 0xFFB547, "👋 ✨ 😊 💫"),
    ("nope", 0x577DFF, "👎 💢 😒 ❌"),
    ("shock", 0xE8453C, "🫨 ⚡ 💥 ❗ 😱"),
    ("shy", 0xFF8FAB, "🥺 👉👈 💕 🌸 ✨"),
    ("holiday", 0x2FBF71, "🎄 🎅 🎁 ❄️ ⭐ ✨"),
    ("halloween", 0xFF7A1A, "🎃 👻 🦇 🕸️ 🍬"),
  ]

  /// Theme name, the emoji that burst with it. Later themes win (😇 is "pray", not "happy").
  private static let members: [(String, String)] = [
    ("love", "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 🩷 🩵 🩶 💖 💗 💓 💞 💕 💘 💝 ❣️ 💟 😍 🥰 😻 💑 💏 🫶 ❤️‍🔥 ❤️‍🩹 🌹"),
    ("laugh", "😂 🤣 😆 😹 😝 😜 🤪 😛"),
    ("happy", "😀 😃 😄 😁 😊 🙂 ☺️ 😇 🥲 😸 😺 😌 🤗 🫠 🙃"),
    ("cool", "😎 🤩 🕶️ 🆒"),
    ("fire", "🔥 🌶️ 🫕"),
    ("approve", "👍 🤙 🫡"),
    ("clap", "👏 🙌 🤝"),
    ("wow", "😮 😯 😲 🤯 😳 🫢 😦 😧 👀 ‼️ ⁉️ ❗ ❕"),
    ("sad", "😢 😭 🥹 😞 😔 😟 🙁 ☹️ 😿 💔 🥀 😥 😓 😪"),
    ("angry", "😡 😠 🤬 👿 😤 💢 😾"),
    ("scared", "😱 😨 😰 🫣"),
    ("party", "🎉 🎊 🥳 🪅 🎈 🪩 🎆 🎇 🧨"),
    ("birthday", "🎂 🍰 🧁 🕯️"),
    ("gift", "🎁 🎀 💌"),
    ("pray", "🙏 🕊️ 😇 🪬"),
    ("hundred", "💯"),
    ("check", "✅ ✔️ ☑️ 🆗 🟢"),
    ("cross", "❌ ❎ 🚫 ⛔ ✖️ 🛑"),
    ("star", "⭐ 🌟 ✨ 💫 🌠"),
    ("money", "💸 💰 🤑 💵 💴 💶 💷 🪙 💳 💲"),
    ("win", "🏆 🥇 🥈 🥉 🏅 🎖️ 👑"),
    (
      "sport",
      "⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🏓 🏸 🏒 🏑 🥍 🏏 🥅 ⛳ 🏹 🥊 🥋 🏋️ 🤸 ⛹️ 🤾 🏌️ 🏄 🏊 🤽 🚴 🚵 🏇 🏃 🎯"
    ),
    ("music", "🎵 🎶 🎧 🎤 🎼 🎹 🥁 🪘 🎷 🎺 🪗 🎸 🪕 🎻 📻"),
    ("dance", "💃 🕺 👯 🪩"),
    ("rocket", "🚀 🛰️ 🛸 🌌"),
    ("think", "🤔 🧐 💭 🤨 🫤 😕 ❓ ❔"),
    ("idea", "💡 🧠"),
    ("kiss", "💋 😘 😗 😙 😚 😽 👄"),
    ("sleep", "😴 💤 🥱 🛌 😪"),
    ("sick", "🤒 🤕 🤢 🤮 🤧 😷 🥴 🦠 💊 🌡️"),
    ("skull", "💀 ☠️ ⚰️ 🦴"),
    ("poop", "💩"),
    ("drink", "🥂 🍾 🍻 🍺 🍷 🍸 🍹 🥃 🧉 🍶"),
    ("flower", "🌸 🌷 🌼 🌺 🌻 💐 🪷 🪻 🏵️ 💮"),
    ("nature", "🍀 ☘️ 🌿 🍃 🌱 🌲 🌳 🌴 🌵 🍂 🍁 🪴"),
    ("sun", "☀️ 🌞 🌤️ ⛅ 🌅 🌄"),
    ("night", "🌙 🌛 🌜 🌝 🌚 🌕 🌖 🌗 🌘 🌑 🌒 🌓 🌔 🌃"),
    ("rain", "🌧️ ☔ ☂️ ⛈️ ☁️ 🌦️"),
    ("snow", "❄️ ☃️ ⛄ 🌨️ 🧊"),
    ("rainbow", "🌈 🏳️‍🌈"),
    ("water", "💦 💧 🌊 🫧"),
    ("zap", "⚡ 🌩️"),
    ("boom", "💥 💣"),
    ("ghost", "👻"),
    ("alien", "👽 👾 🛸"),
    ("robot", "🤖"),
    ("cat", "🐱 🐈 😼 🙀"),
    ("dog", "🐶 🐕 🦮 🐩"),
    ("unicorn", "🦄"),
    ("muscle", "💪 🦾"),
    ("ok", "👌 🤌 🤏"),
    ("wave", "👋 🖐️ ✋ 🤚"),
    ("nope", "👎 🙅 🙄 😒 😑 😐 😶"),
    ("shock", "🫨"),
    ("shy", "🥺 🫣 ☺️"),
    ("holiday", "🎄 🎅 🤶 🧑‍🎄 ⛄"),
    ("halloween", "🎃 🦇 🕸️ 🕷️"),
  ]

  private static let themesByEmoji: [String: ExpressEmojiFamily] = {
    var byName: [String: ExpressEmojiFamily] = [:]
    for (name, rgb, set) in themes {
      byName[name] = ExpressEmojiFamily(
        color: UIColor(
          red: CGFloat((rgb >> 16) & 0xFF) / 255,
          green: CGFloat((rgb >> 8) & 0xFF) / 255,
          blue: CGFloat(rgb & 0xFF) / 255,
          alpha: 1
        ),
        set: set.split(separator: " ").map(String.init)
      )
    }
    var byEmoji: [String: ExpressEmojiFamily] = [:]
    for (name, list) in members {
      guard let family = byName[name] else { continue }
      for member in list.split(separator: " ") {
        byEmoji[normalize(String(member))] = family
      }
    }
    return byEmoji
  }()
}

// MARK: - Maths

private func rnd(_ a: CGFloat, _ b: CGFloat) -> CGFloat {
  a + CGFloat.random(in: 0..<1) * (b - a)
}

/// Roughly normal in [-1, 1].
private func gauss() -> CGFloat {
  (CGFloat.random(in: 0..<1) + CGFloat.random(in: 0..<1) + CGFloat.random(in: 0..<1) - 1.5) / 1.5
}

private func clamp(_ v: CGFloat, _ lower: CGFloat, _ upper: CGFloat) -> CGFloat {
  max(lower, min(upper, v))
}

func expressEaseOut(_ t: CGFloat) -> CGFloat {
  1 - pow(1 - t, 3)
}

private func easeOutBack(_ t: CGFloat) -> CGFloat {
  let c: CGFloat = 1.9
  return 1 + (c + 1) * pow(t - 1, 3) + c * pow(t - 1, 2)
}
