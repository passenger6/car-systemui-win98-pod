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
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import android.view.Display;

import androidx.annotation.NonNull;

import com.android.car.scalableui.manager.StateManager;
import com.android.car.scalableui.model.PanelState;
import com.android.car.scalableui.model.PanelType;
import com.android.car.scalableui.panel.PanelPool;
import com.android.wm.shell.dagger.WMSingleton;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Hands out taskbar slots to minimized windows, one per display, so several
 * {@link Win98TitleBarController}s can park their chips side by side along the bottom edge
 * without knowing about each other. Slots are ordered by acquisition; releasing one shifts
 * the chips to its right, exactly like closing a window in the Windows 98 taskbar.
 *
 * <p>Mutations are meant to arrive on the shell main executor, but this class cannot see which
 * thread a caller is on and a controller's getView()/cleanup() run on the process main thread.
 * The collections are therefore guarded rather than trusted; the locks are uncontended.
 */
@WMSingleton
public class Win98Taskbar {
    private final Context mContext;
    private final DisplayManager mDisplayManager;
    // panelId of the chip owner, in slot order; one list per display id.
    private final SparseArray<List<String>> mSlotsByDisplay = new SparseArray<>();

    /** Notified after a slot is acquired or released, so sibling chips can re-lay out. */
    public interface Listener {
        void onSlotsChanged(int displayId);
    }

    private final List<Listener> mListeners = new ArrayList<>();

    @Inject
    public Win98Taskbar(Context context) {
        mContext = context;
        mDisplayManager = context.getSystemService(DisplayManager.class);
    }

    public void addListener(@NonNull Listener listener) {
        synchronized (mListeners) {
            if (!mListeners.contains(listener)) mListeners.add(listener);
        }
    }

    public void removeListener(@NonNull Listener listener) {
        synchronized (mListeners) {
            mListeners.remove(listener);
        }
    }

    private void notifyChanged(int displayId) {
        List<Listener> snapshot;
        synchronized (mListeners) {
            snapshot = new ArrayList<>(mListeners);
        }
        // Outside the lock: a listener may call back into acquire/release.
        for (Listener listener : snapshot) listener.onSlotsChanged(displayId);
    }

    /** Registers {@code panelId} as minimized on {@code displayId}; idempotent. */
    public void acquire(int displayId, @NonNull String panelId) {
        synchronized (mSlotsByDisplay) {
            List<String> slots = slots(displayId);
            if (slots.contains(panelId)) return;
            slots.add(panelId);
        }
        notifyChanged(displayId);
    }

    /** Removes {@code panelId}'s chip; the chips after it move left. */
    public void release(int displayId, @NonNull String panelId) {
        boolean removed;
        synchronized (mSlotsByDisplay) {
            List<String> slots = mSlotsByDisplay.get(displayId);
            removed = slots != null && slots.remove(panelId);
        }
        if (removed) notifyChanged(displayId);
    }

    /** Position of {@code panelId}'s chip, or {@code null} if it holds no slot. */
    public Rect chipBounds(int displayId, @NonNull String panelId) {
        int index;
        synchronized (mSlotsByDisplay) {
            List<String> slots = mSlotsByDisplay.get(displayId);
            index = slots == null ? -1 : slots.indexOf(panelId);
        }
        if (index < 0) return null;
        int width = mContext.getResources().getDimensionPixelSize(R.dimen.win98_chip_width);
        int height = mContext.getResources().getDimensionPixelSize(R.dimen.win98_chip_height);
        int margin = mContext.getResources().getDimensionPixelSize(R.dimen.win98_chip_margin);
        int gap = mContext.getResources().getDimensionPixelSize(R.dimen.win98_chip_gap);
        int inset = mContext.getResources().getDimensionPixelSize(R.dimen.win98_chip_inset_left);
        Rect display = displayBounds(displayId);
        int left = display.left + inset + margin + index * (width + gap);
        int top = display.bottom - margin - height;
        return new Rect(left, top, left + width, top + height);
    }

    /**
     * The display minus the system bars: where a window may sit without hiding under the status
     * bar or a navigation bar.
     *
     * <p>Derived from the {@link PanelType#SYSTEM_BAR} panels themselves rather than a fixed
     * inset, so adding, removing or resizing a bar in the product XML moves the usable area with
     * it. Only bars spanning a whole edge are subtracted: a centred navigation bar such as
     * {@code bottom_bar_center_panel} at {@code Rect(704,987-1269,1080)} leaves the rest of the
     * bottom edge usable, and treating it as a full-width strip would waste it.
     */
    @NonNull
    public Rect workArea(int displayId) {
        Rect display = displayBounds(displayId);
        Rect usable = new Rect(display);
        PanelPool.getInstance().forEach(panel -> {
            if (!panel.isVisible() || panel.getDisplayId() != displayId) return;
            // The type lives on the PanelState: at runtime a system bar is a plain SysUIPanel.
            PanelState state = StateManager.getPanelState(panel.getPanelId());
            if (state == null || state.getType() != PanelType.SYSTEM_BAR) return;
            Rect bar = panel.getBounds();
            if (bar == null || bar.isEmpty()) return;
            boolean spansWidth = bar.left <= display.left && bar.right >= display.right;
            boolean spansHeight = bar.top <= display.top && bar.bottom >= display.bottom;
            if (spansWidth && bar.top <= display.top) {
                usable.top = Math.max(usable.top, bar.bottom);
            } else if (spansWidth && bar.bottom >= display.bottom) {
                usable.bottom = Math.min(usable.bottom, bar.top);
            } else if (spansHeight && bar.left <= display.left) {
                usable.left = Math.max(usable.left, bar.right);
            } else if (spansHeight && bar.right >= display.right) {
                usable.right = Math.min(usable.right, bar.left);
            }
        });
        // A misconfigured bar could swallow the display; fall back rather than return an empty
        // rect that callers would place windows into.
        return usable.width() > 0 && usable.height() > 0 ? usable : display;
    }

    /** Real pixel bounds of the display, used for chips and as the basis of {@link #workArea}. */
    @NonNull
    public Rect displayBounds(int displayId) {
        Display display = mDisplayManager.getDisplay(displayId);
        DisplayMetrics metrics = new DisplayMetrics();
        if (display != null) {
            display.getRealMetrics(metrics);
        } else {
            metrics = mContext.getResources().getDisplayMetrics();
        }
        return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
    }

    /** Caller must hold the {@code mSlotsByDisplay} lock. */
    private List<String> slots(int displayId) {
        List<String> slots = mSlotsByDisplay.get(displayId);
        if (slots == null) {
            slots = new ArrayList<>();
            mSlotsByDisplay.put(displayId, slots);
        }
        return slots;
    }
}
