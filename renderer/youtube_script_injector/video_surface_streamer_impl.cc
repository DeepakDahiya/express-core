/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "brave/renderer/youtube_script_injector/video_surface_streamer_impl.h"

#include "content/public/renderer/render_frame.h"
#include "third_party/blink/public/platform/web_media_player.h"
#include "third_party/blink/public/platform/web_video_frame_submitter.h"
#include "third_party/blink/public/web/web_document.h"
#include "third_party/blink/public/web/web_element_collection.h"
#include "third_party/blink/public/web/web_local_frame.h"
#include "third_party/blink/public/web/html/html_video_element.h"

namespace brave {

void VideoSurfaceStreamerImpl::Create(
    content::RenderFrame* render_frame,
    mojo::PendingAssociatedReceiver<mojom::VideoSurfaceStreamer> receiver) {
  new VideoSurfaceStreamerImpl(render_frame, std::move(receiver));
}

VideoSurfaceStreamerImpl::VideoSurfaceStreamerImpl(
    content::RenderFrame* render_frame,
    mojo::PendingAssociatedReceiver<mojom::VideoSurfaceStreamer> receiver)
    : content::RenderFrameObserver(render_frame),
      receiver_(this, std::move(receiver)) {}

VideoSurfaceStreamerImpl::~VideoSurfaceStreamerImpl() = default;

void VideoSurfaceStreamerImpl::OnDestruct() {
  delete this;
}

void VideoSurfaceStreamerImpl::StartStreaming(
    gpu::SurfaceHandle surface_handle,
    StartStreamingCallback callback) {
  StopStreaming();

  blink::WebLocalFrame* frame = render_frame()->GetWebFrame();
  if (!frame) {
    std::move(callback).Run(false);
    return;
  }

  blink::HTMLVideoElement* video_element = nullptr;
  int max_area = 0;
  blink::WebElementCollection videos = frame->GetDocument().GetElementsByTagName("video");
  for (blink::WebElement element = videos.FirstItem(); !element.IsNull(); element = videos.NextItem()) {
    auto* current_video = element.To<blink::HTMLVideoElement>();
    if (current_video && current_video->HasVideo() && !current_video->paused()) {
        gfx::Rect bounds = current_video->BoundsInWidget();
        int area = bounds.width() * bounds.height();
        if (area > max_area) {
            max_area = area;
            video_element = current_video;
        }
    }
  }

  if (!video_element) {
    LOG(WARNING) << "BraveVideoStreamer: No suitable <video> element found.";
    std::move(callback).Run(false);
    return;
  }

  web_media_player_ = video_element->GetWebMediaPlayer();
  if (!web_media_player_) {
    LOG(WARNING) << "BraveVideoStreamer: No WebMediaPlayer available.";
    std::move(callback).Run(false);
    return;
  }

  video_frame_submitter_ = blink::WebVideoFrameSubmitter::Create(
      frame->GetTaskRunner(blink::TaskType::kMediaElementEvent),
      base::BindRepeating([](media::VideoFrame::StorageType) { return true; }));

  video_frame_submitter_->Start(web_media_player_, surface_handle);

  LOG(INFO) << "BraveVideoStreamer: Started streaming video frames.";
  std::move(callback).Run(true);
}

void VideoSurfaceStreamerImpl::StopStreaming() {
  if (video_frame_submitter_) {
    video_frame_submitter_->Stop();
    video_frame_submitter_.reset();
  }
  web_media_player_ = nullptr;
}

void VideoSurfaceStreamerImpl::TogglePlayback() {
  if (!web_media_player_) {
    LOG(WARNING) << "BraveVideoStreamer: TogglePlayback called but no media player is active.";
    return;
  }

  if (web_media_player_->Paused()) {
    web_media_player_->Play();
  } else {
    web_media_player_->Pause();
  }
}

}  // namespace brave