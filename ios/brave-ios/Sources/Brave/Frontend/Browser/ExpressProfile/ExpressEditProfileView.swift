// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import ExpressCore
import PhotosUI
import SwiftUI
import UIKit

// Edit profile, ported from the Android app's `BrowserExpressEditProfilePreferences`: name,
// username (checked for availability as you type) and photo.

struct ExpressEditProfileView: View {
  @ObservedObject var model: ExpressProfileModel
  /// Called when the screen closes; true when something was saved.
  var onDone: (Bool) -> Void

  private enum UsernameState: Equatable {
    case idle, badCharacters, tooShort, checking, available, taken, unknown
  }

  private enum Photo: Equatable {
    case unchanged
    case new(UIImage)
    case removed
  }

  @State private var name = ""
  @State private var username = ""
  @State private var savedName = ""
  @State private var savedUsername = ""
  @State private var didEdit = false
  @State private var usernameState: UsernameState = .idle
  @State private var checkGeneration = 0
  @State private var photo: Photo = .unchanged
  @State private var photoItem: PhotosPickerItem?
  @State private var choosingPhoto = false
  @State private var takingPhoto = false
  @State private var cropSource: ExpressCropSource?
  @State private var showingPhotoOptions = false
  @State private var isSaving = false
  @State private var confirmingDiscard = false
  @State private var errorMessage: String?
  /// The banner is about the photo (camera or library), not a failed save.
  @State private var errorIsPhoto = false

  private static let maxName = 30
  private static let maxUsername = 20

  /// The current photo is an upload (not the account's default face).
  private var hasUpload: Bool {
    switch photo {
    case .new: return true
    case .removed: return false
    case .unchanged:
      guard let url = model.avatarURL else { return false }
      return !url.absoluteString.hasPrefix("https://media.browser.express/avatars/default/")
    }
  }

  private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

  private var hasChanges: Bool {
    trimmedName != savedName || username != savedUsername || photo != .unchanged
  }

  private var canSave: Bool {
    hasChanges && !trimmedName.isEmpty && !isSaving
      && [.idle, .available, .unknown].contains(usernameState)
  }

  var body: some View {
    VStack(spacing: 0) {
      navBar
      ScrollView {
        VStack(spacing: 0) {
          photoSection
            .padding(.top, 18)
          fields
            .padding(.horizontal, 16)
            .padding(.top, 20)
          helper
            .padding(.horizontal, 32)
            .padding(.top, 8)
        }
        .padding(.bottom, 24)
      }
    }
    .background(Color(ExpressProfileColors.page).ignoresSafeArea())
    .overlay(alignment: .top) {
      if let errorMessage {
        errorBanner(errorMessage)
          .padding(.horizontal, 12)
          .padding(.top, 54)
          .transition(.opacity.combined(with: .move(edge: .top)))
      }
    }
    .task { loadInitial() }
    .onChange(of: model.me) { _, _ in
      // `/user/me` replaces the claims, unless the reader has started editing.
      if !didEdit { loadInitial() }
    }
    .confirmationDialog("Profile photo", isPresented: $showingPhotoOptions, titleVisibility: .visible) {
      Button("Take photo") { openCamera() }
      Button("Choose from library") { choosingPhoto = true }
      if hasUpload {
        Button("Remove photo", role: .destructive) {
          photo = .removed
          didEdit = true
        }
      }
      Button("Cancel", role: .cancel) {}
    }
    .photosPicker(isPresented: $choosingPhoto, selection: $photoItem, matching: .images)
    .fullScreenCover(isPresented: $takingPhoto) {
      ExpressCameraPicker(prefersFrontCamera: true) { image in
        takingPhoto = false
        if let image {
          // Let the camera go before "Move and scale" comes up.
          DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
            cropSource = ExpressCropSource(image: image)
          }
        }
      }
      .ignoresSafeArea()
    }
    .fullScreenCover(item: $cropSource) { source in
      ExpressAvatarCropView(
        image: source.image,
        onCancel: { cropSource = nil },
        onChoose: { cropped in
          cropSource = nil
          photo = .new(cropped)
          didEdit = true
        }
      )
    }
    .onChange(of: photoItem) { _, item in
      guard let item else { return }
      Task { await loadPhoto(item) }
    }
    .alert("Discard changes?", isPresented: $confirmingDiscard) {
      Button("Keep editing", role: .cancel) {}
      Button("Discard", role: .destructive) { onDone(false) }
    } message: {
      Text("Your edits won’t be saved.")
    }
  }

  // MARK: Parts

  private var navBar: some View {
    ZStack {
      Text("Edit profile")
        .font(.system(size: 16, weight: .semibold))
        .foregroundColor(Color(ExpressProfileColors.textPrimary))
      HStack {
        Button("Cancel") { cancel() }
          .font(.system(size: 15, weight: .medium))
          .foregroundColor(Color(ExpressProfileColors.textPrimary))
          .disabled(isSaving)
        Spacer()
        if isSaving {
          ProgressView().frame(width: 44)
        } else {
          Button("Save") { Task { await save() } }
            .font(.system(size: 15, weight: .semibold))
            .foregroundColor(Color(canSave ? ExpressProfileColors.link : ExpressProfileColors.textMeta))
            .disabled(!canSave)
        }
      }
    }
    .frame(height: 48)
    .padding(.horizontal, 16)
  }

  private var photoSection: some View {
    VStack(spacing: 10) {
      Button {
        showingPhotoOptions = true
      } label: {
        avatar
          .frame(width: 96, height: 96)
          .clipShape(Circle())
          .padding(1)
          .overlay(Circle().stroke(Color(ExpressProfileColors.avatarRing), lineWidth: 1))
          .overlay(alignment: .bottomTrailing) {
            Circle()
              .fill(Color(red: 0x57 / 255, green: 0x7D / 255, blue: 1))
              .frame(width: 32, height: 32)
              .overlay(Image(systemName: "camera.fill").font(.system(size: 13)).foregroundColor(.white))
              .overlay(Circle().stroke(Color(ExpressProfileColors.page), lineWidth: 3))
          }
      }
      .buttonStyle(.plain)
      .accessibilityLabel(hasUpload ? "Change photo" : "Add photo")
      Button(hasUpload ? "Change photo" : "Add photo") { showingPhotoOptions = true }
        .font(.system(size: 14, weight: .semibold))
        .foregroundColor(Color(ExpressProfileColors.link))
    }
  }

  @ViewBuilder private var avatar: some View {
    switch photo {
    case .new(let image):
      Image(uiImage: image).resizable().scaledToFill()
    case .removed:
      // Back to the account's default face.
      ExpressAvatarView(url: ExpressAvatar.resolve(nil, seed: model.claims?.userID), size: 96)
    case .unchanged:
      ExpressAvatarView(url: model.avatarURL, size: 96)
    }
  }

  private var fields: some View {
    VStack(spacing: 0) {
      VStack(alignment: .leading, spacing: 4) {
        HStack {
          Text("Name")
          Spacer()
          Text("\(trimmedName.count)/\(Self.maxName)")
        }
        .font(.system(size: 12, weight: .medium))
        .foregroundColor(Color(ExpressProfileColors.textMeta))
        ExpressFilteredTextField(
          text: Binding(get: { name }, set: nameChanged),
          placeholder: "Your name",
          textColor: ExpressProfileColors.textPrimary,
          autocapitalization: .words,
          maxLength: Self.maxName
        )
        .frame(height: 22)
      }
      .padding(.horizontal, 16)
      .padding(.vertical, 12)
      Rectangle()
        .fill(Color(ExpressProfileColors.divider))
        .frame(height: 1)
        .padding(.leading, 16)
      VStack(alignment: .leading, spacing: 4) {
        Text("Username")
          .font(.system(size: 12, weight: .medium))
          .foregroundColor(Color(ExpressProfileColors.textMeta))
        HStack(spacing: 2) {
          Text("@")
            .font(.system(size: 16, weight: .medium))
            .foregroundColor(Color(ExpressProfileColors.textMeta))
          // Lowercase with no spaces, as typed (Android's input filter).
          ExpressFilteredTextField(
            text: Binding(get: { username }, set: usernameChanged),
            placeholder: "username",
            textColor: ExpressProfileColors.textPrimary,
            autocapitalization: .none,
            autocorrection: .no,
            filter: { $0.lowercased().filter { !$0.isWhitespace } },
            maxLength: Self.maxUsername
          )
          .frame(height: 22)
          usernameStatus.frame(width: 18, height: 18)
        }
      }
      .padding(.horizontal, 16)
      .padding(.vertical, 12)
    }
    .background(
      RoundedRectangle(cornerRadius: 18)
        .fill(Color(ExpressProfileColors.card))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color(ExpressProfileColors.cardEdge), lineWidth: 1))
    )
  }

  @ViewBuilder private var usernameStatus: some View {
    switch usernameState {
    case .checking:
      ProgressView().scaleEffect(0.7)
    case .available:
      Image(systemName: "checkmark.circle.fill").foregroundColor(Color(ExpressProfileColors.success))
    case .badCharacters, .tooShort, .taken:
      Image(systemName: "exclamationmark.circle.fill").foregroundColor(Color(ExpressProfileColors.danger))
    case .idle, .unknown:
      Color.clear
    }
  }

  private var helper: some View {
    let (text, color): (String, UIColor) = {
      switch usernameState {
      case .idle, .unknown:
        return ("Letters, numbers and underscores. 3–20 characters.", ExpressProfileColors.textSecondary)
      case .badCharacters:
        return ("Only letters, numbers and underscores.", ExpressProfileColors.danger)
      case .tooShort:
        return ("At least 3 characters.", ExpressProfileColors.danger)
      case .checking:
        return ("Checking availability…", ExpressProfileColors.textMeta)
      case .available:
        return ("@\(username) is available", ExpressProfileColors.success)
      case .taken:
        return ("@\(username) is already taken", ExpressProfileColors.danger)
      }
    }()
    return Text(text)
      .font(.system(size: 12.5))
      .foregroundColor(Color(color))
      .frame(maxWidth: .infinity, alignment: .leading)
  }

  private func errorBanner(_ detail: String) -> some View {
    HStack(alignment: .top, spacing: 10) {
      Circle()
        .fill(Color(ExpressProfileColors.danger))
        .frame(width: 8, height: 8)
        .padding(.top, 6)
      VStack(alignment: .leading, spacing: 2) {
        Text(errorIsPhoto ? "Profile photo" : "Couldn’t save your profile")
          .font(.system(size: 14, weight: .semibold))
          .foregroundColor(Color(ExpressProfileColors.textPrimary))
        Text(detail)
          .font(.system(size: 12))
          .foregroundColor(Color(ExpressProfileColors.textSecondary))
      }
      Spacer(minLength: 0)
    }
    .padding(.horizontal, 14)
    .padding(.vertical, 11)
    .background(
      RoundedRectangle(cornerRadius: 14)
        .fill(Color(ExpressProfileColors.card))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color(ExpressProfileColors.cardEdge), lineWidth: 1))
        .shadow(color: .black.opacity(0.12), radius: 8, y: 3)
    )
    .onTapGesture { withAnimation { errorMessage = nil } }
    .task(id: detail) {
      try? await Task.sleep(nanoseconds: 4_000_000_000)
      withAnimation { errorMessage = nil }
    }
  }

  // MARK: Behaviour

  /// The claims first, then `/user/me` (the screen's model refreshes it).
  private func loadInitial() {
    let claims = model.claims
    savedName = model.me?.name ?? claims?.name ?? ""
    savedUsername = model.me?.username ?? claims?.username ?? ""
    name = savedName
    username = savedUsername
    usernameState = .idle
    didEdit = false
  }

  /// The field keeps names to 30 characters as typed (a longer saved name stays until edited).
  private func nameChanged(_ new: String) {
    name = new
    if name != savedName { didEdit = true }
  }

  /// The field has already lowercased it and dropped spaces; checked after a 550 ms pause,
  /// newest answer wins.
  private func usernameChanged(_ new: String) {
    let cleaned = new
    guard cleaned != username else { return }
    username = cleaned
    if cleaned != savedUsername { didEdit = true }
    checkGeneration += 1
    let generation = checkGeneration
    if cleaned == savedUsername {
      usernameState = .idle
    } else if cleaned.range(of: "^[a-z0-9_]*$", options: .regularExpression) == nil {
      usernameState = .badCharacters
    } else if cleaned.count < 3 {
      usernameState = .tooShort
    } else {
      usernameState = .checking
      Task {
        try? await Task.sleep(nanoseconds: 550_000_000)
        guard generation == checkGeneration, let token = ExpressAccount.shared.validAccessToken else {
          if generation == checkGeneration { usernameState = .unknown }
          return
        }
        let available = await ExpressAPIClient.shared.isUsernameAvailable(cleaned, accessToken: token)
        guard generation == checkGeneration else { return }
        switch available {
        case .some(true): usernameState = .available
        case .some(false): usernameState = .taken
        case .none: usernameState = .unknown
        }
      }
    }
  }

  private func cancel() {
    guard !isSaving else { return }
    if hasChanges {
      confirmingDiscard = true
    } else {
      onDone(false)
    }
  }

  /// Opens the chosen photo in "Move and scale".
  private func loadPhoto(_ item: PhotosPickerItem) async {
    defer { photoItem = nil }
    guard let data = try? await item.loadTransferable(type: Data.self),
      let image = UIImage(data: data)
    else {
      withAnimation { errorIsPhoto = true; errorMessage = "Couldn’t open that photo" }
      return
    }
    cropSource = ExpressCropSource(image: image)
  }

  /// "Take photo": the front camera, after asking for access the first time.
  private func openCamera() {
    guard ExpressCameraPicker.isAvailable else {
      withAnimation { errorIsPhoto = true; errorMessage = "Couldn’t open the camera." }
      return
    }
    Task {
      if await ExpressCameraPicker.requestAccess() {
        takingPhoto = true
      } else {
        withAnimation { errorIsPhoto = true; errorMessage = "Camera access is off. Turn it on in Settings." }
      }
    }
  }

  /// Name and username, then the photo, then a fresh `/user/me`; the spinner shows for at least
  /// 800 ms.
  private func save() async {
    guard canSave, let token = ExpressAccount.shared.validAccessToken else { return }
    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    isSaving = true
    let started = Date()
    defer { isSaving = false }
    var accessToken = token
    var saved = false

    if trimmedName != savedName || username != savedUsername {
      switch await ExpressAPIClient.shared.updateProfile(
        name: trimmedName,
        username: username,
        accessToken: token
      ) {
      case .saved(let access, let refresh):
        model.store(accessToken: access, refreshToken: refresh)
        accessToken = access
        savedName = trimmedName
        savedUsername = username
        saved = true
      case .usernameTaken:
        usernameState = .taken
        return
      case .failed(let message):
        withAnimation { errorIsPhoto = false; errorMessage = message ?? "Check your connection and try again." }
        return
      }
    }

    switch photo {
    case .new(let image):
      guard let jpeg = image.jpegData(compressionQuality: 0.9) else { break }
      if let message = await ExpressAPIClient.shared.uploadAvatar(jpeg: jpeg, accessToken: accessToken) {
        withAnimation { errorIsPhoto = false; errorMessage = message.isEmpty ? "Check your connection and try again." : message }
        return
      }
      saved = true
    case .removed:
      guard await ExpressAPIClient.shared.deleteAvatar(accessToken: accessToken) else {
        withAnimation {
          errorIsPhoto = false
          errorMessage = ExpressDebug.isDryRun ? "Dry run: nothing was saved." : "Check your connection and try again."
        }
        return
      }
      saved = true
    case .unchanged:
      break
    }

    await model.refresh()
    let elapsed = Date().timeIntervalSince(started)
    if elapsed < 0.8 {
      try? await Task.sleep(nanoseconds: UInt64((0.8 - elapsed) * 1_000_000_000))
    }
    onDone(saved)
  }
}
