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

import static com.android.car.scalableui.loader.xml.parser.PanelControllerParser.OVERLAY_PANEL_ID_TAG;

import android.content.Context;
import android.graphics.Rect;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.car.scalableui.model.PanelControllerMetadata;
import com.android.car.scalableui.model.Variant;
import com.android.car.scalableui.model.Win98FreeVariant;
import com.android.car.scalableui.panel.PanelPool;
import com.android.systemui.car.wm.scalableui.panel.DecorPanel;
import com.android.systemui.car.wm.scalableui.panel.PanelUtils;
import com.android.systemui.car.wm.scalableui.panel.TaskPanel;
import com.android.wm.shell.automotive.AutoSurfaceTransaction;
import com.android.wm.shell.automotive.AutoSurfaceTransactionFactory;
import com.android.wm.shell.automotive.AutoTaskStackController;
import com.android.wm.shell.automotive.AutoTaskStackState;
import com.android.wm.shell.automotive.AutoTaskStackTransaction;
import com.android.wm.shell.automotive.RootTaskStack;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.dagger.WMSingleton;
import com.android.wm.shell.shared.annotations.ShellMainThread;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

/**
 * Z-order of the Win98 windows.
 *
 * <p>Each window is a pair of layers: the task at {@code n}, its chrome at {@code n - 1}. Windows
 * live in {@code [WIN98_LAYER_BASE, floor)}, where the floor is the layer the product declares
 * for the panels in {@code win98_always_on_top_panels}. Panels in {@code win98_stay_back_panels}
 * are the backdrop: they are never raised and never renumbered.
 *
 * <p>A raise is described with an {@link AutoTaskStackTransaction}, which the shell treats as the
 * stack's final layer; {@code setTaskSurfaceTransientLayer} is animation-only and does not
 * survive the next transition. Every stack whose layer changes has to be in that one transaction,
 * because the coordinator only updates the stacks a transition lists.
 *
 * <p>Shell main thread only.
 */
@WMSingleton
public class Win98LayerStack {
    private static final String TAG = "Win98LayerStack";

    /**
     * First layer handed to a window, above the range product XML uses for static panels (nav
     * bars, focus ring). N windows occupy {@code [BASE, BASE + 2N)}.
     */
    static final int WIN98_LAYER_BASE = 20;

    /**
     * Above this the stack is compacted back down to {@link #WIN98_LAYER_BASE}. Each raise costs
     * two layers; compaction moves every window in one transition, so the ceiling is far out to
     * keep that rare rather than per click.
     */
    private static final int WIN98_LAYER_CEILING = 10_000;

    /** A decorated window that can be asked to come to the front. */
    interface Window {
        void bringToFront();
    }

    private final Context mContext;
    private final AutoTaskStackController mAutoTaskStackController;
    private final AutoSurfaceTransactionFactory mAutoSurfaceTransactionFactory;
    private final PanelUtils mPanelUtils;
    private final ShellExecutor mShellMainExecutor;

    /**
     * Task stack id → layer requested from the shell but not yet reported by
     * {@code getTaskStackStateMap()}, which only refreshes when a transition completes.
     */
    private final Map<Integer, Integer> mRequestedLayer = new HashMap<>();

    /** Target panel id → the window decorating it. */
    private final Map<String, Window> mWindows = new HashMap<>();

    @Inject
    public Win98LayerStack(
            Context context,
            AutoTaskStackController autoTaskStackController,
            AutoSurfaceTransactionFactory autoSurfaceTransactionFactory,
            PanelUtils panelUtils,
            @ShellMainThread ShellExecutor shellMainExecutor) {
        mContext = context;
        mAutoTaskStackController = autoTaskStackController;
        mAutoSurfaceTransactionFactory = autoSurfaceTransactionFactory;
        mPanelUtils = panelUtils;
        mShellMainExecutor = shellMainExecutor;
    }

    // ---------------------------------------------------------------- windows

    /** A window now decorates {@code targetPanelId}. */
    void register(@NonNull String targetPanelId, @NonNull Window window) {
        mWindows.put(targetPanelId, window);
    }

    /** Only if {@code window} is still the registered one: a replacement may already own it. */
    void unregister(@NonNull String targetPanelId, @NonNull Window window) {
        mWindows.remove(targetPanelId, window);
    }

    /** Raise the window decorating {@code targetPanelId}; no-op when none does. */
    void raise(@NonNull String targetPanelId) {
        Window window = mWindows.get(targetPanelId);
        if (window != null) window.bringToFront();
    }

    // ---------------------------------------------------------------- product lists

    /** Listed in {@code win98_stay_back_panels}: never focused by a click, never raised. */
    boolean isStayBack(@NonNull String panelId) {
        return listed(R.array.win98_stay_back_panels).contains(panelId);
    }

    /** Listed in {@code win98_always_on_top_panels}: windows are never raised over it. */
    boolean isAlwaysOnTop(@NonNull String panelId) {
        return listed(R.array.win98_always_on_top_panels).contains(panelId);
    }

    /**
     * The layer windows must stay below, from {@code win98_always_on_top_layer}; no ceiling when
     * the product sets 0. Declared rather than derived from the protected panels: a panel has one
     * layer per variant and can switch at any moment, after the windows already hold theirs.
     */
    int floor() {
        int floor = mContext.getResources().getInteger(R.integer.win98_always_on_top_layer);
        return floor > 0 ? floor : Integer.MAX_VALUE;
    }

    @NonNull
    private Set<String> listed(int arrayRes) {
        return new HashSet<>(Arrays.asList(mContext.getResources().getStringArray(arrayRes)));
    }

    // ---------------------------------------------------------------- shell state

    /**
     * The layer the shell has applied for {@code stackId}, or the one already requested and still
     * in flight; {@link Integer#MIN_VALUE} when neither exists. Never the panel model, which the
     * caller has usually just rewritten.
     */
    int appliedLayer(int stackId) {
        AutoTaskStackState applied = mAutoTaskStackController.getTaskStackStateMap().get(stackId);
        int shellLayer = applied != null ? applied.getLayer() : Integer.MIN_VALUE;
        Integer requested = mRequestedLayer.get(stackId);
        if (requested == null) return shellLayer;
        if (requested == shellLayer) {
            // The shell caught up; stop shadowing it.
            mRequestedLayer.remove(stackId);
            return shellLayer;
        }
        return requested;
    }

    /** Remember a layer handed to the shell, so an in-flight raise is not repeated. */
    void rememberRequested(int stackId, int layer) {
        mRequestedLayer.put(stackId, layer);
    }

    /** A window is gone; a successor must not inherit the raise it asked for. */
    void forgetRequested(int stackId) {
        mRequestedLayer.remove(stackId);
    }

    /** Hand a described restack to the shell. */
    void startTransition(@NonNull AutoTaskStackTransaction stackTransaction) {
        mShellMainExecutor.execute(() -> mAutoTaskStackController.startTransition(stackTransaction));
    }

    // ---------------------------------------------------------------- ordering

    /** Whether {@code target} is already above every other floating window. */
    boolean isInFront(@NonNull TaskPanel target) {
        RootTaskStack stack = target.getRootStack();
        if (stack == null) return false;
        int highestOther = Integer.MIN_VALUE;
        for (TaskPanel other : floatingWindowsExcept(target)) {
            RootTaskStack otherStack = other.getRootStack();
            if (otherStack == null) continue;
            highestOther = Math.max(highestOther, appliedLayer(otherStack.getId()));
        }
        return appliedLayer(stack.getId()) > highestOther;
    }

    /**
     * The layer that puts {@code target} in front without moving anyone else: one pair above the
     * highest floating window, capped below the floor.
     *
     * <p>For callers that hand the layer to an event-driven transition they do not control and so
     * cannot carry neighbour restacks with it. Stepping over the current maximum never needs a
     * neighbour to move; compaction is left to {@link #frontLayer(TaskPanel, AutoTaskStackTransaction)}.
     */
    int frontLayer(@NonNull TaskPanel target) {
        int highestLayer = WIN98_LAYER_BASE - 2;
        for (TaskPanel other : floatingWindowsExcept(target)) {
            RootTaskStack stack = other.getRootStack();
            highestLayer = Math.max(highestLayer,
                    stack == null ? other.getLayer() : appliedLayer(stack.getId()));
        }
        return Math.min(highestLayer + 2, floor() - 2);
    }

    /**
     * The layer that puts {@code target} in front; every other window whose layer changes is
     * added to {@code stackTransaction}, and its chrome is moved in a surface transaction.
     *
     * <p>While the other windows' layers are still a usable order the target simply steps over
     * them: every stack in a transition pays a leash round trip, so only the raised window should
     * move. Otherwise the whole stack is compacted into consecutive pairs from
     * {@link #WIN98_LAYER_BASE}.
     */
    int frontLayer(@NonNull TaskPanel target, @NonNull AutoTaskStackTransaction stackTransaction) {
        List<TaskPanel> others = floatingWindowsExcept(target);
        // Order by what the shell has applied: the model carries what the previous click wrote.
        others.sort(Comparator.comparingInt(panel -> {
            RootTaskStack stack = panel.getRootStack();
            return stack == null ? panel.getLayer() : appliedLayer(stack.getId());
        }));
        int floor = floor();

        if (isUsableOrder(others, floor)) {
            int highestLayer = WIN98_LAYER_BASE;
            for (TaskPanel other : others) {
                RootTaskStack stack = other.getRootStack();
                if (stack != null) {
                    highestLayer = Math.max(highestLayer, appliedLayer(stack.getId()));
                }
            }
            return Math.min(highestLayer + 2, floor - 2);
        }

        Map<String, DecorPanel> barsByTarget = barsByTarget();
        AutoSurfaceTransaction chromeTransaction =
                mAutoSurfaceTransactionFactory.createTransaction("Win98Restack");
        boolean restacked = false;
        int layer = WIN98_LAYER_BASE;
        for (TaskPanel other : others) {
            RootTaskStack otherStack = other.getRootStack();
            boolean shellNeedsIt =
                    otherStack != null && appliedLayer(otherStack.getId()) != layer;
            // Judge on the shell, not the model: the model carries whatever was last written.
            if (shellNeedsIt || other.getLayer() != layer) {
                other.setLayer(layer);
                Variant otherVariant = mPanelUtils.getCurrentVariant(other.getPanelId());
                if (otherVariant != null) Win98FreeVariant.assignLayer(otherVariant, layer);
                // Send the stack only when the shell is not already at that depth: every stack
                // in a transition pays a leash reparent round trip, visible as a blink.
                if (otherStack != null && shellNeedsIt) {
                    Rect otherBounds =
                            otherVariant != null ? otherVariant.getBounds() : other.getBounds();
                    stackTransaction.setTaskStackState(
                            otherStack.getId(),
                            new AutoTaskStackState(
                                    new Rect(otherBounds), /* isAboveBarrier= */ true, layer));
                    rememberRequested(otherStack.getId(), layer);
                    Log.d(TAG, "restack " + other.getPanelId() + " -> " + layer);
                }
                // The chrome moves with its task, or another window shows through between them.
                DecorPanel otherBar = barsByTarget.get(other.getPanelId());
                if (otherBar != null) {
                    otherBar.setLayer(layer - 1);
                    Variant barVariant = mPanelUtils.getCurrentVariant(otherBar.getPanelId());
                    if (barVariant != null) Win98FreeVariant.assignLayer(barVariant, layer - 1);
                    if (otherBar.getAutoDecor() != null) {
                        chromeTransaction.setZOrder(otherBar.getAutoDecor(), layer - 1);
                        restacked = true;
                    }
                }
            }
            layer += 2;
        }
        // Chrome z lands immediately while task z travels with a transition started later; apply
        // on the shell queue so both land in the same order.
        if (restacked) {
            mShellMainExecutor.execute(chromeTransaction::apply);
        }
        // A floor lower than BASE + 2 × window count cannot be honoured; the top windows then
        // share the last layer below it rather than being raised over the protected panels.
        return Math.min(layer, floor - 2);
    }

    /**
     * Whether the raised window can simply be placed above {@code others}: their layers are
     * distinct, at least {@link #WIN98_LAYER_BASE}, and below the ceiling.
     */
    private boolean isUsableOrder(@NonNull List<TaskPanel> others, int floor) {
        int previous = Integer.MIN_VALUE;
        for (TaskPanel other : others) {
            RootTaskStack stack = other.getRootStack();
            if (stack == null) return false;
            int layer = appliedLayer(stack.getId());
            if (layer < WIN98_LAYER_BASE) return false;
            if (layer == previous) return false;
            previous = layer;
        }
        // Leave room for the raise itself, and compact before raises pile up against an
        // always-on-top panel, where every window would share one layer.
        int ceiling = Math.min(WIN98_LAYER_CEILING, floor - 2);
        return previous <= ceiling - 2;
    }

    /** Visible floating TaskPanels other than {@code target}. */
    @NonNull
    private List<TaskPanel> floatingWindowsExcept(@Nullable TaskPanel target) {
        Set<String> stayBack = listed(R.array.win98_stay_back_panels);
        Set<String> alwaysOnTop = listed(R.array.win98_always_on_top_panels);
        List<TaskPanel> others = new ArrayList<>();
        PanelPool.getInstance().forEach(panel -> {
            if (panel == target || !panel.isVisible()) return;
            if (!(panel instanceof TaskPanel)) return;
            // Stay-back panels are the backdrop and always-on-top panels are the ceiling;
            // neither is a floating window, so neither takes part in the ordering.
            String panelId = panel.getPanelId();
            if (stayBack.contains(panelId) || alwaysOnTop.contains(panelId)) return;
            others.add((TaskPanel) panel);
        });
        return others;
    }

    /** Target panel id → the bar decorating it, read off the bars' {@code <OverlayPanelId>}. */
    @NonNull
    private static Map<String, DecorPanel> barsByTarget() {
        Map<String, DecorPanel> bars = new HashMap<>();
        PanelPool.getInstance().forEach(panel -> {
            if (!(panel instanceof DecorPanel)) return;
            PanelControllerMetadata metadata = panel.getPanelControllerMetadata();
            if (metadata == null) return;
            String decoratedPanelId = metadata.getStringConfiguration(OVERLAY_PANEL_ID_TAG);
            if (decoratedPanelId != null) bars.put(decoratedPanelId, (DecorPanel) panel);
        });
        return bars;
    }
}
