/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#ifndef BRAVE_RENDERER_YOUTUBE_SCRIPT_INJECTOR_VIDEO_SURFACE_STREAMER_IMPL_H_
#define BRAVE_RENDERER_YOUTUBE_SCRIPT_INJECTOR_VIDEO_SURFACE_STREAMER_IMPL_H_

#include "base/memory/raw_ptr.h"
#include "base/memory/weak_ptr.h"
#include "base/time/time.h"
#include "brave/browser/android/youtube_script_injector/mojom/video_surface_streamer.mojom.h"
#include "gpu/ipc/common/surface_handle.h"
#include "mojo/public/cpp/bindings/pending_receiver.h"
#include "mojo/public/cpp/bindings/receiver.h"
#include "ui/gfx/geometry/size.h"
#include "mojo/public/cpp/bindings/associated_receiver.h"
#include "content/public/renderer/render_frame_observer.h"

namespace blink {
class WebMediaPlayer;
}

namespace cc {
class VideoLayer;
}

namespace content {
class RenderFrame;
}

namespace media {
class VideoFrame;
}

namespace brave {

// Implementation of the VideoSurfaceStreamer interface in the renderer process
class VideoSurfaceStreamerImpl : public mojom::VideoSurfaceStreamer {
 public:
  VideoSurfaceStreamerImpl(
      content::RenderFrame* render_frame,
      mojo::PendingReceiver<mojom::VideoSurfaceStreamer> receiver);
  ~VideoSurfaceStreamerImpl() override;

  // mojom::VideoSurfaceStreamer implementation
  void StartStreaming(gpu::SurfaceHandle surface_handle,
                     StartStreamingCallback callback) override;
  void StopStreaming() override;
  void TogglePlayback() override;
  void UpdateStreamingParams(const gfx::Size& video_size,
                            float frame_rate) override;

  // Static factory method
  static void Create(content::RenderFrame* render_frame,
                    mojo::PendingAssociatedReceiver<mojom::VideoSurfaceStreamer> receiver);

 private:
  void SetupVideoFrameCallback();
  void OnVideoFrameAvailable(base::TimeDelta timestamp);
  void CreateVideoLayer();
  void RenderFrameToSurface(scoped_refptr<media::VideoFrame> frame);

  raw_ptr<content::RenderFrame> render_frame_;
  mojo::AssociatedReceiver<mojom::VideoSurfaceStreamer> receiver_;
  
  // Video streaming state
  bool is_streaming_ = false;
  gpu::SurfaceHandle surface_handle_ = gpu::kNullSurfaceHandle;
  gfx::Size video_size_;
  float frame_rate_ = 30.0f;
  
  // Media player references
  raw_ptr<blink::WebMediaPlayer> web_media_player_ = nullptr;
  scoped_refptr<cc::VideoLayer> video_layer_;
  uint32_t video_frame_callback_id_ = 0;

  base::WeakPtrFactory<VideoSurfaceStreamerImpl> weak_factory_{this};
};

}  // namespace brave

#endif  // BRAVE_RENDERER_YOUTUBE_SCRIPT_INJECTOR_VIDEO_SURFACE_STREAMER_IMPL_H_