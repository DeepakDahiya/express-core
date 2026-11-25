// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import SwiftUI
import UIKit

/// A photo waiting to be framed for the profile picture.
struct ExpressCropSource: Identifiable {
  let id = UUID()
  let image: UIImage
}

/// "Move and scale" (Android's `EditProfileCropView`): the photo fills a square, a circle shows
/// what the picture keeps, pinch or the slider zooms 1–2×, and a drag moves it. The circle is
/// always covered.
struct ExpressAvatarCropView: View {
  let image: UIImage
  var onCancel: () -> Void
  var onChoose: (UIImage) -> Void

  @State private var zoom: CGFloat = 1
  @State private var offset: CGSize = .zero
  @State private var pinchStartZoom: CGFloat?
  @State private var dragStartOffset: CGSize?
  @State private var appeared = false
  @State private var measuredSide: CGFloat = 0

  private static let minZoom: CGFloat = 1
  private static let maxZoom: CGFloat = 2
  /// Android: the hole is 300/402 of the square.
  private static let holeFraction: CGFloat = 300 / 402

  /// The photo, upright and at most 2048 px on its long edge.
  private let upright: UIImage

  init(image: UIImage, onCancel: @escaping () -> Void, onChoose: @escaping (UIImage) -> Void) {
    self.image = image
    self.onCancel = onCancel
    self.onChoose = onChoose
    self.upright = ExpressDraftFactory.scaledUpright(image, maxEdge: 2048)
  }

  var body: some View {
    VStack(spacing: 0) {
      topBar
      GeometryReader { proxy in
        let side = min(proxy.size.width, proxy.size.height)
        cropArea(side: side)
          .frame(width: side, height: side)
          .frame(maxWidth: .infinity, maxHeight: .infinity)
      }
      .onGeometryChange(for: CGFloat.self) { min($0.size.width, $0.size.height) } action: {
        measuredSide = $0
      }
      zoomRow
    }
    .background(Color.black.ignoresSafeArea())
    .opacity(appeared ? 1 : 0)
    .onAppear { withAnimation(.easeOut(duration: 0.2)) { appeared = true } }
    .preferredColorScheme(.dark)
  }

  private var topBar: some View {
    ZStack {
      Text("Move and scale")
        .font(.system(size: 16, weight: .semibold))
        .foregroundColor(.white)
      HStack {
        Button("Cancel", action: onCancel)
          .font(.system(size: 15, weight: .medium))
          .foregroundColor(.white)
          .padding(.horizontal, 12)
          .frame(minHeight: 44)
        Spacer()
        Button("Choose") { onChoose(cropped()) }
          .font(.system(size: 15, weight: .semibold))
          .foregroundColor(Color(UIColor(expressRGB: 0x6EA8FF)))
          .padding(.horizontal, 12)
          .frame(minHeight: 44)
      }
    }
    .padding(.horizontal, 8)
    .frame(height: 48)
  }

  private func baseScale(side: CGFloat) -> CGFloat {
    max(side / upright.size.width, side / upright.size.height)
  }

  private func cropArea(side: CGFloat) -> some View {
    let hole = side * Self.holeFraction
    let base = baseScale(side: side)
    // Everything hangs off one square, so the photo (often wider or taller) can't stretch the
    // layout and the mask lines up with the ring.
    return Color.black
      .frame(width: side, height: side)
      .overlay {
        Image(uiImage: upright)
          .resizable()
          .frame(width: upright.size.width * base, height: upright.size.height * base)
          .scaleEffect(zoom)
          .offset(offset)
      }
      .overlay {
        // The mask: everything dimmed but the circle, which gets a thin ring.
        Path { path in
          path.addRect(CGRect(x: 0, y: 0, width: side, height: side))
          path.addEllipse(in: CGRect(x: (side - hole) / 2, y: (side - hole) / 2, width: hole, height: hole))
        }
        .fill(Color.black.opacity(0x9E / 255.0), style: FillStyle(eoFill: true))
        .frame(width: side, height: side)
      }
      .overlay {
        Circle()
          .strokeBorder(Color.white.opacity(0x99 / 255.0), lineWidth: 1)
          .frame(width: hole, height: hole)
      }
    .clipped()
    .contentShape(Rectangle())
    .gesture(
      DragGesture()
        .onChanged { value in
          guard pinchStartZoom == nil else { return }
          let start = dragStartOffset ?? offset
          dragStartOffset = start
          offset = clamped(
            CGSize(width: start.width + value.translation.width, height: start.height + value.translation.height),
            zoom: zoom,
            side: side
          )
        }
        .onEnded { _ in dragStartOffset = nil }
        .simultaneously(
          with: MagnifyGesture()
            .onChanged { value in
              let start = pinchStartZoom ?? zoom
              pinchStartZoom = start
              setZoom(start * value.magnification, side: side)
            }
            .onEnded { _ in pinchStartZoom = nil }
        )
    )
    .onChange(of: zoom) { _, newZoom in
      offset = clamped(offset, zoom: newZoom, side: side)
    }
    .accessibilityElement()
    .accessibilityLabel("Photo framing")
  }

  /// Zooms about the circle's centre: the pan scales with the zoom.
  private func setZoom(_ value: CGFloat, side: CGFloat) {
    let newZoom = min(Self.maxZoom, max(Self.minZoom, value))
    let ratio = newZoom / zoom
    zoom = newZoom
    offset = clamped(CGSize(width: offset.width * ratio, height: offset.height * ratio), zoom: newZoom, side: side)
  }

  /// Keeps the circle covered on both axes.
  private func clamped(_ proposed: CGSize, zoom: CGFloat, side: CGFloat) -> CGSize {
    let base = baseScale(side: side)
    let shownWidth = upright.size.width * base * zoom
    let shownHeight = upright.size.height * base * zoom
    let hole = side * Self.holeFraction
    let limitX = max(0, (shownWidth - hole) / 2)
    let limitY = max(0, (shownHeight - hole) / 2)
    return CGSize(
      width: min(limitX, max(-limitX, proposed.width)),
      height: min(limitY, max(-limitY, proposed.height))
    )
  }

  private var zoomRow: some View {
    HStack(spacing: 14) {
      Image(systemName: "minus.magnifyingglass")
        .font(.system(size: 16))
        .foregroundColor(Color(UIColor(expressRGB: 0xA8ABB5)))
      Slider(value: $zoom, in: Self.minZoom...Self.maxZoom)
        .tint(Color(UIColor(expressRGB: 0x577DFF)))
        .accessibilityLabel("Zoom")
      Image(systemName: "plus.magnifyingglass")
        .font(.system(size: 22))
        .foregroundColor(Color(UIColor(expressRGB: 0xA8ABB5)))
    }
    .padding(.horizontal, 32)
    .padding(.top, 20)
    .padding(.bottom, 32)
  }

  /// What the circle shows, as a square of at most 512 px (never upscaled past the photo).
  private func cropped() -> UIImage {
    let screenSide =
      measuredSide > 0 ? measuredSide : min(UIScreen.main.bounds.width, UIScreen.main.bounds.height)
    // Work in the photo's own pixels from here.
    let base = baseScale(side: screenSide)
    let displayScale = base * zoom
    let hole = screenSide * Self.holeFraction
    let photo = upright.size
    let cropSide = hole / displayScale
    let center = CGPoint(
      x: photo.width / 2 - offset.width / displayScale,
      y: photo.height / 2 - offset.height / displayScale
    )
    let crop = CGRect(x: center.x - cropSide / 2, y: center.y - cropSide / 2, width: cropSide, height: cropSide)
    let output = min(512, (cropSide * upright.scale).rounded())
    let format = UIGraphicsImageRendererFormat.default()
    format.scale = 1
    format.opaque = true
    return UIGraphicsImageRenderer(size: CGSize(width: output, height: output), format: format).image { context in
      UIColor.white.setFill()
      context.fill(CGRect(x: 0, y: 0, width: output, height: output))
      let factor = output / cropSide
      upright.draw(
        in: CGRect(
          x: -crop.minX * factor,
          y: -crop.minY * factor,
          width: photo.width * factor,
          height: photo.height * factor
        )
      )
    }
  }
}
