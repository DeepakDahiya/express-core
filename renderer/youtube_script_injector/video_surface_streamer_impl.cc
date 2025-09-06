/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "brave/renderer/youtube_script_injector/video_surface_streamer_impl.h"

#include "base/logging.h"
#include "base/task/single_thread_task_runner.h"
#include "cc/layers/video_layer.h"
#include "content/public/renderer/render_frame.h"
#include "gpu/command_buffer/client/gles2_interface.h"
#include "media/base/video_frame.h"
#include "third_party/blink/public/platform/web_media_player.h"
#include "third_party/blink/public/web/web_document.h"
#include "third_party/blink/public/web/web_element.h"
#include "third_party/blink/public/web/web_frame.h"
#include "third_party/blink/public/web/web_local_frame.h"
#include "third_party/blink/public/web/web_node.h"
#include "third_party/blink/renderer/core/html/media/html_video_element.h"

namespace brave {

VideoSurfaceStreamerImpl::VideoSurfaceStreamerImpl(
    content::RenderFrame* render_frame,
    mojo::PendingReceiver<mojom::VideoSurfaceStreamer> receiver)
    : render_frame_(render_frame),
      receiver_(this, std::move(receiver)),
      weak_factory_(this) {
  DCHECK(render_frame_);
}

VideoSurfaceStreamerImpl::~VideoSurfaceStreamerImpl() {
  StopStreaming();
}

void VideoSurfaceStreamerImpl::StartStreaming(
    const base::UnguessableToken& surface_token,
    StartStreamingCallback callback) {
  LOG(INFO) << "VideoSurfaceStreamerImpl::StartStreaming";
  
  if (is_streaming_) {
    LOG(WARNING) << "Already streaming, stopping existing stream";
    StopStreaming();
  }

  // Find the video element in the page
  blink::WebLocalFrame* web_frame = render_frame_->GetWebFrame();
  if (!web_frame) {
    LOG(ERROR) << "No web frame available";
    std::move(callback).Run(false);
    return;
  }

  blink::WebDocument document = web_frame->GetDocument();
  if (document.IsNull()) {
    LOG(ERROR) << "Document is null";
    std::move(callback).Run(false);
    return;
  }

  // Find the first video element
  blink::WebElement video_element = document.QuerySelector("video");
  if (video_element.IsNull()) {
    LOG(ERROR) << "No video element found";
    std::move(callback).Run(false);
    return;
  }

  // Get the media player from the video element
  auto* html_video = video_element.Unwrap<blink::HTMLVideoElement>();
  if (!html_video) {
    LOG(ERROR) << "Failed to unwrap HTMLVideoElement";
    std::move(callback).Run(false);
    return;
  }

  web_media_player_ = html_video->GetWebMediaPlayer();
  if (!web_media_player_) {
    LOG(ERROR) << "No WebMediaPlayer available";
    std::move(callback).Run(false);
    return;
  }

  surface_token_ = surface_token;
  is_streaming_ = true;

  // Set up video frame callback
  SetupVideoFrameCallback();

  // Create a video layer that will render to the surface
  CreateVideoLayer();

  LOG(INFO) << "Successfully started video streaming";
  std::move(callback).Run(true);
}

void VideoSurfaceStreamerImpl::StopStreaming() {
  LOG(INFO) << "VideoSurfaceStreamerImpl::StopStreaming";
  
  is_streaming_ = false;
  
  if (video_frame_callback_id_) {
    if (web_media_player_) {
      web_media_player_->CancelVideoFrameCallback(video_frame_callback_id_);
    }
    video_frame_callback_id_ = 0;
  }

  if (video_layer_) {
    video_layer_ = nullptr;
  }

  web_media_player_ = nullptr;
  surface_token_ = base::UnguessableToken();
}

void VideoSurfaceStreamerImpl::TogglePlayback() {
  if (!web_media_player_) {
    LOG(ERROR) << "No media player available";
    return;
  }

  if (web_media_player_->Paused()) {
    web_media_player_->Play();
    LOG(INFO) << "Resumed playback";
  } else {
    web_media_player_->Pause();
    LOG(INFO) << "Paused playback";
  }
}

void VideoSurfaceStreamerImpl::UpdateStreamingParams(
    const gfx::Size& video_size,
    float frame_rate) {
  video_size_ = video_size;
  frame_rate_ = frame_rate;
  
  if (video_layer_) {
    video_layer_->SetBounds(gfx::Size(video_size.width(), video_size.height()));
  }
}

void VideoSurfaceStreamerImpl::SetupVideoFrameCallback() {
  if (!web_media_player_) {
    return;
  }

  // Request video frame callbacks to get notified when new frames are available
  auto callback = base::BindRepeating(
      &VideoSurfaceStreamerImpl::OnVideoFrameAvailable,
      weak_factory_.GetWeakPtr());
  
  video_frame_callback_id_ = web_media_player_->RequestVideoFrameCallback(
      std::move(callback));
}

void VideoSurfaceStreamerImpl::OnVideoFrameAvailable(
    base::TimeDelta timestamp) {
  if (!is_streaming_ || !web_media_player_) {
    return;
  }

  // Get the current video frame
  scoped_refptr<media::VideoFrame> frame = web_media_player_->GetCurrentFrame();
  if (!frame) {
    return;
  }

  // Send the frame to the surface
  RenderFrameToSurface(frame);

  // Request the next frame callback
  if (is_streaming_) {
    SetupVideoFrameCallback();
  }
}

void VideoSurfaceStreamerImpl::CreateVideoLayer() {
  if (!web_media_player_) {
    return;
  }

  // Create a video layer that will be composited to the surface
  video_layer_ = cc::VideoLayer::Create(
      web_media_player_,
      media::VideoRotation::VIDEO_ROTATION_0);
  
  if (!video_layer_) {
    LOG(ERROR) << "Failed to create video layer";
    return;
  }

  // Set initial bounds
  if (!video_size_.IsEmpty()) {
    video_layer_->SetBounds(gfx::Size(video_size_.width(), video_size_.height()));
  }

  // Configure the layer to render to our surface
  video_layer_->SetSurfaceId(surface_token_);
  video_layer_->SetIsDrawable(true);
  video_layer_->SetContentsOpaque(true);
}

void VideoSurfaceStreamerImpl::RenderFrameToSurface(
    scoped_refptr<media::VideoFrame> frame) {
  if (!frame || !is_streaming_) {
    return;
  }

  // Update video size if it has changed
  gfx::Size frame_size = frame->natural_size();
  if (frame_size != video_size_) {
    UpdateStreamingParams(frame_size, frame_rate_);
  }

  // The actual rendering to the surface is handled by the compositor
  // through the video layer we created. We just need to ensure the
  // layer has the latest frame.
  if (video_layer_) {
    video_layer_->SetNeedsDisplay();
  }
}

// Static factory method
void VideoSurfaceStreamerImpl::Create(
    content::RenderFrame* render_frame,
    mojo::PendingReceiver<mojom::VideoSurfaceStreamer> receiver) {
  // The object will be owned by the mojo binding
  new VideoSurfaceStreamerImpl(render_frame, std::move(receiver));
}

}  // namespace brave