/* Copyright (c) 2019 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

#include "brave/components/brave_component_updater/browser/dat_file_util.h"

#include <memory>
#include <string>

#include "base/files/file_path.h"
#include "base/files/file_util.h"
#include "base/logging.h"
#include "base/trace_event/trace_event.h"

namespace {

void GetDATFileData(const base::FilePath& file_path,
                    brave_component_updater::DATFileDataBuffer* buffer) {
  if (!base::PathExists(file_path)) {
    LOG(ERROR) << "GetDATFileData: the dat file is not found. " << file_path;
    return;
  }

  if (auto bytes = base::ReadFileToBytes(file_path)) {
    *buffer = std::move(*bytes);
  } else {
    LOG(ERROR) << "GetDATFileData: cannot "
               << "read dat file " << file_path;
  }
}

}  // namespace

namespace brave_component_updater {

DATFileDataBuffer ReadDATFileData(const base::FilePath& dat_file_path) {
  TRACE_EVENT_BEGIN("brave.adblock", "ReadDATFileData", "path",
                    dat_file_path.MaybeAsASCII());
  DATFileDataBuffer buffer;
  GetDATFileData(dat_file_path, &buffer);
  TRACE_EVENT_END("brave.adblock", "size", buffer.size());
  return buffer;
}

std::string GetDATFileAsString(const base::FilePath& file_path) {
  TRACE_EVENT_BEGIN("brave.adblock", "GetDATFileAsString", "path",
                    file_path.MaybeAsASCII());
  LOG(ERROR) << "Brave AdBlock: GetDATFileAsString called for: " << file_path.value();

  if (!base::PathExists(file_path)) {
    LOG(ERROR) << "Brave AdBlock: GetDATFileAsString - file does NOT exist: " << file_path.value();
    // Also check if the parent directory exists
    base::FilePath parent_dir = file_path.DirName();
    if (!base::PathExists(parent_dir)) {
      LOG(ERROR) << "Brave AdBlock: GetDATFileAsString - parent directory does NOT exist: " << parent_dir.value();
    } else {
      LOG(ERROR) << "Brave AdBlock: GetDATFileAsString - parent directory exists: " << parent_dir.value();
    }
    TRACE_EVENT_END("brave.adblock", "size", 0);
    return std::string();
  }

  LOG(ERROR) << "Brave AdBlock: GetDATFileAsString - file EXISTS: " << file_path.value();
  std::string contents;
  bool success = base::ReadFileToString(file_path, &contents);
  if (!success || contents.empty()) {
    LOG(ERROR) << "Brave AdBlock: GetDATFileAsString - FAILED to read file or file is empty: " << file_path.value();
  } else {
    LOG(ERROR) << "Brave AdBlock: GetDATFileAsString - successfully read " << contents.size() << " bytes from: " << file_path.value();
  }
  TRACE_EVENT_END("brave.adblock", "size", contents.size());
  return contents;
}

}  // namespace brave_component_updater
