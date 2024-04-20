// Copyright (c) 2023 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#include "brave/components/brave_shields/browser/ad_block_component_installer.h"

#include <memory>
#include <string>
#include <vector>

#include "base/base64.h"
#include "base/functional/bind.h"
#include "base/functional/callback.h"
#include "base/rand_util.h"
#include "base/task/sequenced_task_runner.h"
#include "base/time/time.h"
#include "brave/components/brave_component_updater/browser/brave_on_demand_updater.h"
#include "components/component_updater/component_installer.h"
#include "components/component_updater/component_updater_service.h"
#include "crypto/sha2.h"

using brave_component_updater::BraveOnDemandUpdater;

namespace brave_shields {

namespace {

constexpr size_t kHashSize = 32;
const char kAdBlockResourceComponentName[] = "Brave Ad Block Resources Library";
const char kAdBlockResourceComponentId[] = "dlibbfbdhhamaleoofdjnejelcgldodb";
const char kAdBlockResourceComponentBase64PublicKey[] =
    "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAlYa/Beo8ErS7GB31gsjH"
    "QOerBEGD58eXXf6GLPxp8cyJ/DFF06Svo8uXEZMuFK6pT6Qi1byX5UVjNmeB5Xwb"
    "6TndxJGQAYPL2YA8R60OpKDL8fKRmikI6vBleV1Fw56qYi/SoT47xqxf/F7uOFms"
    "W6768ImB9lyF6YWW5ZpUDaHj1H5XemUWSF3JzY6uBYEkjdn1KmbE+zz+tNh1UXrd"
    "AkWSXr3opGDFNWKEg9kOBa2gEZh42mwNJDS8Olo8RgrKQbSHr5zxMCuxsntdua22"
    "mH4vBzHoauLRwQqYpLW64Kkl5xx0cDizUF7EGbqhB+Xcm4yD5kvNzmDzQ6WdNMRL"
    "kwIDAQAB";

const char kAdBlockFilterListCatalogComponentName[] =
    "Brave Ad Block List Catalog";
const char kAdBlockFilterListCatalogComponentId[] =
    "bbhodghhfoljambigkiibfnmgkobming";
const char kAdBlockFilterListCatalogComponentBase64PublicKey[] =
    "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAteLpEXUrGmrqoP0zVdrr"
    "G6ryZX4zuM6uKMSFjTFwGYD3BmqKPZis65HABWe1dSVE96YojLBG+uGK9McNwHb7"
    "pC+Ht3L9TRF4qY/vLLHFSb1lBzgNo+IVOC+s7cj0tQ5CquKd4I14xJTAlHovsI6y"
    "tki4N89is2wFGuJCv+zY6Y8fBmseykhlcp3EHHNX3iKaQMX5B66omEw57iAHMXcB"
    "xNzQEcssBcPnej3FlaPojgdtg+Fzb55OpRgYCq/N5VP8oKGG2LZAxW8rjyab4wVp"
    "I5cNRpVJZzdDhRdkIU05TpIiC0drDprKvNdUpumTVxTtzeOfWjXQPqW7iTSX6HJP"
    "UwIDAQAB";

const char kAdBlockDefaultComponentName[] = "Brave Ad Block Updater";
const char kAdBlockDefaultComponentId[] = "pdekichlmeollipkjkbdgkijnlcofoed";
const char kAdBlockDefaultComponentBase64PublicKey[] =
    "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAtGFxHUZ9oZ8VJLlmG49d"
    "tcGusEyrA8F7GBiiRtis3iBZYOeGiu10lCnmdKuSzmRWmwPNd5Vq7PHAYHRqZCI/"
    "wKyXZdvw0ZPPNg57v+hqVU3DX0UBQE99/H7bZfADLiuwGrAfNPfqGdb3rcbbres1"
    "WH+Zc9pv4Isoh6jc1AX7mDO3SJauKj/qrU7OWa0pDuZH5JOwKjY8JBHfJIuMc+SY"
    "rpM8tsL6jsUW4Evdu+QeWMbL+kq5cXpPOW/72Jy2DoaK4S9JOsQhAdX1Zx6ujku7"
    "vWu0uK/yr5Wv66naEVB2vYS0A1mDntLSx07sRHNDSg8JLpIweexrNbq1040sc99k"
    "aQIDAQAB";

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

 private:
  const std::string component_id_;
  const std::string component_name_;
  OnComponentReadyCallback ready_callback_;
  uint8_t component_hash_[kHashSize];
};

AdBlockComponentInstallerPolicy::AdBlockComponentInstallerPolicy(
    const std::string& component_public_key,
    const std::string& component_id,
    const std::string& component_name,
    OnComponentReadyCallback callback)
    : component_id_(component_id),
      component_name_(component_name),
      ready_callback_(callback) {
  // Generate hash from public key.
  std::string decoded_public_key;
  base::Base64Decode(component_public_key, &decoded_public_key);
  crypto::SHA256HashString(decoded_public_key, component_hash_, kHashSize);
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
  return update_client::CrxInstaller::Result(0);
}

void AdBlockComponentInstallerPolicy::OnCustomUninstall() {}

void AdBlockComponentInstallerPolicy::ComponentReady(
    const base::Version& version,
    const base::FilePath& path,
    base::Value::Dict manifest) {
  ready_callback_.Run(path);
}

bool AdBlockComponentInstallerPolicy::VerifyInstallation(
    const base::Value::Dict& manifest,
    const base::FilePath& install_dir) const {
  return true;
}

base::FilePath AdBlockComponentInstallerPolicy::GetRelativeInstallDir() const {
  return base::FilePath::FromUTF8Unsafe(component_id_);
}

void AdBlockComponentInstallerPolicy::GetHash(
    std::vector<uint8_t>* hash) const {
  hash->assign(component_hash_, component_hash_ + kHashSize);
}

std::string AdBlockComponentInstallerPolicy::GetName() const {
  return component_name_;
}

update_client::InstallerAttributes
AdBlockComponentInstallerPolicy::GetInstallerAttributes() const {
  return update_client::InstallerAttributes();
}

void OnRegistered(const std::string& component_id) {
  BraveOnDemandUpdater::GetInstance()->OnDemandUpdate(component_id);
}

}  // namespace

void RegisterAdBlockDefaultComponent(
    component_updater::ComponentUpdateService* cus,
    OnComponentReadyCallback callback) {
  // In test, |cus| could be nullptr.
  if (!cus)
    return;

  auto installer = base::MakeRefCounted<component_updater::ComponentInstaller>(
      std::make_unique<AdBlockComponentInstallerPolicy>(
          kAdBlockDefaultComponentBase64PublicKey, kAdBlockDefaultComponentId,
          kAdBlockDefaultComponentName, callback));
  installer->Register(
      cus, base::BindOnce(&OnRegistered, kAdBlockDefaultComponentId));
}

void RegisterAdBlockDefaultResourceComponent(
    component_updater::ComponentUpdateService* cus,
    OnComponentReadyCallback callback) {
  // In test, |cus| could be nullptr.
  if (!cus)
    return;

  auto installer = base::MakeRefCounted<component_updater::ComponentInstaller>(
      std::make_unique<AdBlockComponentInstallerPolicy>(
          kAdBlockResourceComponentBase64PublicKey, kAdBlockResourceComponentId,
          kAdBlockResourceComponentName, callback));
  installer->Register(
      cus, base::BindOnce(&OnRegistered, kAdBlockResourceComponentId));
}

void CheckAdBlockComponentsUpdate() {
  auto runner = base::SequencedTaskRunner::GetCurrentDefault();

  runner->PostDelayedTask(FROM_HERE, base::BindOnce([]() {
                            BraveOnDemandUpdater::GetInstance()->OnDemandUpdate(
                                kAdBlockResourceComponentId);
                          }),
                          base::Seconds(base::RandInt(0, 10)));

  runner->PostDelayedTask(FROM_HERE, base::BindOnce([]() {
                            BraveOnDemandUpdater::GetInstance()->OnDemandUpdate(
                                kAdBlockDefaultComponentId);
                          }),
                          base::Seconds(base::RandInt(0, 10)));
}

void RegisterAdBlockFilterListCatalogComponent(
    component_updater::ComponentUpdateService* cus,
    OnComponentReadyCallback callback) {
  // In test, |cus| could be nullptr.
  if (!cus)
    return;

  auto installer = base::MakeRefCounted<component_updater::ComponentInstaller>(
      std::make_unique<AdBlockComponentInstallerPolicy>(
          kAdBlockFilterListCatalogComponentBase64PublicKey,
          kAdBlockFilterListCatalogComponentId,
          kAdBlockFilterListCatalogComponentName, callback));
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
  if (!cus)
    return;

  auto installer = base::MakeRefCounted<component_updater::ComponentInstaller>(
      std::make_unique<AdBlockComponentInstallerPolicy>(
          component_public_key, component_id, component_name, callback));
  installer->Register(cus, base::BindOnce(&OnRegistered, component_id));
}

}  // namespace brave_shields
