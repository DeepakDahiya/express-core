// Copyright (c) 2023 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import * as React from 'react'
import ButtonMenu from '@brave/leo/react/buttonMenu'
import Icon from '@brave/leo/react/icon'
import Toggle from '@brave/leo/react/toggle'
import { getLocale } from '$web-common/locale'
import getPageHandlerInstance, * as mojom from '../../api/page_handler'
import DataContext from '../../state/context'
import styles from './style.module.scss'

export default function FeatureMenu() {
  const context = React.useContext(DataContext)

  const handleSettingsClick = () => {
    getPageHandlerInstance().pageHandler.openBraveLeoSettings()
  }

  // TODO(petemill): Whilst this may be accurate, if the default (i.e. Unset)
  // value changes to True, this will be wrong.
  const isAutoGeneratedQuestionsEnabled =
  (context.userAutoGeneratePref === mojom.AutoGenerateQuestionsPref.Enabled)

  const handleAutoGenerateQuestionsClick = () => {
    context.setUserAllowsAutoGenerating(!isAutoGeneratedQuestionsEnabled)
  }

  const handleNewConversationClick = () => {
    getPageHandlerInstance().pageHandler.clearConversationHistory()
  }

  return (
    <ButtonMenu>
      <div slot='anchor-content'>
        <Icon name='more-horizontal' />
      </div>
      <div className={styles.menuSectionTitle}>
        {getLocale('menuTitleModels')}
      </div>
      <div className={styles.menuSubtitle}>
        {getLocale('modelCategory-chat')}
      </div>

      {context.allModels.map((model) => (
        <leo-menu-item
          key={model.key}
          aria-selected={(model.key === context.currentModel?.key) || undefined}
          onClick={() => context.setCurrentModel(model)}
        >
          <div className={styles.menuItemWithIcon}>
            {model.name}
            {model.isPremium && <Icon name='lock-plain' />}
          </div>
        </leo-menu-item>
      ))}

      <div className={styles.menuSeparator} />

      <leo-menu-item onClick={handleNewConversationClick}>
        <div className={styles.menuItemWithIcon}>
          <span>{getLocale('menuNewChat')}</span>
          <Icon name='erase' />
        </div>
      </leo-menu-item>


      <leo-menu-item
        onClick={handleAutoGenerateQuestionsClick}
        data-is-interactive={true}
      >
        <div className={styles.menuItemWithIcon}>
          <span>{getLocale('menuSuggestedQuestions')}</span>
          <Toggle
            size='small'
            checked={isAutoGeneratedQuestionsEnabled}
            onChange={handleAutoGenerateQuestionsClick}
          />
        </div>
      </leo-menu-item>


      <leo-menu-item onClick={handleSettingsClick}>
        <div className={styles.menuItemWithIcon}>
          <span>{getLocale('menuSettings')}</span>
          <Icon name='settings' />
        </div>
      </leo-menu-item>
    </ButtonMenu>
  )
}
