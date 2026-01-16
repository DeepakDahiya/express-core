// Copyright (c) 2023 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#include "brave/components/brave_shields/core/browser/ad_block_component_installer.h"

#include <memory>
#include <string>
#include <vector>

#include "base/base64.h"
#include "base/containers/to_vector.h"
#include "base/functional/bind.h"
#include "base/functional/callback.h"
#include "brave/components/brave_component_updater/browser/brave_on_demand_updater.h"
#include "brave/components/brave_shields/core/common/brave_shield_constants.h"
#include "components/component_updater/component_installer.h"
#include "components/component_updater/component_updater_service.h"
#include "components/update_client/update_client_errors.h"
#include "crypto/sha2.h"
#include "base/logging.h"

using brave_component_updater::BraveOnDemandUpdater;

namespace brave_shields {

namespace {

class AdBlockComponentInstallerPolicy
    : public component_updater::ComponentInstallerPolicy {
 public:
  explicit AdBlockComponentInstallerPolicy(
      const std::string& component_public_key,
      const std::string& component_id,
      const std::string& component_name,
      OnComponentReadyCallback callback);
  ~AdBlockComponentInstallerPolicy() override;

  AdBlockComponentInstallerPolicy(const AdBlockComponentInstallerPolicy&) =
      delete;
  AdBlockComponentInstallerPolicy& operator=(
      const AdBlockComponentInstallerPolicy&) = delete;

  // component_updater::ComponentInstallerPolicy
  bool SupportsGroupPolicyEnabledComponentUpdates() const override;
  bool RequiresNetworkEncryption() const override;
  update_client::CrxInstaller::Result OnCustomInstall(
      const base::Value::Dict& manifest,
      const base::FilePath& install_dir) override;
  void OnCustomUninstall() override;
  bool VerifyInstallation(const base::Value::Dict& manifest,
                          const base::FilePath& install_dir) const override;
  void ComponentReady(const base::Version& version,
                      const base::FilePath& path,
                      base::Value::Dict manifest) override;
  base::FilePath GetRelativeInstallDir() const override;
  void GetHash(std::vector<uint8_t>* hash) const override;
  std::string GetName() const override;
  update_client::InstallerAttributes GetInstallerAttributes() const override;
  bool IsBraveComponent() const override;

 private:
  const std::string component_id_;
  const std::string component_name_;
  OnComponentReadyCallback ready_callback_;
  std::array<uint8_t, crypto::kSHA256Length> component_hash_;
};

AdBlockComponentInstallerPolicy::AdBlockComponentInstallerPolicy(
    const std::string& component_public_key,
    const std::string& component_id,
    const std::string& component_name,
    OnComponentReadyCallback callback)
    : component_id_(component_id),
      component_name_(component_name),
      ready_callback_(callback) {
  LOG(ERROR) << "Brave AdBlock: AdBlockComponentInstallerPolicy constructor for "
             << component_name_ << " (id: " << component_id_ << ")";
  // Generate hash from public key.
  auto decoded_public_key = base::Base64Decode(component_public_key);
  if (!decoded_public_key) {
    LOG(ERROR) << "Brave AdBlock: FAILED to decode base64 public key for " << component_name_;
  } else {
    LOG(ERROR) << "Brave AdBlock: Successfully decoded public key for " << component_name_
               << ", decoded key size: " << decoded_public_key->size();
  }
  CHECK(decoded_public_key);
  component_hash_ = crypto::SHA256Hash(*decoded_public_key);
  LOG(ERROR) << "Brave AdBlock: Generated component hash for " << component_name_;
}

AdBlockComponentInstallerPolicy::~AdBlockComponentInstallerPolicy() = default;

bool AdBlockComponentInstallerPolicy::
    SupportsGroupPolicyEnabledComponentUpdates() const {
  return true;
}

bool AdBlockComponentInstallerPolicy::RequiresNetworkEncryption() const {
  return false;
}

update_client::CrxInstaller::Result
AdBlockComponentInstallerPolicy::OnCustomInstall(
    const base::Value::Dict& manifest,
    const base::FilePath& install_dir) {
  LOG(ERROR) << "Brave AdBlock: OnCustomInstall for " << component_name_;
  return update_client::CrxInstaller::Result(0);
}

void AdBlockComponentInstallerPolicy::OnCustomUninstall() {}

void AdBlockComponentInstallerPolicy::ComponentReady(
    const base::Version& version,
    const base::FilePath& path,
    base::Value::Dict manifest) {
  LOG(ERROR) << "Brave AdBlock: ComponentReady called for " << component_name_
            << ". Version: " << version.GetString() << ", Path: " << path.value();
  ready_callback_.Run(path);
}

bool AdBlockComponentInstallerPolicy::VerifyInstallation(
    const base::Value::Dict& manifest,
    const base::FilePath& install_dir) const {
  LOG(ERROR) << "Brave AdBlock: Verifying installation for " << component_name_ << " at " << install_dir.value();
  return true;
}

base::FilePath AdBlockComponentInstallerPolicy::GetRelativeInstallDir() const {
  LOG(ERROR) << "Brave AdBlock: GetRelativeInstallDir called for " << component_name_
             << ", returning: " << component_id_;
  return base::FilePath::FromUTF8Unsafe(component_id_);
}

void AdBlockComponentInstallerPolicy::GetHash(
    std::vector<uint8_t>* hash) const {
  LOG(ERROR) << "Brave AdBlock: GetHash called for " << component_name_;
  *hash = base::ToVector(component_hash_);
}

std::string AdBlockComponentInstallerPolicy::GetName() const {
  return component_name_;
}

update_client::InstallerAttributes
AdBlockComponentInstallerPolicy::GetInstallerAttributes() const {
  return update_client::InstallerAttributes();
}

bool AdBlockComponentInstallerPolicy::IsBraveComponent() const {
  return true;
}

void OnRegistered(const std::string& component_id) {
  // Unlike other components, which are only installed but not updated in
  // `OnRegistered`, we do always want to update the ad block component upon
  // registration.
  LOG(ERROR) << "Brave AdBlock: Component registered, triggering on-demand update for " << component_id;
  BraveOnDemandUpdater::GetInstance()->OnDemandUpdate(
      component_id, component_updater::OnDemandUpdater::Priority::FOREGROUND,
      base::BindOnce([](const std::string& cid, update_client::Error error) {
        std::string error_desc;
        switch (error) {
          case update_client::Error::NONE:
            error_desc = "NONE (success or no update available)";
            break;
          case update_client::Error::UPDATE_IN_PROGRESS:
            error_desc = "UPDATE_IN_PROGRESS";
            break;
          case update_client::Error::UPDATE_NOT_FOUND:
            error_desc = "UPDATE_NOT_FOUND (component not on server!)";
            break;
          case update_client::Error::UPDATE_CHECK_ERROR:
            error_desc = "UPDATE_CHECK_ERROR";
            break;
          case update_client::Error::CRX_NOT_FOUND:
            error_desc = "CRX_NOT_FOUND";
            break;
          case update_client::Error::INVALID_ARGUMENT:
            error_desc = "INVALID_ARGUMENT";
            break;
          case update_client::Error::MAX_VALUE:
            error_desc = "MAX_VALUE";
            break;
          default:
            error_desc = "UNKNOWN";
        }
        LOG(ERROR) << "Brave AdBlock: On-demand update result for " << cid
                   << ": " << static_cast<int>(error) << " (" << error_desc << ")";
      }, component_id));
}

}  // namespace

void RegisterAdBlockDefaultResourceComponent(
    component_updater::ComponentUpdateService* cus,
    OnComponentReadyCallback callback) {
  // In test, |cus| could be nullptr.
  LOG(INFO) << "Brave AdBlock: Registering default resource component.";
  if (!cus ||
      BraveOnDemandUpdater::GetInstance()->is_component_update_disabled()) {
    return;
  }

  auto installer = base::MakeRefCounted<component_updater::ComponentInstaller>(
      std::make_unique<AdBlockComponentInstallerPolicy>(
          kAdBlockResourceComponentBase64PublicKey, kAdBlockResourceComponentId,
          kAdBlockResourceComponentName, callback));
  installer->Register(
      cus, base::BindOnce(&OnRegistered, kAdBlockResourceComponentId));
}

void RegisterAdBlockFilterListCatalogComponent(
    component_updater::ComponentUpdateService* cus,
    OnComponentReadyCallback callback) {
  // In test, |cus| could be nullptr.
  LOG(ERROR) << "Brave AdBlock: RegisterAdBlockFilterListCatalogComponent called";
  if (!cus) {
    LOG(ERROR) << "Brave AdBlock: CUS is null, cannot register catalog component";
    return;
  }
  if (BraveOnDemandUpdater::GetInstance()->is_component_update_disabled()) {
    LOG(ERROR) << "Brave AdBlock: Component updates disabled, cannot register catalog component";
    return;
  }

  // Verify component ID format (should be 32 lowercase chars a-p)
  std::string comp_id = kAdBlockFilterListCatalogComponentId;
  LOG(ERROR) << "Brave AdBlock: Catalog component ID: '" << comp_id << "'";
  LOG(ERROR) << "Brave AdBlock: Catalog component ID length: " << comp_id.length()
             << " (should be 32)";

  // Log the public key for verification
  LOG(ERROR) << "Brave AdBlock: Catalog public key (first 50 chars): "
             << std::string(kAdBlockFilterListCatalogComponentBase64PublicKey).substr(0, 50) << "...";

  LOG(ERROR) << "Brave AdBlock: Creating installer for catalog component with ID: "
             << kAdBlockFilterListCatalogComponentId;

  auto installer = base::MakeRefCounted<component_updater::ComponentInstaller>(
      std::make_unique<AdBlockComponentInstallerPolicy>(
          kAdBlockFilterListCatalogComponentBase64PublicKey,
          kAdBlockFilterListCatalogComponentId,
          kAdBlockFilterListCatalogComponentName, callback));

  LOG(ERROR) << "Brave AdBlock: Calling Register() for catalog component";
  installer->Register(
      cus, base::BindOnce(&OnRegistered, kAdBlockFilterListCatalogComponentId));
}

void RegisterAdBlockFiltersComponent(
    component_updater::ComponentUpdateService* cus,
    const std::string& component_public_key,
    const std::string& component_id,
    const std::string& component_name,
    OnComponentReadyCallback callback) {
  // In test, |cus| could be nullptr.
  if (!cus ||
      BraveOnDemandUpdater::GetInstance()->is_component_update_disabled()) {
    LOG(ERROR) << "Brave AdBlock: Not registering component " << component_name << " (id: " << component_id << ") because CUS is null (test?) or updates disabled.";
    return;
  }

  LOG(ERROR) << "Brave AdBlock: Registering component " << component_name << " (id: " << component_id << ")";
  auto installer = base::MakeRefCounted<component_updater::ComponentInstaller>(
      std::make_unique<AdBlockComponentInstallerPolicy>(
          component_public_key, component_id, component_name, callback));
  installer->Register(cus, base::BindOnce(&OnRegistered, component_id));
}

}  // namespace brave_shields
