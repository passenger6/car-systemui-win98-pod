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
package com.android.systemui.car.win98;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;

import androidx.annotation.NonNull;

/**
 * Where the chrome sits around a task, and how a window clamps and resizes. Pure arithmetic on
 * rects, so it can be tested without the framework.
 */
final class Win98ChromeGeometry {

    /** How much of a dragged window has to stay on screen on every side. */
    static final int MIN_VISIBLE_PX = 120;

    private final int mBarHeight;
    private final int mFrameWidth;
    private final int mResizeBarHeight;
    /** The smallest width and height a resize leaves the task with. */
    private final int mMinimumClientSize;

    Win98ChromeGeometry(int barHeight, int frameWidth, int resizeBarHeight,
            int minimumClientSize) {
        mBarHeight = barHeight;
        mFrameWidth = frameWidth;
        mResizeBarHeight = resizeBarHeight;
        mMinimumClientSize = minimumClientSize;
    }

    @NonNull
    static Win98ChromeGeometry fromResources(@NonNull Context context) {
        return new Win98ChromeGeometry(
                context.getResources().getDimensionPixelSize(R.dimen.win98_title_bar_height),
                context.getResources().getDimensionPixelSize(R.dimen.win98_frame_width),
                context.getResources().getDimensionPixelSize(R.dimen.win98_resize_bar_height),
                context.getResources().getDimensionPixelSize(R.dimen.win98_min_client));
    }

    int barHeight() {
        return mBarHeight;
    }

    /** True when wrapping the caption above the task would put it off the display. */
    boolean captionOverlapsTask(@NonNull Rect task) {
        return task.top < mBarHeight;
    }

    /**
     * The chrome for {@code task}. Normally it wraps the task: title above, frame on the sides,
     * resize bar below. When only a strip is wanted (chip, fullscreen, no room above the task)
     * the chrome is the caption alone, over the task's top edge when {@code captionOverTask} and
     * above it otherwise.
     */
    @NonNull
    Rect chromeRect(@NonNull Rect task, boolean stripOnly, boolean captionOverTask) {
        if (!stripOnly) {
            return new Rect(
                    task.left - mFrameWidth,
                    task.top - mBarHeight,
                    task.right + mFrameWidth,
                    task.bottom + mResizeBarHeight);
        }
        int stripTop = captionOverTask ? Math.max(0, task.top) : task.top - mBarHeight;
        if (stripTop < 0) stripTop = task.top;
        return new Rect(task.left, stripTop, task.right, stripTop + mBarHeight);
    }

    /**
     * The chrome's layer relative to the task: one below when the chrome sits outside the task,
     * so the task covers the client hole and keeps its touches; one above when the caption
     * overlaps the task.
     */
    int chromeLayer(int targetLayer, boolean captionOverTask) {
        return captionOverTask ? targetLayer + 1 : Math.max(0, targetLayer - 1);
    }

    /**
     * The task's XML insets plus chrome padding. A caption over the task becomes a top inset so
     * the app is not drawn under it; a resize bar pushed against the bottom of the work area
     * becomes a bottom inset.
     */
    @NonNull
    Insets chromeInsets(@NonNull Insets baseInsets, @NonNull Rect task, boolean captionOverTask,
            @NonNull Rect workArea) {
        int extraTop = captionOverTask ? mBarHeight : 0;
        int extraBottom = 0;
        if (!captionOverTask && !workArea.isEmpty()
                && task.bottom > workArea.bottom - mResizeBarHeight) {
            extraBottom = mResizeBarHeight;
        }
        return Insets.of(
                baseInsets.left,
                baseInsets.top + extraTop,
                baseInsets.right,
                baseInsets.bottom + extraBottom);
    }

    /**
     * Keep {@link #MIN_VISIBLE_PX} of {@code rect} inside {@code displayBounds} on every side and
     * its caption below the top edge. Size is unchanged.
     */
    void clampPosition(@NonNull Rect rect, @NonNull Rect displayBounds) {
        int minLeft = displayBounds.left - rect.width() + MIN_VISIBLE_PX;
        int maxLeft = displayBounds.right - MIN_VISIBLE_PX;
        int minTop = displayBounds.top + mBarHeight;
        int maxTop = displayBounds.bottom - MIN_VISIBLE_PX;
        int left = Math.max(minLeft, Math.min(maxLeft, rect.left));
        int top = Math.max(minTop, Math.min(maxTop, rect.top));
        rect.offsetTo(left, top);
    }

    /**
     * Grow {@code rect} back to the minimum client size, moving the edge the user is dragging
     * ({@code directionX} / {@code directionY}: -1 left/top, +1 right/bottom, 0 untouched).
     */
    void enforceMinimumSize(@NonNull Rect rect, int directionX, int directionY) {
        if (rect.width() < mMinimumClientSize) {
            if (directionX < 0) rect.left = rect.right - mMinimumClientSize;
            else rect.right = rect.left + mMinimumClientSize;
        }
        if (rect.height() < mMinimumClientSize) {
            if (directionY < 0) rect.top = rect.bottom - mMinimumClientSize;
            else rect.bottom = rect.top + mMinimumClientSize;
        }
    }
}
