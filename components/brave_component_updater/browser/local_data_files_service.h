/* Copyright (c) 2019 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

#ifndef BRAVE_COMPONENTS_BRAVE_COMPONENT_UPDATER_BROWSER_LOCAL_DATA_FILES_SERVICE_H_
#define BRAVE_COMPONENTS_BRAVE_COMPONENT_UPDATER_BROWSER_LOCAL_DATA_FILES_SERVICE_H_

#include <memory>
#include <string>

#include "base/files/file_path.h"
#include "base/observer_list.h"
#include "brave/components/brave_component_updater/browser/brave_component.h"

namespace brave_component_updater {

class LocalDataFilesObserver;

const char kLocalDataFilesComponentName[] = "Brave Local Data Updater";
const char kLocalDataFilesComponentId[] = "apclhplomajomnpciajgmpmnjondfhek";
const char kLocalDataFilesComponentBase64PublicKey[] =
    "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwAFiNKuDHk5VRj/HQ4XT"
    "f3NuqwgNuN+CgbmXA033D8EKqp1S0gIR0/zajFujZOGqON/EvOLwZoAyRCa1VLu8"
    "0GBw3UdvaE3vsz36u9SiXQKdEuqo7GppEr/YXm/zx2Y6sAywcFj/gUW2ddW+liZb"
    "VI6PFQ8kN2yAkO0MQVcGfKL5ADwg91UP0qE0Vpf0kjJpDSyy+kD+sPPJrCRC7JIZ"
    "AUa5GJSRylMUcv8LxP2sQqwQccfwzcpUhxDdyF55EAcJXT2yJNtDrZwymEh/SgLO"
    "5+P35lKG0Gx6BZIvFkeobBYpA2DwS2w0Cjev4WLktfnm/BWPjJK84t44BG521053"
    "fQIDAQAB";

// The component in charge of delegating access to different DAT files
// such as tracking protection.
class LocalDataFilesService : public BraveComponent {
 public:
  explicit LocalDataFilesService(BraveComponent::Delegate* delegate);
  LocalDataFilesService(const LocalDataFilesService&) = delete;
  LocalDataFilesService& operator=(const LocalDataFilesService&) = delete;
  ~LocalDataFilesService() override;
  bool Start();
  bool IsInitialized() const { return initialized_; }
  void AddObserver(LocalDataFilesObserver* observer);
  void RemoveObserver(LocalDataFilesObserver* observer);

  static void SetComponentIdAndBase64PublicKeyForTest(
      const std::string& component_id,
      const std::string& component_base64_public_key);

 protected:
  void OnComponentReady(const std::string& component_id,
      const base::FilePath& install_dir,
      const std::string& manifest) override;

 private:
  static std::string g_local_data_files_component_id_;
  static std::string g_local_data_files_component_base64_public_key_;

  bool initialized_;
  base::ObserverList<LocalDataFilesObserver>::Unchecked observers_;
};

// Creates the LocalDataFilesService
std::unique_ptr<LocalDataFilesService>
LocalDataFilesServiceFactory(BraveComponent::Delegate* delegate);

}  // namespace brave_component_updater

#endif  // BRAVE_COMPONENTS_BRAVE_COMPONENT_UPDATER_BROWSER_LOCAL_DATA_FILES_SERVICE_H_
