/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#ifndef BRAVE_RENDERER_YOUTUBE_SCRIPT_INJECTOR_VIDEO_SURFACE_STREAMER_IMPL_H_
#define BRAVE_RENDERER_YOUTUBE_SCRIPT_INJECTOR_VIDEO_SURFACE_STREAMER_IMPL_H_

#include "content/public/renderer/render_frame_observer.h"
#include "mojo/public/cpp/bindings/associated_receiver.h"
#include "mojo/public/cpp/bindings/pending_associated_receiver.h"
#include "brave/browser/android/youtube_script_injector/mojom/video_surface_streamer.mojom.h"

#include <memory>

namespace blink {
class WebMediaPlayer;
class WebVideoFrameSubmitter;
}

namespace content {
class RenderFrame;
}

namespace brave {

class VideoSurfaceStreamerImpl : public content::RenderFrameObserver,
                                 public mojom::VideoSurfaceStreamer {
 public:
  static void Create(
      content::RenderFrame* render_frame,
      mojo::PendingAssociatedReceiver<mojom::VideoSurfaceStreamer> receiver);

  ~VideoSurfaceStreamerImpl() override;

  // content::RenderFrameObserver implementation
  void OnDestruct() override;

  // mojom::VideoSurfaceStreamer implementation
  void StartStreaming(gpu::SurfaceHandle surface_handle,
                     StartStreamingCallback callback) override;
  void StopStreaming() override;
  void TogglePlayback() override;

 private:
  VideoSurfaceStreamerImpl(
      content::RenderFrame* render_frame,
      mojo::PendingAssociatedReceiver<mojom::VideoSurfaceStreamer> receiver);

  mojo::AssociatedReceiver<mojom::VideoSurfaceStreamer> receiver_;
  
  raw_ptr<blink::WebMediaPlayer> web_media_player_ = nullptr;
  std::unique_ptr<blink::WebVideoFrameSubmitter> video_frame_submitter_;
};

}  // namespace brave

#endif  // BRAVE_RENDERER_YOUTUBE_SCRIPT_INJECTOR_VIDEO_SURFACE_STREAMER_IMPL_H_