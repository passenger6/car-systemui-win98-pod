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

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import static com.android.car.scalableui.loader.xml.parser.PanelControllerParser.CONTROLLER_NAME_TAG;
import static com.android.car.scalableui.loader.xml.parser.PanelControllerParser.OVERLAY_PANEL_ID_TAG;
import static com.android.car.scalableui.loader.xml.parser.PanelControllerParser.VIEW_TAG;
import static com.android.systemui.car.wm.scalableui.systemevents.SystemEventConstants.SYSTEM_TASK_OPEN_EVENT_ID;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.SurfaceControl;
import android.window.TransitionInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.car.scalableui.manager.StateManager;
import com.android.car.scalableui.model.Event;
import com.android.car.scalableui.model.PanelControllerMetadata;
import com.android.car.scalableui.model.PanelState;
import com.android.car.scalableui.model.PanelType;
import com.android.car.scalableui.model.Role;
import com.android.car.scalableui.model.Transition;
import com.android.car.scalableui.model.Variant;
import com.android.car.scalableui.panel.Panel;
import com.android.car.scalableui.panel.PanelPool;
import com.android.systemui.car.wm.scalableui.panel.TaskPanel;
import com.android.systemui.car.wm.scalableui.panel.panelupdates.PanelConfigReadStateMonitor;
import com.android.wm.shell.automotive.AutoTaskStackController;
import com.android.wm.shell.automotive.RootTaskStack;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.dagger.WMSingleton;
import com.android.wm.shell.shared.annotations.ShellMainThread;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;

import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;

/**
 * Blank windows that any app can be launched into, so the desktop is not limited to the panels
 * the product wrote into XML.
 *
 * <p>The pool never refuses a launch: it starts with {@link #INITIAL_WINDOWS}, reuses a window
 * whose task has gone, and registers one more window whenever every existing one is occupied.
 * Both the {@link TaskPanel}s and the
 * {@link com.android.systemui.car.wm.scalableui.panel.DecorPanel}s decorating them are registered
 * at runtime with {@link StateManager#addState}; nothing about the pool lives in the product RRO.
 *
 * <p>Launch routing: ActivityManager places a new task into whichever root task stack is the
 * display's launch root at placement time. The pool points
 * {@link AutoTaskStackController#setDefaultRootTaskStackOnDisplay} at a free window and moves it
 * on after every placement. A window whose root task stack does not exist yet cannot be aimed at
 * from here, so such a window is registered with the default {@link Role}: {@code TaskPanel}
 * then makes it the launch root itself, inside the callback that hands it the stack. That is
 * how the first window is aimed at after boot and how a window added on demand is aimed at.
 *
 * <p>Two watchers back the routing up: a {@link Transitions.TransitionObserver} sees every task
 * that arrives, including launches that never pass through a request the pool could intercept,
 * and a {@link TaskStackListener} re-checks a pending aim whenever a task is created and frees a
 * window once ActivityManager has destroyed its task.
 *
 * <p>Threading: every mutation happens on the shell main thread; callbacks arriving elsewhere
 * hop first.
 */
@WMSingleton
public class Win98WindowPool implements Transitions.TransitionObserver {

    private static final String TAG = "Win98WindowPool";

    /** Panel ids are {@code win98_window_<n>}; their bars are {@code win98_window_<n>_bar}. */
    static final String WINDOW_PREFIX = "win98_window_";
    static final String BAR_SUFFIX = "_bar";

    /**
     * Windows registered at boot. A window's root task stack is created asynchronously after
     * registration, so a few are warmed up front; further windows are added on demand.
     */
    static final int INITIAL_WINDOWS = 6;

    // The bar reads these names off the variant, so they are its constants.
    private static final String VARIANT_BASE = "base";
    private static final String VARIANT_OPEN = Win98TitleBarController.VARIANT_OPEN;
    private static final String VARIANT_MINIMIZED = Win98TitleBarController.VARIANT_MINIMIZED;
    private static final String VARIANT_HIDDEN = "win98_hidden";
    private static final String VARIANT_FULLSCREEN = Win98TitleBarController.VARIANT_FULLSCREEN;

    /** Cascade offset between consecutive windows, in pixels. */
    private static final int CASCADE_STEP = 48;
    /** Fraction of the work area a freshly opened window covers. */
    private static final float WINDOW_WIDTH_FRACTION = 0.3f;
    private static final float WINDOW_HEIGHT_FRACTION = 0.55f;

    private static final int NO_SLOT = -1;

    private final Context mContext;
    private final AutoTaskStackController mAutoTaskStackController;
    private final ShellExecutor mShellMainExecutor;
    private final Win98Taskbar mTaskbar;
    private final Win98LayerStack mLayerStack;
    private final Transitions mTransitions;
    private final PanelConfigReadStateMonitor mConfigMonitor;

    /**
     * Slot → ids of the tasks ActivityManager has placed in it. A slot is free when the set is
     * empty. A set rather than one id: a root task stack holds any number of tasks, and a launch
     * can land in an occupied slot while a new window's stack is still being created.
     */
    private final SparseArray<Set<Integer>> mOccupants = new SparseArray<>();
    /** Slot → root task stack id, once scalable-ui has created the stack. */
    private final SparseIntArray mStackIdBySlot = new SparseIntArray();

    private final int mBarHeight;
    private final int mFrameWidth;
    /** Windows are registered below this layer; 0 or less means no ceiling. */
    private final int mAlwaysOnTopFloor;

    private int mDisplayId = DEFAULT_DISPLAY;
    /** Windows registered so far; slots are {@code 0..mWindowCount-1}. */
    private int mWindowCount;
    /** The slot launches are routed to, or {@link #NO_SLOT}. */
    private int mCurrentSlot = NO_SLOT;
    /** A slot that should receive launches as soon as its root task stack exists. */
    private int mPendingSlot = NO_SLOT;
    private boolean mStarted;

    private final TaskStackWatcher mTaskStackWatcher = new TaskStackWatcher();

    @Inject
    public Win98WindowPool(
            Context context,
            AutoTaskStackController autoTaskStackController,
            @ShellMainThread ShellExecutor shellMainExecutor,
            Win98Taskbar taskbar,
            Win98LayerStack layerStack,
            Transitions transitions,
            ShellInit shellInit,
            PanelConfigReadStateMonitor configMonitor) {
        mContext = context;
        mAutoTaskStackController = autoTaskStackController;
        mShellMainExecutor = shellMainExecutor;
        mTaskbar = taskbar;
        mLayerStack = layerStack;
        mTransitions = transitions;
        mConfigMonitor = configMonitor;
        mBarHeight = context.getResources().getDimensionPixelSize(R.dimen.win98_title_bar_height);
        mFrameWidth = context.getResources().getDimensionPixelSize(R.dimen.win98_frame_width);
        mAlwaysOnTopFloor = context.getResources().getInteger(R.integer.win98_always_on_top_layer);
        // Registering a panel needs scalable-ui's PanelCreatorDelegate, and PanelConfigReader.init()
        // clears every panel before installing it; PanelConfigReadStateMonitor is the framework's
        // signal that both have happened.
        shellInit.addInitCallback(this::onShellInit, this);
    }

    /**
     * Runs inside {@code ShellInit.init()}, which walks every registered callback in one pass on
     * the shell main thread. Posting from here queues behind that whole pass.
     */
    private void onShellInit() {
        mShellMainExecutor.execute(this::startWhenPanelsExist);
    }

    private void startWhenPanelsExist() {
        if (panelsLoaded()) {
            start(DEFAULT_DISPLAY);
            return;
        }
        mConfigMonitor.addListener(mConfigReadyListener);
    }

    /**
     * Whether scalable-ui has finished loading the panel config. {@code isReady()} answers true
     * unconditionally when the ext-panel-updates flag is off, so a non-empty pool is the check.
     */
    private boolean panelsLoaded() {
        boolean[] anyPanel = { false };
        PanelPool.getInstance().forEach(panel -> anyPanel[0] = true);
        return anyPanel[0];
    }

    private final PanelConfigReadStateMonitor.Listener mConfigReadyListener =
            new PanelConfigReadStateMonitor.Listener() {
                @Override
                public void onReady() {
                    mConfigMonitor.removeListener(this);
                    // Fires from inside PanelConfigReader; post so registration lands after it.
                    mShellMainExecutor.execute(() -> start(DEFAULT_DISPLAY));
                }
            };

    /**
     * Register the initial windows and start routing launches into them. Safe to call more than
     * once.
     *
     * @param displayId the display the pool lives on
     */
    public void start(int displayId) {
        if (mStarted) return;
        mStarted = true;
        mDisplayId = displayId;

        // Only the first window carries the default role: the framework aims the launch root at
        // whichever default-role stack appeared last, and the first launch belongs in slot 0.
        for (int slot = 0; slot < INITIAL_WINDOWS; slot++) {
            registerSlot(slot, /* launchRoot= */ slot == 0);
        }
        mWindowCount = INITIAL_WINDOWS;

        mTransitions.registerObserver(this);
        try {
            ActivityTaskManager.getService().registerTaskStackListener(mTaskStackWatcher);
        } catch (RemoteException e) {
            Log.e(TAG, "failed to register TaskStackListener", e);
        }

        aimAt(0);
        Log.d(TAG, "pool started with " + INITIAL_WINDOWS + " windows on display " + displayId);
    }

    // ---------------------------------------------------------------- registration

    /**
     * Register window {@code slot} and its bar. With {@code launchRoot} the window gets the
     * default {@link Role}, and {@code TaskPanel} makes it the display's launch root as soon as
     * its root task stack appears (see {@link #aimAt}).
     */
    private void registerSlot(int slot, boolean launchRoot) {
        registerWindow(slot, launchRoot);
        registerBar(slot);
    }

    /**
     * Register one blank {@link TaskPanel}. No app role: a role with activities is how a panel
     * claims a specific app, and these take whatever is launched next.
     */
    private void registerWindow(int slot, boolean launchRoot) {
        String panelId = WINDOW_PREFIX + slot;
        Rect open = cascadeBounds(slot);
        int layer = taskLayerFor(slot);

        // Hidden, but with real geometry: the panel is created in its first variant, and an
        // empty rect yields a zero-sized crop and safe bounds.
        Variant baseVariant = variant(VARIANT_BASE, open, false, layer);
        Variant openVariant = variant(VARIANT_OPEN, open, true, layer);
        // Two hidden variants: the bar tells "minimized" (keeps a taskbar chip) from "closed" by
        // the variant name.
        Variant minimizedVariant = variant(VARIANT_MINIMIZED, open, false, layer);
        Variant hiddenVariant = variant(VARIANT_HIDDEN, open, false, layer);
        // Maximised means the usable screen, not the whole display: a window under the status
        // bar loses its caption to it.
        Variant fullscreenVariant = variant(VARIANT_FULLSCREEN, workArea(), true, layer);

        PanelState.Builder builder = new PanelState.Builder(panelId, PanelType.TASK)
                .setDisplayId(mDisplayId)
                .setDefaultVariant(VARIANT_BASE)
                .addVariant(baseVariant)
                .addVariant(openVariant)
                .addVariant(minimizedVariant)
                .addVariant(hiddenVariant)
                .addVariant(fullscreenVariant);
        if (launchRoot) {
            // StateManager.addState applies the role before TaskPanel.init() creates the stack,
            // so the panel already knows it is the launch root when the stack appears.
            builder.setRole(new Role.Builder().setIsDefault(true).build());
        }

        // The bar's buttons are events; the window has to map them. Drop_a/Drop_b are not
        // declared here: the controller installs those variants and transitions on first drag.
        String prefix = Win98TitleBarController.EVENT_PREFIX + panelId;
        builder.addTransition(
                transition(prefix + Win98TitleBarController.EVENT_MINIMIZE, minimizedVariant));
        builder.addTransition(
                transition(prefix + Win98TitleBarController.EVENT_CLOSE, hiddenVariant));
        builder.addTransition(
                transition(prefix + Win98TitleBarController.EVENT_RESTORE, openVariant));
        builder.addTransition(
                transition(prefix + Win98TitleBarController.EVENT_FULLSCREEN, fullscreenVariant));
        // A minimized or closed window comes back when something opens in it again.
        builder.addTransition(taskOpenTransition(panelId, minimizedVariant, openVariant));
        builder.addTransition(taskOpenTransition(panelId, hiddenVariant, openVariant));
        // A blank slot becomes a window the moment ActivityManager lands a task in it.
        builder.addTransition(taskOpenTransition(panelId, baseVariant, openVariant));

        StateManager.addState(builder.build());
    }

    /** {@code onEvent} → {@code destination}, applied immediately: user actions, not animations. */
    @NonNull
    private Transition transition(@NonNull String eventId, @NonNull Variant destination) {
        return new Transition.Builder(null, destination)
                .addEvent(transitionEvent(eventId).build())
                .setDefaultDuration(0)
                .build();
    }

    /**
     * The framework's own "an app opened in this panel" event, narrowed to this panel so a launch
     * elsewhere does not un-hide it.
     */
    @NonNull
    private Transition taskOpenTransition(@NonNull String panelId,
            @NonNull Variant from, @NonNull Variant destination) {
        return new Transition.Builder(from, destination)
                .addEvent(transitionEvent(SYSTEM_TASK_OPEN_EVENT_ID)
                        .setPanelId(panelId)
                        .build())
                .setDefaultDuration(0)
                .build();
    }

    /**
     * A transition event scoped to the pool's display, exactly as {@code TransitionParser} scopes
     * the events it reads from XML. The framework fires its system events with the displays of
     * the launching user, and {@code Event.isMatch} rejects a transition whose event names no
     * display at all; without this the window only opened through the coordinator's conflict
     * reconciliation, which never published its visibility to the bar.
     */
    @NonNull
    private Event.Builder transitionEvent(@NonNull String eventId) {
        return new Event.Builder(eventId).addApplicableDisplay(mDisplayId);
    }

    /** Register the {@link Win98TitleBarController} bar that decorates window {@code slot}. */
    private void registerBar(int slot) {
        String panelId = WINDOW_PREFIX + slot + BAR_SUFFIX;
        int layer = taskLayerFor(slot) - 1;

        PanelControllerMetadata metadata = PanelControllerMetadata.builder(panelId)
                .addConfiguration(CONTROLLER_NAME_TAG, Win98TitleBarController.class.getName())
                .addConfiguration(VIEW_TAG, Win98TitleBarView.class.getName())
                .addConfiguration(OVERLAY_PANEL_ID_TAG, WINDOW_PREFIX + slot)
                .build();

        // A valid strip, not a placeholder: AutoDecorManager.createAutoDecor returns null for
        // bounds without positive width and height. The controller replaces it with the exact
        // chrome rect on the target's first bounds publish.
        Rect window = cascadeBounds(slot);
        int barTop = Math.max(0, window.top - mBarHeight);
        Rect bar = new Rect(Math.max(0, window.left - mFrameWidth), barTop,
                window.right + mFrameWidth, barTop + mBarHeight);

        // Hidden like the window it decorates; the controller shows it from onTargetVisibility.
        PanelState.Builder builder = new PanelState.Builder(panelId, PanelType.DECOR)
                .setDisplayId(mDisplayId)
                .setDefaultVariant(VARIANT_OPEN)
                .addVariant(variant(VARIANT_OPEN, bar, false, layer));
        builder.setPanelControllerMetadata(metadata);
        StateManager.addState(builder.build());
    }

    @NonNull
    private static Variant variant(String name, Rect bounds, boolean visible, int layer) {
        return new Variant.Builder(name, name)
                .setBounds(new Rect(bounds))
                .setSafeBounds(new Rect(bounds))
                .setVisibility(visible)
                .setLayer(layer)
                .setAlpha(1.0f)
                .build();
    }

    /**
     * The layer window {@code slot} is registered at: a pair per window above
     * {@link Win98LayerStack#WIN98_LAYER_BASE}, kept below the always-on-top floor. Only the
     * starting value; {@link Win98LayerStack} renumbers windows as they are raised.
     */
    private int taskLayerFor(int slot) {
        int layer = Win98LayerStack.WIN98_LAYER_BASE + slot * 2;
        return mAlwaysOnTopFloor > 0 ? Math.min(layer, mAlwaysOnTopFloor - 2) : layer;
    }

    // ---------------------------------------------------------------- routing

    /**
     * Make {@code slot} the display's launch root, so the next launch lands in it.
     *
     * <p>A slot whose root task stack does not exist yet is only remembered as pending: the
     * framework aims at it by itself when the stack appears, because the window carries the
     * default role, and the pool re-checks on the next task creation. Nothing here waits on the
     * bounds the window publishes: {@code TaskPanel} only re-publishes after the stack appears
     * when the user is already unlocked, which at boot is a race.
     */
    private void aimAt(int slot) {
        Integer stackId = stackIdOf(slot);
        if (stackId == null) {
            if (mPendingSlot != slot) {
                mPendingSlot = slot;
                Log.d(TAG, "slot " + slot + " has no root stack yet; pending");
            }
            return;
        }
        mPendingSlot = NO_SLOT;
        mCurrentSlot = slot;
        mAutoTaskStackController.setDefaultRootTaskStackOnDisplay(mDisplayId, stackId);
        Log.d(TAG, "aiming launches at slot " + slot + " (stack " + stackId + ")");
    }

    /** The lowest free slot, registering a new window when there is none. */
    private int nextSlot() {
        for (int slot = 0; slot < mWindowCount; slot++) {
            if (isFree(slot)) return slot;
        }
        return growPool();
    }

    /**
     * Register one more window and bar. Its stack appears asynchronously; the default role makes
     * the framework aim at it then, and until it does launches keep landing in the current slot.
     */
    private int growPool() {
        int slot = mWindowCount++;
        registerSlot(slot, /* launchRoot= */ true);
        Log.d(TAG, "pool grown to " + mWindowCount + " windows");
        return slot;
    }

    private boolean isFree(int slot) {
        Set<Integer> tasks = mOccupants.get(slot);
        return tasks == null || tasks.isEmpty();
    }

    @NonNull
    private Set<Integer> occupants(int slot) {
        Set<Integer> tasks = mOccupants.get(slot);
        if (tasks == null) {
            tasks = new HashSet<>();
            mOccupants.put(slot, tasks);
        }
        return tasks;
    }

    @Override
    public void onTransitionReady(@NonNull IBinder transition,
            @NonNull TransitionInfo transitionInfo,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction) {
        for (TransitionInfo.Change change : transitionInfo.getChanges()) {
            int mode = change.getMode();
            if (mode != TRANSIT_OPEN && mode != TRANSIT_TO_FRONT) {
                continue;
            }
            ActivityManager.RunningTaskInfo task = change.getTaskInfo();
            if (task == null) continue;
            // Trampoline activities live for milliseconds and die without a transition of their
            // own; tracking one would leave the slot occupied by a task that no longer exists.
            if (task.isTopActivityNoDisplay) continue;
            // An app a panel claims by role belongs in that panel, not in a blank window.
            if (isClaimedByAnotherPanel(task)) {
                Log.d(TAG, "task " + task.taskId + " belongs to a panel of its own; not pooled");
                continue;
            }
            int slot = slotOfStack(task.parentTaskId);
            if (slot < 0) {
                // A task landed outside the pool, so routing is not aimed: the stacks may not
                // have existed when it was first tried. Retry now that they do.
                if (mCurrentSlot == NO_SLOT) {
                    aimAt(mPendingSlot != NO_SLOT ? mPendingSlot : nextSlot());
                }
                continue;
            }
            onTaskLanded(slot, task.taskId);
        }
    }

    /** A task now occupies {@code slot}; hand the next launch somewhere else. */
    private void onTaskLanded(int slot, int taskId) {
        if (!occupants(slot).add(taskId)) return;
        Log.d(TAG, "task " + taskId + " landed in slot " + slot);
        // A new window belongs in front; asked now rather than when the focus change arrives,
        // which lags the launch.
        mLayerStack.raise(WINDOW_PREFIX + slot);
        // The slot the framework aimed at through the default role is not known here as
        // mCurrentSlot; a landing in the pending slot means that aim took effect.
        if (slot == mCurrentSlot || slot == mPendingSlot || mCurrentSlot == NO_SLOT) {
            aimAt(nextSlot());
        }
    }

    /** ActivityManager destroyed a task: free its slot and prefer it for the next launch. */
    private void onTaskVanished(int taskId) {
        for (int index = 0; index < mOccupants.size(); index++) {
            Set<Integer> tasks = mOccupants.valueAt(index);
            if (!tasks.remove(taskId)) continue;
            int slot = mOccupants.keyAt(index);
            if (tasks.isEmpty()) {
                Log.d(TAG, "task " + taskId + " vanished, slot " + slot + " free");
                aimAt(nextSlot());
            }
            return;
        }
    }

    /**
     * {@code onTaskCreated} fires for every task WindowManager creates, a window's root task
     * included, so it is the moment to re-check a pending aim. The shell may not have handed the
     * panel its stack yet when the callback arrives; then the aim stays pending and the next task
     * event tries again, while the default role has the framework aim the launch root itself.
     */
    private class TaskStackWatcher extends TaskStackListener {
        @Override
        public void onTaskCreated(int taskId, ComponentName componentName) {
            mShellMainExecutor.execute(() -> {
                if (mPendingSlot != NO_SLOT) aimAt(mPendingSlot);
            });
        }

        @Override
        public void onTaskRemoved(int taskId) {
            mShellMainExecutor.execute(() -> onTaskVanished(taskId));
        }
    }

    // ---------------------------------------------------------------- geometry & lookup

    /**
     * Where window {@code slot} opens: a fraction of the work area, stepped down-right so a fresh
     * window never lands exactly on the one before it, wrapped back when it would run off.
     */
    @NonNull
    private Rect cascadeBounds(int slot) {
        Rect area = workArea();
        int width = Math.round(area.width() * WINDOW_WIDTH_FRACTION);
        int height = Math.round(area.height() * WINDOW_HEIGHT_FRACTION);
        // One caption below the top edge, so the first window's chrome is on screen.
        int originTop = area.top + mBarHeight;
        int maxOffset = Math.max(0,
                Math.min(area.width() - width, area.bottom - originTop - height));
        int step = maxOffset == 0 ? 0 : (slot * CASCADE_STEP) % (maxOffset + 1);
        int left = area.left + step;
        int top = originTop + step;
        return new Rect(left, top, left + width, top + height);
    }

    /** The display minus the system bars; see {@link Win98Taskbar#workArea}. */
    @NonNull
    private Rect workArea() {
        return mTaskbar.workArea(mDisplayId);
    }

    @Nullable
    private Integer stackIdOf(int slot) {
        int cached = mStackIdBySlot.get(slot, -1);
        if (cached >= 0) return cached;
        Panel panel = PanelPool.getInstance().getPanel(WINDOW_PREFIX + slot);
        if (!(panel instanceof TaskPanel)) return null;
        RootTaskStack stack = ((TaskPanel) panel).getRootStack();
        if (stack == null) return null;
        mStackIdBySlot.put(slot, stack.getId());
        return stack.getId();
    }

    /**
     * Whether some other panel claims {@code task}'s component through its {@code role}. The
     * framework expects that app to land in its own panel, so those launches pass untouched.
     */
    private boolean isClaimedByAnotherPanel(@NonNull ActivityManager.RunningTaskInfo task) {
        ComponentName component = task.baseActivity != null
                ? task.baseActivity
                : task.topActivity;
        if (component == null) return false;
        boolean[] claimedByPanel = { false };
        PanelPool.getInstance().forEach(panel -> {
            if (claimedByPanel[0] || !(panel instanceof TaskPanel)) return;
            if (panel.getPanelId().startsWith(WINDOW_PREFIX)) return;
            Role role = panel.getRole();
            if (role == null || role.getPersistedActivities() == null) return;
            for (ComponentName persisted : role.getPersistedActivities()) {
                if (component.equals(persisted)) {
                    claimedByPanel[0] = true;
                    return;
                }
            }
        });
        return claimedByPanel[0];
    }

    /** Slot whose root stack is {@code stackId}, or -1 when the stack is not ours. */
    private int slotOfStack(int stackId) {
        if (stackId < 0) return -1;
        for (int slot = 0; slot < mWindowCount; slot++) {
            Integer slotStackId = stackIdOf(slot);
            if (slotStackId != null && slotStackId == stackId) return slot;
        }
        return -1;
    }
}
