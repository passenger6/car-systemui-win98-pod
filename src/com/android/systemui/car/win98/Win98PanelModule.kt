/*
 * Copyright (C) 2026 Daniel Georg
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.car.win98

import android.content.Context
import android.view.View
import com.android.car.scalableui.panel.DecorPanelController
import com.android.systemui.car.wm.scalableui.panel.controller.DecorPanelViewMap
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

/** Dagger module for the Windows 98 title-bar decor: controller factory + view. */
@Module
abstract class Win98PanelModule {
    @Binds
    @IntoMap
    @ClassKey(Win98TitleBarController::class)
    abstract fun bindWin98TitleBarControllerFactory(
        factory: Win98TitleBarController.Factory
    ): DecorPanelController.Factory<*>

    companion object {
        @Provides
        @IntoMap
        @ClassKey(Win98TitleBarView::class)
        @DecorPanelViewMap
        fun provideWin98TitleBarView(context: Context): View {
            return Win98TitleBarView(context)
        }
    }
}
