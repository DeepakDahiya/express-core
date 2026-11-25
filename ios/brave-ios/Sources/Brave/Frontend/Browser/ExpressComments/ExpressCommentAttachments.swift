// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import AVFoundation
import QuickLook
import BraveUI
import ExpressCore
import SDWebImage
import SwiftUI
import UIKit
import UniformTypeIdentifiers
import os.log

// Attaching files to a comment and showing the files of posted comments (Android's
// `CommentAttachmentSheet`, `GifPickerSheet`, `CommentAttachmentRows` and `MediaViewerFragment`).

extension ExpressColors {
  static let tileStroke = UIColor(expressLight: 0xD9DBE8, expressDark: 0x3A3D45)
  static let tileFill = UIColor(expressLight: 0xF2F5FC, expressDark: 0x232733)
  static let tileMeta = UIColor(expressLight: 0x737585, expressDark: 0xA8ABB5)
  static let mediaBackground = UIColor(expressLight: 0x202124, expressDark: 0x202124)
  static let draftMeta = UIColor(expressLight: 0x747480, expressDark: 0xA8ABB5)
}

/// Opens a link from inside the comments (documents): the browser shows http(s) links in an
/// in-app Safari view over the comments (`ExpressDocumentBrowser`), so closing it lands back on
/// the same comment, as Android's custom tab does.
private struct ExpressOpenURLKey: EnvironmentKey {
  static let defaultValue: ((URL) -> Void)? = nil
}

extension EnvironmentValues {
  var expressOpenURL: ((URL) -> Void)? {
    get { self[ExpressOpenURLKey.self] }
    set { self[ExpressOpenURLKey.self] = newValue }
  }
}

// MARK: - Animated images

/// An image that animates when it's a GIF (SwiftUI's `Image` shows only the first frame). It
/// never takes touches, so buttons and tap gestures around it still work.
struct ExpressAnimatedImage: View {
  let url: URL
  var contentMode: UIView.ContentMode = .scaleAspectFill

  var body: some View {
    ExpressAnimatedImageView(url: url, contentMode: contentMode)
      .allowsHitTesting(false)
  }
}

private struct ExpressAnimatedImageView: UIViewRepresentable {
  let url: URL
  var contentMode: UIView.ContentMode

  func makeUIView(context: Context) -> SDAnimatedImageView {
    let view = SDAnimatedImageView()
    view.isUserInteractionEnabled = false
    view.contentMode = contentMode
    view.clipsToBounds = true
    view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
    view.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
    view.setContentHuggingPriority(.defaultLow, for: .horizontal)
    view.setContentHuggingPriority(.defaultLow, for: .vertical)
    view.sd_setImage(with: url)
    return view
  }

  func updateUIView(_ view: SDAnimatedImageView, context: Context) {
    view.contentMode = contentMode
    if view.sd_imageURL != url {
      view.sd_setImage(with: url)
    }
  }
}

// MARK: - Attach sheet

enum ExpressAttachChoice: String, CaseIterable, Identifiable {
  case photo, camera, video, voice, gif, document
  var id: String { rawValue }

  var title: String {
    switch self {
    case .photo: return "Photo"
    case .camera: return "Camera"
    case .video: return "Video"
    case .voice: return "Voice note"
    case .gif: return "GIF"
    case .document: return "Document"
    }
  }

  var symbol: String {
    switch self {
    case .photo: return "photo.on.rectangle"
    case .camera: return "camera.fill"
    case .video: return "video.fill"
    case .voice: return "mic.fill"
    case .gif: return "face.smiling"
    case .document: return "doc.fill"
    }
  }

  /// The take picker's tile colours.
  var tint: UInt32 {
    switch self {
    case .photo: return 0x5B7CFF
    case .camera: return 0xA26BFF
    case .video: return 0xFF5C7A
    case .voice: return 0x2FBF71
    case .gif: return 0xF2B227
    case .document: return 0x3AA3E8
    }
  }
}

/// "Add to comment": six tiles in two rows.
struct ExpressAttachSheet: View {
  var onChoose: (ExpressAttachChoice) -> Void
  var onClose: () -> Void
  @Environment(\.colorScheme) private var colorScheme

  var body: some View {
    VStack(alignment: .leading, spacing: 0) {
      HStack(alignment: .top) {
        VStack(alignment: .leading, spacing: 2) {
          Text("Add to comment")
            .font(.system(size: 18, weight: .semibold))
            .foregroundColor(Color(ExpressColors.commentBody))
          Text("Choose what you want to share")
            .font(.system(size: 12))
            .foregroundColor(Color(ExpressColors.commentAuthor))
        }
        Spacer()
        Button(action: onClose) {
          Image(systemName: "xmark")
            .font(.system(size: 14, weight: .semibold))
            .foregroundColor(Color(ExpressColors.commentAuthor))
            .frame(width: 36, height: 36)
        }
        .accessibilityLabel("Close")
      }
      .padding(.bottom, 16)
      LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 3), spacing: 12) {
        ForEach(ExpressAttachChoice.allCases) { choice in
          Button {
            onChoose(choice)
          } label: {
            VStack(spacing: 8) {
              Circle()
                .fill(Color(UIColor(expressRGB: choice.tint)).opacity(colorScheme == .dark ? 0.22 : 0.14))
                .frame(width: 52, height: 52)
                .overlay(
                  Image(systemName: choice.symbol)
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundColor(Color(UIColor(expressRGB: choice.tint)))
                )
              Text(choice.title)
                .font(.system(size: 12, weight: .medium))
                .foregroundColor(Color(ExpressColors.commentBody))
            }
            .frame(maxWidth: .infinity, minHeight: 88)
            .contentShape(Rectangle())
          }
          .buttonStyle(.plain)
        }
      }
      Text("You can add multiple photos, documents, voice notes and reels.")
        .font(.system(size: 11))
        .foregroundColor(Color(ExpressColors.commentAuthor))
        .padding(.top, 14)
    }
    .padding(.horizontal, 20)
    .padding(.top, 20)
    .padding(.bottom, 12)
  }
}

extension UIColor {
  /// An opaque colour from 0xRRGGBB.
  convenience init(expressRGB rgb: UInt32) {
    self.init(
      red: CGFloat((rgb >> 16) & 0xFF) / 255,
      green: CGFloat((rgb >> 8) & 0xFF) / 255,
      blue: CGFloat(rgb & 0xFF) / 255,
      alpha: 1
    )
  }
}

// MARK: - Camera and Files

/// The system camera for one still photo.
struct ExpressCameraPicker: UIViewControllerRepresentable {
  var prefersFrontCamera = false
  var onPicked: (UIImage?) -> Void

  static var isAvailable: Bool { UIImagePickerController.isSourceTypeAvailable(.camera) }

  func makeUIViewController(context: Context) -> UIImagePickerController {
    let picker = UIImagePickerController()
    picker.sourceType = .camera
    picker.mediaTypes = [UTType.image.identifier]
    if prefersFrontCamera, UIImagePickerController.isCameraDeviceAvailable(.front) {
      picker.cameraDevice = .front
    }
    picker.delegate = context.coordinator
    return picker
  }

  func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

  func makeCoordinator() -> Coordinator { Coordinator(onPicked: onPicked) }

  final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
    let onPicked: (UIImage?) -> Void
    init(onPicked: @escaping (UIImage?) -> Void) { self.onPicked = onPicked }

    func imagePickerController(
      _ picker: UIImagePickerController,
      didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
    ) {
      onPicked(info[.originalImage] as? UIImage)
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
      onPicked(nil)
    }
  }

  /// Camera access: asks the first time; false when it's off.
  static func requestAccess() async -> Bool {
    switch AVCaptureDevice.authorizationStatus(for: .video) {
    case .authorized: return true
    case .notDetermined: return await AVCaptureDevice.requestAccess(for: .video)
    default: return false
    }
  }
}

/// Files, filtered to Android's document types, several at once, as copies.
struct ExpressDocumentPicker: UIViewControllerRepresentable {
  var onPicked: ([URL]) -> Void

  func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
    let picker = UIDocumentPickerViewController(
      forOpeningContentTypes: ExpressDraftLimits.documentTypes,
      asCopy: true
    )
    picker.allowsMultipleSelection = true
    picker.delegate = context.coordinator
    return picker
  }

  func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

  func makeCoordinator() -> Coordinator { Coordinator(onPicked: onPicked) }

  final class Coordinator: NSObject, UIDocumentPickerDelegate {
    let onPicked: ([URL]) -> Void
    init(onPicked: @escaping ([URL]) -> Void) { self.onPicked = onPicked }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
      onPicked(urls)
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
      onPicked([])
    }
  }
}

// MARK: - GIF picker

@MainActor
final class ExpressGIFPickerModel: ObservableObject {
  @Published var query = ""
  @Published private(set) var gifs: [ExpressGIFPage.GIF] = []
  @Published private(set) var provider: String?
  @Published private(set) var message: String?
  @Published private(set) var isLoading = false
  @Published private(set) var downloadingID: String?

  private var next: String?
  private var generation = 0
  private var searchTask: Task<Void, Never>?

  /// Typing searches after 350 ms; Return searches at once.
  func queryChanged() {
    searchTask?.cancel()
    searchTask = Task { [weak self] in
      try? await Task.sleep(nanoseconds: 350_000_000)
      guard !Task.isCancelled else { return }
      await self?.load(reset: true)
    }
  }

  func load(reset: Bool) async {
    if reset {
      generation += 1
      next = nil
    } else if next == nil || isLoading {
      return
    }
    let current = generation
    isLoading = true
    defer { if current == generation { isLoading = false } }
    do {
      let page = try await ExpressAPIClient.shared.searchGIFs(
        query: query.trimmingCharacters(in: .whitespacesAndNewlines),
        position: reset ? nil : next
      )
      guard current == generation else { return }
      gifs = reset ? page.results : gifs + page.results
      provider = page.provider ?? provider
      next = page.next
      message = gifs.isEmpty ? "No GIFs found." : nil
    } catch is ExpressGIFSearchUnavailable {
      guard current == generation else { return }
      message = "GIF search isn’t available right now."
    } catch {
      guard current == generation else { return }
      if gifs.isEmpty || reset { message = "Couldn’t load that GIF." }
    }
  }

  /// Downloads the full GIF (at most 15 MB) as a draft item.
  func download(_ gif: ExpressGIFPage.GIF) async -> ExpressDraftItem? {
    downloadingID = gif.id
    defer { downloadingID = nil }
    guard let (data, response) = try? await URLSession.shared.data(from: gif.url),
      (response as? HTTPURLResponse)?.statusCode == 200,
      data.count <= ExpressDraftLimits.gifMaxBytes
    else {
      message = "Couldn’t load that GIF."
      return nil
    }
    let safeName = "gif_" + gif.id.filter { $0.isLetter || $0.isNumber } + ".gif"
    return try? ExpressDraftFactory.gif(data: data, name: safeName)
  }
}

/// "GIFs": search with trending first, a two-column grid, one pick.
struct ExpressGIFPicker: View {
  var onPicked: (ExpressDraftItem) -> Void
  var onClose: () -> Void
  @StateObject private var model = ExpressGIFPickerModel()

  var body: some View {
    VStack(spacing: 0) {
      HStack {
        Text("GIFs")
          .font(.system(size: 20, weight: .semibold))
          .foregroundColor(Color(ExpressColors.commentBody))
        Spacer()
        Button(action: onClose) {
          Image(systemName: "xmark")
            .font(.system(size: 15, weight: .semibold))
            .foregroundColor(Color(ExpressColors.commentAuthor))
            .frame(width: 44, height: 44)
        }
        .accessibilityLabel("Close")
      }
      .padding(.leading, 16)
      .padding(.trailing, 4)
      .padding(.top, 8)
      HStack(spacing: 8) {
        Image(systemName: "magnifyingglass").foregroundColor(Color(ExpressColors.commentTime))
        TextField("Search GIFs", text: $model.query)
          .font(.system(size: 15))
          .submitLabel(.search)
          .onSubmit { Task { await model.load(reset: true) } }
          .onChange(of: model.query) { model.queryChanged() }
      }
      .padding(.horizontal, 14)
      .frame(height: 44)
      .background(RoundedRectangle(cornerRadius: 22).fill(Color(ExpressColors.composerField)))
      .overlay(RoundedRectangle(cornerRadius: 22).stroke(Color(ExpressColors.composerStroke), lineWidth: 1))
      .padding(.horizontal, 16)
      .padding(.bottom, 8)

      ZStack {
        ScrollView {
          grid
            .padding(.horizontal, 12)
            .padding(.bottom, 8)
        }
        if let message = model.message {
          Text(message)
            .font(.system(size: 14))
            .foregroundColor(Color(ExpressColors.commentAuthor))
        } else if model.isLoading && model.gifs.isEmpty {
          ProgressView()
        }
      }
      if let provider = model.provider {
        Text(provider.lowercased().contains("tenor") ? "Powered by Tenor" : "Powered by GIPHY")
          .font(.system(size: 10))
          .foregroundColor(Color(ExpressColors.commentTime))
          .padding(.vertical, 6)
      }
    }
    .background(Color(ExpressColors.commentsBackground).ignoresSafeArea())
    .task { await model.load(reset: true) }
  }

  /// Two staggered columns: each GIF goes under the shorter column.
  private var grid: some View {
    let columnWidth = (UIScreen.main.bounds.width - 24 - 8) / 2
    let columns = layout(columnWidth: columnWidth)
    return HStack(alignment: .top, spacing: 8) {
      ForEach(0..<2, id: \.self) { column in
        LazyVStack(spacing: 8) {
          ForEach(columns.items[column], id: \.gif.id) { entry in
            cell(entry.gif, height: entry.height)
              .frame(width: columnWidth, height: entry.height)
              .onAppear {
                if entry.index >= model.gifs.count - 6 {
                  Task { await model.load(reset: false) }
                }
              }
          }
        }
        .frame(width: columnWidth)
      }
    }
  }

  private struct Entry {
    let gif: ExpressGIFPage.GIF
    let height: CGFloat
    let index: Int
  }

  private func layout(columnWidth: CGFloat) -> (items: [[Entry]], height: CGFloat) {
    var items: [[Entry]] = [[], []]
    var heights: [CGFloat] = [0, 0]
    for (index, gif) in model.gifs.enumerated() {
      let height: CGFloat =
        gif.width > 0 && gif.height > 0
        ? max(columnWidth / 3, columnWidth * CGFloat(gif.height) / CGFloat(gif.width))
        : columnWidth * 3 / 4
      let column = heights[0] <= heights[1] ? 0 : 1
      items[column].append(Entry(gif: gif, height: height, index: index))
      heights[column] += height + 8
    }
    return (items, max(heights[0], heights[1]))
  }

  private func cell(_ gif: ExpressGIFPage.GIF, height: CGFloat) -> some View {
    Button {
      Task {
        if let item = await model.download(gif) { onPicked(item) }
      }
    } label: {
      ZStack {
        Color(ExpressColors.mediaBackground)
        ExpressAnimatedImage(url: gif.previewURL)
        if model.downloadingID == gif.id {
          Color.black.opacity(0.4)
          ProgressView().tint(.white)
        }
      }
      .clipShape(RoundedRectangle(cornerRadius: 6))
    }
    .buttonStyle(.plain)
    .disabled(model.downloadingID != nil)
    .accessibilityLabel("GIF")
  }
}

// MARK: - Posted comments' files

/// The files of a posted comment below its text: one image, GIF, voice note or document as its
/// own row, or several as a strip of tiles.
struct ExpressCommentFilesView: View {
  let comment: ExpressComment
  var onOpenImage: (URL) -> Void
  var onOpenVideo: ((URL) -> Void)?

  @Environment(\.expressOpenURL) private var openURL
  @State private var previewURL: URL?

  var body: some View {
    if comment.attachments.count > 1 {
      tiles
    } else if let audio = comment.audioURL, comment.videoURL == nil, comment.imageURL == nil {
      ExpressVoiceNoteRow(url: audio, durationMs: comment.audioDurationMs)
    } else if let name = comment.documentName, comment.videoURL == nil, comment.imageURL == nil {
      documentRow(name: name, url: comment.documentURL, mime: comment.documentMime, size: comment.documentSize)
    }
  }

  private var tiles: some View {
    ScrollView(.horizontal, showsIndicators: false) {
      HStack(spacing: 8) {
        ForEach(Array(comment.attachments.enumerated()), id: \.offset) { _, file in
          tile(file)
        }
      }
    }
    .quickLookPreview($previewURL)
    .modifier(ExpressCommentsCoverWhile(isActive: previewURL != nil))
  }

  @ViewBuilder private func tile(_ file: ExpressCommentAttachment) -> some View {
    let shape = RoundedRectangle(cornerRadius: 8)
    Button {
      switch file.kind {
      case .image, .gif: onOpenImage(file.url)
      case .video:
        guard let onOpenVideo else { break }
        // The viewer plays with sound: a voice note pauses first (one sound at a time).
        ExpressAudioPlayback.shared.pause()
        onOpenVideo(file.url)
      case .audio, .document:
        if file.url.isFileURL { previewURL = file.url } else { openURL?(file.url) }
      }
    } label: {
      Group {
        switch file.kind {
        case .image, .gif:
          ExpressAnimatedImage(url: file.url)
        case .video:
          ExpressVideoStill(url: file.url)
            .overlay(
              Image(systemName: "play.circle.fill")
                .font(.system(size: 24))
                .foregroundStyle(.white.opacity(0.9))
            )
        case .audio, .document:
          VStack(alignment: .leading, spacing: 8) {
            Text(file.kind == .audio ? "Voice note" : Self.typeLabel(name: file.name, mime: file.mime))
              .font(.system(size: 11, weight: .semibold))
              .foregroundColor(Color(ExpressColors.accent))
              .lineLimit(1)
            Text(
              file.kind == .audio
                ? ExpressDraftFactory.durationText(milliseconds: file.durationMs)
                : (file.size > 0 ? ExpressDraftFactory.sizeText(file.size) : "")
            )
            .font(.system(size: 9))
            .foregroundColor(Color(ExpressColors.tileMeta))
          }
          .padding(9)
          .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
          .background(Color(ExpressColors.tileFill))
        }
      }
      .frame(width: 88, height: 56)
      .clipShape(shape)
      .overlay(shape.stroke(Color(ExpressColors.tileStroke), lineWidth: 1))
    }
    .buttonStyle(.plain)
    .accessibilityLabel(file.kind == .document ? (file.name ?? "Document") : file.kind.rawValue.capitalized)
  }

  private func documentRow(name: String, url: URL?, mime: String?, size: Int64) -> some View {
    Button {
      guard let url, url.scheme?.hasPrefix("http") == true || url.isFileURL else {
        Logger.module.info("Express document tap: no openable URL")
        return
      }
      Logger.module.info(
        "Express document tap: \(url.scheme ?? "", privacy: .public), handler \(openURL == nil ? "missing" : "set", privacy: .public)"
      )
      #if DEBUG
      // `-ExpressDebugDocumentURL "https://…"`: a dry-run document opens that link instead, so
      // the in-app browser over the comments can be tried without posting a real file.
      if url.isFileURL, let debugURL = UserDefaults.standard.string(forKey: "ExpressDebugDocumentURL")
        .flatMap(URL.init(string:))
      {
        openURL?(debugURL)
        return
      }
      #endif
      if url.isFileURL {
        // A file only on this device (a dry run): the system preview shows it.
        previewURL = url
      } else {
        openURL?(url)
      }
    } label: {
      HStack(spacing: 10) {
        Image(systemName: "doc.text.fill")
          .font(.system(size: 22))
          .foregroundColor(Color(ExpressColors.accent))
          .frame(width: 40, height: 40)
        VStack(alignment: .leading, spacing: 2) {
          Text(name)
            .font(.system(size: 13, weight: .medium))
            .foregroundColor(Color(ExpressColors.commentBody))
            .lineLimit(1)
            .truncationMode(.middle)
          Text(
            [Self.typeLabel(name: name, mime: mime), size > 0 ? ExpressDraftFactory.sizeText(size) : nil]
              .compactMap { $0 }.joined(separator: " · ")
          )
          .font(.system(size: 11))
          .foregroundColor(Color(ExpressColors.voiceTimer))
        }
        Spacer(minLength: 0)
      }
      .padding(.horizontal, 12)
      .frame(height: 64)
      .background(
        RoundedRectangle(cornerRadius: 16)
          .fill(Color(ExpressColors.voiceRowFill))
          .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color(ExpressColors.voiceRowStroke), lineWidth: 1))
      )
      .contentShape(RoundedRectangle(cornerRadius: 16))
    }
    .buttonStyle(.plain)
    .quickLookPreview($previewURL)
    .modifier(ExpressCommentsCoverWhile(isActive: previewURL != nil))
    .accessibilityLabel("Open document, \(name)")
  }

  /// "PDF", "DOCX": the extension, upper-cased.
  static func typeLabel(name: String?, mime: String?) -> String {
    if let ext = name.map({ ($0 as NSString).pathExtension }), !ext.isEmpty { return ext.uppercased() }
    if let mime, let ext = UTType(mimeType: mime)?.preferredFilenameExtension { return ext.uppercased() }
    return "Document"
  }
}

/// Videos' opening frames, kept in memory (Android's `CommentVideoFrames`): tiles, the chip, and
/// the reel's next-video strip when the server sent no poster (a fresh upload). The frame comes
/// from a finished cached copy if the video has played before, else straight from the remote
/// file (AVFoundation reads the start over range requests; nothing is downloaded whole). It's
/// read off the main thread, one read per video and size at a time, and a video whose frame
/// can't be read isn't tried again this session.
@MainActor
enum ExpressVideoFrames {
  /// Roughly four 1080 px frames (Android keeps 12 MB of 720 px ones).
  private static let cache: NSCache<NSString, UIImage> = {
    let cache = NSCache<NSString, UIImage>()
    cache.totalCostLimit = 24 * 1024 * 1024
    return cache
  }()
  private static var inFlight: [String: Task<UIImage?, Never>] = [:]
  private static var failed: Set<String> = []

  private static func key(_ url: URL, _ maxPixels: CGFloat) -> String {
    "\(Int(maxPixels))|\(url.absoluteString)"
  }

  /// The frame if it has been read already, at once.
  static func cachedFrame(of url: URL, maxPixels: CGFloat) -> UIImage? {
    cache.object(forKey: key(url, maxPixels) as NSString)
  }

  /// The upright opening frame, its longer side at most `maxPixels`; nil if it can't be read.
  static func frame(of url: URL, maxPixels: CGFloat) async -> UIImage? {
    let key = key(url, maxPixels)
    if let image = cache.object(forKey: key as NSString) { return image }
    if failed.contains(key) { return nil }
    if let running = inFlight[key] { return await running.value }
    let source = ExpressMediaCache.shared.cachedFileURL(for: url) ?? url
    let task = Task.detached(priority: .utility) { () -> UIImage? in
      let generator = AVAssetImageGenerator(asset: AVURLAsset(url: source))
      generator.appliesPreferredTrackTransform = true
      generator.maximumSize = CGSize(width: maxPixels, height: maxPixels)
      guard let still = try? await generator.image(at: .zero).image else { return nil }
      return UIImage(cgImage: still)
    }
    inFlight[key] = task
    let image = await task.value
    inFlight[key] = nil
    if let image {
      let bytes = Int(image.size.width * image.size.height * image.scale * image.scale * 4)
      cache.setObject(image, forKey: key as NSString, cost: bytes)
    } else {
      failed.insert(key)
      Logger.module.info("Express video frame unavailable: \(url.lastPathComponent, privacy: .public)")
    }
    return image
  }
}

/// A video's first frame, for tiles.
struct ExpressVideoStill: View {
  let url: URL
  @State private var image: UIImage?

  var body: some View {
    ZStack {
      Color(ExpressColors.mediaBackground)
      if let image {
        Image(uiImage: image).resizable().scaledToFill()
      }
    }
    .task(id: url) {
      image = ExpressVideoFrames.cachedFrame(of: url, maxPixels: 240)
      if image == nil {
        image = await ExpressVideoFrames.frame(of: url, maxPixels: 240)
      }
    }
  }
}

/// A video's first frame, drawn like a poster image (`contentMode`), or nothing until it's read.
struct ExpressVideoFirstFrame: View {
  let url: URL
  var contentMode: ContentMode = .fill
  var maxPixels: CGFloat = 1080
  @State private var image: UIImage?

  var body: some View {
    ZStack {
      if let image {
        Image(uiImage: image)
          .resizable()
          .aspectRatio(contentMode: contentMode)
      } else {
        Color.clear
      }
    }
    .task(id: url) {
      image = ExpressVideoFrames.cachedFrame(of: url, maxPixels: maxPixels)
      if image == nil {
        image = await ExpressVideoFrames.frame(of: url, maxPixels: maxPixels)
      }
    }
  }
}

/// A full-screen picture (Android's `MediaViewerFragment` for images): black, fitted, GIFs
/// animate, and a close button at the top.
struct ExpressImageViewer: View {
  let url: URL
  @Environment(\.dismiss) private var dismiss

  var body: some View {
    ZStack(alignment: .topLeading) {
      Color.black.ignoresSafeArea()
      ExpressAnimatedImage(url: url, contentMode: .scaleAspectFit)
        .ignoresSafeArea()
      Button {
        dismiss()
      } label: {
        Image(systemName: "xmark")
          .font(.system(size: 18, weight: .semibold))
          .foregroundColor(.white)
          .frame(width: 48, height: 48)
      }
      .padding(.leading, 16)
      .accessibilityLabel("Close")
    }
    .statusBarHidden()
  }
}

struct ExpressImageItem: Identifiable {
  let url: URL
  var id: URL { url }
}
