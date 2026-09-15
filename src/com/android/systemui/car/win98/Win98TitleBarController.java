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

import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.car.scalableui.manager.StateManager;
import com.android.car.scalableui.model.Event;
import com.android.car.scalableui.model.PanelControllerMetadata;
import com.android.car.scalableui.model.PanelState;
import com.android.car.scalableui.model.Transition;
import com.android.car.scalableui.model.Variant;
import com.android.car.scalableui.model.Win98FreeVariant;
import com.android.car.scalableui.panel.DecorPanelController;
import com.android.car.scalableui.panel.Panel;
import com.android.car.scalableui.panel.PanelPool;
import com.android.systemui.car.wm.scalableui.EventDispatcher;
import com.android.systemui.car.wm.scalableui.panel.DecorPanel;
import com.android.systemui.car.wm.scalableui.panel.PanelUtils;
import com.android.systemui.car.wm.scalableui.panel.TaskPanel;
import com.android.systemui.car.wm.scalableui.panel.controller.DecorPanelViewMap;
import com.android.systemui.car.wm.scalableui.panel.panelupdates.PanelUpdateConsumer;
import com.android.systemui.car.wm.scalableui.view.DecorPanelControllerBase;
import com.android.wm.shell.automotive.AutoDecor;
import com.android.wm.shell.automotive.AutoSurfaceTransaction;
import com.android.wm.shell.automotive.AutoSurfaceTransactionFactory;
import com.android.wm.shell.automotive.AutoTaskStackState;
import com.android.wm.shell.automotive.AutoTaskStackTransaction;
import com.android.wm.shell.automotive.RootTaskStack;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.shared.annotations.ExternalMainThread;
import com.android.wm.shell.shared.annotations.ShellMainThread;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

import java.util.Map;
import java.util.Optional;

import javax.inject.Provider;

/**
 * Windows 98 chrome for one Scalable UI {@link TaskPanel}.
 *
 * <p>The chrome is its own {@link DecorPanel}. This controller keeps it wrapped around the
 * target named by {@code <OverlayPanelId>} (title above, frame on the sides, resize bar
 * below, one layer under the task so the task covers the client hole and keeps its touches)
 * and turns buttons and drops into Scalable UI events:
 *
 * <pre>
 *   button / gesture        event fired                      target XML is expected to map it to
 *   ─────────────────       ─────────────────────────────    ─────────────────────────────────────
 *   minimize (_)            _Win98_&lt;target&gt;_Minimize        variant "win98_minimized"
 *   close (X)               _Win98_&lt;target&gt;_Close           a variant with Visibility=false
 *   maximize (□)            _Win98_&lt;target&gt;_Fullscreen      variant "win98_fullscreen"
 *   restore (❐ / chip tap)  _Win98_&lt;target&gt;_Restore         the panel's normal variant
 *   drag / resize release   _Win98_&lt;target&gt;_Drop_a/b       runtime {@link Win98FreeVariant}
 * </pre>
 *
 * <p>Drag is surface-only (same size, new position). Resize draws a rubber-band shim and leaves
 * the task alone until release. On release the dropped rect is written into one of two
 * ping-ponged {@link Win98FreeVariant}s and a Drop event asks the coordinator for one WCT; two
 * variants because StateManager skips a transition whose from and to are the same object.
 *
 * <p>Z-order is {@link Win98LayerStack}'s; geometry is {@link Win98ChromeGeometry}'s.
 *
 * <p>Threading: the decor view lives on a SurfaceControlViewHost created on the shell main
 * executor. {@link PanelUpdateConsumer} callbacks arrive on the process main thread and
 * TaskStackListener callbacks on a binder thread; both hop first.
 */
public class Win98TitleBarController extends DecorPanelControllerBase
        implements Win98LayerStack.Window {
    private static final String TAG = "Win98TitleBar";

    static final String EVENT_PREFIX = "_Win98_";
    static final String EVENT_MINIMIZE = "_Minimize";
    static final String EVENT_CLOSE = "_Close";
    static final String EVENT_RESTORE = "_Restore";
    static final String EVENT_FULLSCREEN = "_Fullscreen";
    static final String EVENT_DROP_A = "_Drop_a";
    static final String EVENT_DROP_B = "_Drop_b";

    static final String VARIANT_FULLSCREEN = "win98_fullscreen";
    static final String VARIANT_OPEN = "open";
    /**
     * The hidden variant Minimize lands in, distinct from the one Close uses: "is this window
     * minimized?" is read off the variant name, because an instance flag does not survive the
     * fresh controller {@code DecorPanel.reset()} builds on theme change or user unlock.
     */
    static final String VARIANT_MINIMIZED = "win98_minimized";

    @Nullable private final PanelUpdateConsumer mPanelUpdateConsumer;
    private final EventDispatcher mEventDispatcher;
    private final AutoSurfaceTransactionFactory mAutoSurfaceTransactionFactory;
    private final Context mContext;
    private final ShellExecutor mShellMainExecutor;
    /** The process main thread, the only one PanelUpdateConsumer's callback set may be mutated on. */
    private final ShellExecutor mMainExecutor;
    private final PanelUtils mPanelUtils;
    private final Win98Taskbar mTaskbar;
    private final Win98LayerStack mLayerStack;
    private final Win98ChromeGeometry mGeometry;

    private final String mTargetId;
    /** False for stay-back panels: clicks neither focus the task nor raise its z-order. */
    private final boolean mBringToFront;
    private final int mTouchSlop;

    @Nullable private Win98TitleBarView mDecorView;
    private volatile boolean mReleased;
    private boolean mListenerRegistered;

    private final Rect mTargetBounds = new Rect();
    /**
     * False until the target reports otherwise; {@link #syncToTarget} reads the model at
     * registration. A bar must never draw for a target that has not shown itself.
     */
    private boolean mTargetVisible;
    private boolean mChipMode;

    private boolean mDragging;
    private boolean mResizing;
    /** -1 left/top edge, +1 right/bottom edge, 0 untouched; see {@link Win98TitleBarView.Listener}. */
    private int mResizeDirectionX;
    private int mResizeDirectionY;
    private float mDownRawX;
    private float mDownRawY;
    private final Rect mGestureStartTarget = new Rect();
    private final Rect mGestureTarget = new Rect();
    private Rect mDisplayBounds = new Rect();

    private boolean mUseFreeA = true;
    private boolean mFreeInstalled;
    private final Rect mLastFreeBounds = new Rect();
    /** The target's XML insets, captured once before chrome padding is added on top. */
    @Nullable private Insets mBaseInsets;
    private boolean mBaseInsetsCaptured;

    private final PanelUpdateConsumer.PanelUpdateCallback mTargetCallback =
            new PanelUpdateConsumer.PanelUpdateCallback() {
                @Override
                public void onBoundsChange(@NonNull String panelId, @NonNull Rect rect) {
                    if (!mTargetId.equals(panelId)) return;
                    final Rect copy = new Rect(rect);
                    mShellMainExecutor.execute(() -> {
                        if (mReleased) return;
                        onTargetBounds(copy);
                    });
                }

                @Override
                public void onVisibilityChange(@NonNull String panelId, boolean isVisible) {
                    if (!mTargetId.equals(panelId)) return;
                    mShellMainExecutor.execute(() -> {
                        if (mReleased) return;
                        onTargetVisibility(isVisible);
                    });
                }
            };

    private final TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override
        public void onTaskMovedToFront(int taskId) {
            mShellMainExecutor.execute(() -> {
                if (mReleased) return;
                refreshTitle();
            });
        }

        @Override
        public void onTaskFocusChanged(int taskId, boolean focused) {
            if (!focused) return;
            mShellMainExecutor.execute(() -> {
                if (mReleased) return;
                refreshActive();
                // A tap inside the app focuses the task without touching the bar; raise the
                // window as if the caption had been clicked. The callback carries the leaf task
                // id, so the check compares root against root, and bringToFront() is a no-op
                // when this window is already in front.
                if (isFocusedRootTask()) bringToFront();
            });
        }
    };

    private final Win98Taskbar.Listener mTaskbarListener = displayId -> {
        if (mReleased || !mChipMode) return;
        applyChip();
    };

    @AssistedInject
    public Win98TitleBarController(
            @Assisted String panelId,
            @Assisted PanelControllerMetadata metadata,
            @DecorPanelViewMap Map<Class<?>, Provider<View>> decorPanelViewMap,
            Optional<PanelUpdateConsumer> panelUpdateConsumerOptional,
            EventDispatcher eventDispatcher,
            AutoSurfaceTransactionFactory autoSurfaceTransactionFactory,
            Context context,
            @ShellMainThread ShellExecutor shellMainExecutor,
            @ExternalMainThread ShellExecutor mainExecutor,
            PanelUtils panelUtils,
            Win98Taskbar taskbar,
            Win98LayerStack layerStack) {
        super(panelId, metadata, decorPanelViewMap);
        mMainExecutor = mainExecutor;
        mPanelUpdateConsumer = panelUpdateConsumerOptional.orElse(null);
        mEventDispatcher = eventDispatcher;
        mAutoSurfaceTransactionFactory = autoSurfaceTransactionFactory;
        mContext = context;
        mShellMainExecutor = shellMainExecutor;
        mPanelUtils = panelUtils;
        mTaskbar = taskbar;
        mLayerStack = layerStack;
        mGeometry = Win98ChromeGeometry.fromResources(context);

        String target = metadata.getStringConfiguration(OVERLAY_PANEL_ID_TAG);
        if (target == null || target.isEmpty()) {
            throw new IllegalArgumentException(
                    "Win98 title bar " + panelId + " needs <OverlayPanelId>target</OverlayPanelId>");
        }
        mTargetId = target;
        mBringToFront = !layerStack.isStayBack(mTargetId);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        if (mPanelUpdateConsumer == null) {
            Log.w(TAG, mPanelId + ": PanelUpdateConsumer absent (enable_ext_panel_updates off?);"
                    + " the bar cannot follow " + mTargetId);
        }
    }

    @AssistedFactory
    public interface Factory extends DecorPanelController.Factory<Win98TitleBarController> {
        Win98TitleBarController create(String panelId, PanelControllerMetadata metadata);
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    @NonNull
    public View getView() {
        View view = super.getView();
        if (view instanceof Win98TitleBarView && view != mDecorView) {
            // One detach listener per view, or cleanup() runs once per registration.
            mDecorView = (Win98TitleBarView) view;
            mDecorView.setListener(mViewListener);
            mDecorView.addOnAttachStateChangeListener(mDetachListener);
        }
        if (!mListenerRegistered) {
            mReleased = false;
            mListenerRegistered = true;
            // Registration and unregistration must happen on the same thread; see cleanup().
            mMainExecutor.execute(this::registerOnMain);
            mShellMainExecutor.execute(() -> {
                if (mReleased) return;
                mTaskbar.addListener(mTaskbarListener);
                mLayerStack.register(mTargetId, this);
                syncToTarget();
                refreshTitle();
                refreshActive();
            });
        }
        return view;
    }

    private final View.OnAttachStateChangeListener mDetachListener =
            new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(@NonNull View view) { }
                @Override public void onViewDetachedFromWindow(@NonNull View view) { cleanup(); }
            };

    /** Main-thread half of registration; paired with {@link #unregisterOnMain}. */
    private void registerOnMain() {
        if (mReleased) return;
        if (mPanelUpdateConsumer != null) {
            mPanelUpdateConsumer.registerCallback(mTargetId, mTargetCallback);
        }
        try {
            ActivityTaskManager.getService().registerTaskStackListener(mTaskStackListener);
        } catch (RemoteException e) {
            Log.e(TAG, "failed to register TaskStackListener", e);
        }
    }

    /**
     * Main-thread half of teardown. {@code PanelUpdateConsumer} iterates its callback set on the
     * main thread without a lock, so the set is only ever mutated there.
     */
    private void unregisterOnMain() {
        if (mPanelUpdateConsumer != null) {
            mPanelUpdateConsumer.unregisterCallback(mTargetId, mTargetCallback);
        }
        try {
            ActivityTaskManager.getService().unregisterTaskStackListener(mTaskStackListener);
        } catch (RemoteException e) {
            Log.w(TAG, "failed to unregister TaskStackListener", e);
        }
    }

    /**
     * Tear down only. {@code DecorPanel.refreshTheme()} follows this with {@code reset()}, which
     * builds a new controller without destroying this one; the replacement registers itself in
     * its own {@code getView()}.
     */
    @Override
    public void refreshTheme() {
        cleanup();
        super.refreshTheme();
    }

    @Override
    public void destroy() {
        cleanup();
        super.destroy();
    }

    /**
     * Reachable from the main thread (destroy, refreshTheme) and the shell thread (view detach);
     * each half is handed to the thread that owns that state.
     */
    private void cleanup() {
        mReleased = true;
        if (mListenerRegistered) {
            mListenerRegistered = false;
            mMainExecutor.execute(this::unregisterOnMain);
        }
        // Resolve the display now: the target may have left the pool by the time this runs.
        final int displayId = displayId();
        mShellMainExecutor.execute(() -> {
            mTaskbar.removeListener(mTaskbarListener);
            mTaskbar.release(displayId, mPanelId);
            mLayerStack.unregister(mTargetId, this);
            TaskPanel target = targetPanel();
            RootTaskStack stack = target != null ? target.getRootStack() : null;
            if (stack != null) mLayerStack.forgetRequested(stack.getId());
            mDragging = false;
            mResizing = false;
            mChipMode = false;
            mDecorView = null;
            // Re-read the XML base on next use so chrome padding is never captured as base.
            mBaseInsetsCaptured = false;
            mBaseInsets = null;
        });
    }

    // ---------------------------------------------------------------- following the target

    private void onTargetBounds(@NonNull Rect bounds) {
        if (!bounds.equals(mTargetBounds)) {
            Variant currentVariant = mPanelUtils.getCurrentVariant(mTargetId);
            Log.d(TAG, mPanelId + ": target bounds " + mTargetBounds + " -> " + bounds
                    + " variant=" + (currentVariant != null ? currentVariant.getIdName() : "null")
                    + " variantBounds="
                    + (currentVariant != null ? currentVariant.getBounds() : "null")
                    + (mDragging ? " [during drag]" : "")
                    + (mResizing ? " [during resize]" : ""));
        }
        mTargetBounds.set(bounds);
        if (mDragging || mResizing || mChipMode || !mTargetVisible) return;
        boolean fullscreen = isTargetFullscreen();
        if (mDecorView != null) {
            mDecorView.setFullscreen(fullscreen);
            mDecorView.setWrapChrome(!fullscreen && !mGeometry.captionOverlapsTask(bounds));
            mDecorView.setResizePreview(null, null);
        }
        applyChrome(chromeRectFor(bounds), chromeLayer(fullscreen), true);
        applyChromeInsets(targetPanel(), bounds);
    }

    private void onTargetVisibility(boolean visible) {
        mTargetVisible = visible;
        // A window that vanishes mid-gesture must not leave the gesture armed: onTargetBounds
        // bails out while dragging or resizing, so the chrome would stop following.
        if (!visible && (mDragging || mResizing)) {
            abortGesture();
        }
        if (visible) {
            if (mChipMode) exitChip();
            boolean fullscreen = isTargetFullscreen();
            if (mDecorView != null) {
                mDecorView.setWrapChrome(
                        !fullscreen && !mGeometry.captionOverlapsTask(mTargetBounds));
            }
            applyChrome(chromeRectFor(mTargetBounds), chromeLayer(fullscreen), true);
            applyChromeInsets(targetPanel(), mTargetBounds);
            return;
        }
        // Minimized vs closed is read off the variant, so a replacement controller that gets
        // visibility=false replayed into it reaches the same answer as the one that minimized.
        if (isTargetMinimized()) {
            enterChip();
        } else {
            applyChrome(currentChromeRect(), chromeLayer(false), false);
        }
    }

    /**
     * Read the target's geometry and visibility from the model: callbacks only fire on change,
     * and a window untouched since boot never publishes.
     */
    private void syncToTarget() {
        TaskPanel target = targetPanel();
        if (target == null) return;
        Variant current = mPanelUtils.getCurrentVariant(mTargetId);
        Rect bounds = current != null ? current.getBounds() : target.getBounds();
        if (bounds != null && !bounds.isEmpty()) mTargetBounds.set(bounds);
        boolean visible = current != null ? current.isVisible() : target.isVisible();
        onTargetVisibility(visible);
    }

    // ---------------------------------------------------------------- chrome

    private void enterChip() {
        mChipMode = true;
        mTaskbar.acquire(displayId(), mPanelId);
        if (mDecorView != null) {
            mDecorView.setChipMode(true);
            mDecorView.setWrapChrome(false);
            mDecorView.setResizePreview(null, null);
        }
        applyChip();
    }

    private void exitChip() {
        mChipMode = false;
        mTaskbar.release(displayId(), mPanelId);
        if (mDecorView != null) mDecorView.setChipMode(false);
    }

    private void applyChip() {
        Rect chip = mTaskbar.chipBounds(displayId(), mPanelId);
        if (chip == null) return;
        applyChrome(chip, targetLayer() + 1, true);
    }

    /** The chrome for {@code task} in the window's current mode (chip, fullscreen, wrapping). */
    @NonNull
    private Rect chromeRectFor(@NonNull Rect task) {
        boolean captionOverTask = isTargetFullscreen() || mGeometry.captionOverlapsTask(task);
        return mGeometry.chromeRect(task, mChipMode || captionOverTask, captionOverTask);
    }

    private int chromeLayer(boolean fullscreen) {
        return mGeometry.chromeLayer(targetLayer(),
                fullscreen || mGeometry.captionOverlapsTask(mTargetBounds));
    }

    /** XML insets plus chrome padding for the window as it is now. */
    @NonNull
    private Insets chromeInsetsFor(@NonNull Rect task) {
        return chromeInsetsFor(task,
                isTargetFullscreen() || mGeometry.captionOverlapsTask(task));
    }

    /**
     * As {@link #chromeInsetsFor(Rect)} with the caption position given explicitly: a drop
     * stamps insets for the rect the window is going to, not the one it is leaving.
     */
    @NonNull
    private Insets chromeInsetsFor(@NonNull Rect task, boolean captionOverTask) {
        if (!mBaseInsetsCaptured) {
            Variant current = mPanelUtils.getCurrentVariant(mTargetId);
            mBaseInsets = current != null ? current.getInsets() : Insets.NONE;
            mBaseInsetsCaptured = true;
        }
        Insets baseInsets = mBaseInsets != null ? mBaseInsets : Insets.NONE;
        // During a gesture this runs per MOVE frame; the work area is captured when it begins.
        Rect workArea = (mDragging || mResizing)
                ? mDisplayBounds
                : mTaskbar.workArea(displayId());
        return mGeometry.chromeInsets(baseInsets, task, captionOverTask, workArea);
    }

    private void applyChromeInsets(@Nullable TaskPanel target, @NonNull Rect task) {
        if (target == null || !mTargetVisible || mChipMode) return;
        Insets insets = chromeInsetsFor(task);
        if (insets.equals(target.getInsets())) return;
        target.setInsets(insets);
        Variant current = mPanelUtils.getCurrentVariant(mTargetId);
        if (current != null) Win98FreeVariant.assignInsets(current, insets);
        AutoSurfaceTransaction transaction =
                mAutoSurfaceTransactionFactory.createTransaction("Win98Insets");
        target.update(transaction, (Variant) null, false);
        transaction.apply();
    }

    private Rect currentChromeRect() {
        DecorPanel bar = barPanel();
        return bar == null ? chromeRectFor(mTargetBounds) : new Rect(bar.getBounds());
    }

    /**
     * Moves the chrome surface and records the same geometry in the bar's panel and its current
     * variant: any running transition re-applies every panel from the variant, so a surface-only
     * write would be undone on the next frame.
     */
    private void applyChrome(@NonNull Rect rect, int layer, boolean visible) {
        DecorPanel bar = barPanel();
        if (bar == null) return;
        AutoDecor decor = bar.getAutoDecor();
        if (decor == null) return;
        bar.setBounds(rect);
        bar.setLayer(layer);
        bar.setVisibility(visible);
        PanelState barState = StateManager.getPanelState(mPanelId);
        Variant barVariant = barState != null ? barState.getCurrentVariant() : null;
        if (barVariant != null) {
            Win98FreeVariant.assignGeometry(
                    barVariant, rect, layer, barVariant.getInsets(), visible);
        }
        AutoSurfaceTransaction transaction =
                mAutoSurfaceTransactionFactory.createTransaction("Win98Bar");
        transaction.setBounds(decor, rect);
        transaction.setCrop(decor,
                new Rect(0, 0, Math.max(1, rect.width()), Math.max(1, rect.height())));
        transaction.setZOrder(decor, layer);
        transaction.setVisibility(decor, visible);
        transaction.apply();
    }

    // ---------------------------------------------------------------- drag

    private void onStripTouch(@NonNull MotionEvent event) {
        if (mChipMode || mResizing) return;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginDrag(event);
                break;
            case MotionEvent.ACTION_MOVE:
                if (mDragging) moveDrag(event);
                break;
            case MotionEvent.ACTION_UP:
                if (mDragging) endDrag();
                break;
            case MotionEvent.ACTION_CANCEL:
                if (mDragging) abortGesture();
                break;
            default:
                break;
        }
    }

    private void beginDrag(@NonNull MotionEvent event) {
        TaskPanel target = targetPanel();
        DecorPanel bar = barPanel();
        // Focus only once the drag can actually start, so a touch on a bar whose task has no
        // leash yet does not steal focus and leave the caption blue on a window that never moved.
        if (target == null || bar == null || target.getLeash() == null) return;
        // Before focusTarget(): bringToFront() stamps insets, which read this rect.
        mDisplayBounds = mTaskbar.workArea(displayId());
        focusTarget();
        mDragging = true;
        mDownRawX = event.getRawX();
        mDownRawY = event.getRawY();
        mGestureStartTarget.set(target.getBounds());
        mGestureTarget.set(mGestureStartTarget);
    }

    private void moveDrag(@NonNull MotionEvent event) {
        TaskPanel target = targetPanel();
        DecorPanel bar = barPanel();
        if (target == null || bar == null || bar.getAutoDecor() == null) {
            mDragging = false;
            return;
        }
        int deltaX = Math.round(event.getRawX() - mDownRawX);
        int deltaY = Math.round(event.getRawY() - mDownRawY);
        mGestureTarget.set(mGestureStartTarget);
        mGestureTarget.offset(deltaX, deltaY);
        mGeometry.clampPosition(mGestureTarget, mDisplayBounds);
        Rect chrome = chromeRectFor(mGestureTarget);

        // Same size, new origin, surface-only: no WCT, no config change. The variant is stamped
        // too, because a transition landing mid-drag re-applies the task from it.
        target.setBounds(new Rect(mGestureTarget));
        Variant current = mPanelUtils.getCurrentVariant(mTargetId);
        if (current != null) {
            Win98FreeVariant.assignGeometry(
                    current, mGestureTarget, target.getLayer(), chromeInsetsFor(mGestureTarget));
        }
        bar.setBounds(new Rect(chrome));
        AutoSurfaceTransaction transaction =
                mAutoSurfaceTransactionFactory.createTransaction("Win98Drag");
        transaction.setTaskSurfacePosition(
                target.getRootTaskId(), mGestureTarget.left, mGestureTarget.top);
        transaction.setBounds(bar.getAutoDecor(), chrome);
        transaction.apply();
    }

    private void endDrag() {
        mDragging = false;
        int movedX = Math.abs(mGestureTarget.left - mGestureStartTarget.left);
        int movedY = Math.abs(mGestureTarget.top - mGestureStartTarget.top);
        if (movedX < mTouchSlop && movedY < mTouchSlop) return;
        commitFree(mGestureTarget);
    }

    /**
     * Drop an in-flight drag or resize without committing it: the system cancelled the gesture
     * or the target disappeared under the finger, so its last position is not a place the user
     * chose. The surface goes back to where the gesture began.
     */
    private void abortGesture() {
        boolean wasDragging = mDragging;
        mDragging = false;
        mResizing = false;
        if (mDecorView != null) mDecorView.setResizePreview(null, null);
        if (wasDragging) {
            TaskPanel target = targetPanel();
            if (target != null && target.getLeash() != null) {
                target.setBounds(new Rect(mGestureStartTarget));
                Variant current = mPanelUtils.getCurrentVariant(mTargetId);
                if (current != null) {
                    Win98FreeVariant.assignGeometry(current, mGestureStartTarget,
                            target.getLayer(), chromeInsetsFor(mGestureStartTarget));
                }
                AutoSurfaceTransaction transaction =
                        mAutoSurfaceTransactionFactory.createTransaction("Win98DragAbort");
                transaction.setTaskSurfacePosition(target.getRootTaskId(),
                        mGestureStartTarget.left, mGestureStartTarget.top);
                transaction.apply();
            }
        }
        if (mTargetVisible) {
            applyChrome(chromeRectFor(mGestureStartTarget), chromeLayer(false), true);
        }
    }

    // ---------------------------------------------------------------- resize

    private void onResizeTouch(@NonNull MotionEvent event, int directionX, int directionY) {
        if (mChipMode || mDragging || isTargetFullscreen()) return;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginResize(event, directionX, directionY);
                break;
            case MotionEvent.ACTION_MOVE:
                if (mResizing) moveResize(event);
                break;
            case MotionEvent.ACTION_UP:
                if (mResizing) endResize();
                break;
            case MotionEvent.ACTION_CANCEL:
                if (mResizing) abortGesture();
                break;
            default:
                break;
        }
    }

    private void beginResize(@NonNull MotionEvent event, int directionX, int directionY) {
        TaskPanel target = targetPanel();
        if (target == null || target.getLeash() == null) return;
        // Before focusTarget(): bringToFront() stamps insets, which read this rect.
        mDisplayBounds = mTaskbar.workArea(displayId());
        focusTarget();
        mResizing = true;
        mResizeDirectionX = directionX;
        mResizeDirectionY = directionY;
        mDownRawX = event.getRawX();
        mDownRawY = event.getRawY();
        mGestureStartTarget.set(target.getBounds());
        mGestureTarget.set(mGestureStartTarget);
        if (mDecorView != null) mDecorView.setWrapChrome(true);
        showShim();
    }

    private void moveResize(@NonNull MotionEvent event) {
        int deltaX = Math.round(event.getRawX() - mDownRawX);
        int deltaY = Math.round(event.getRawY() - mDownRawY);
        mGestureTarget.set(mGestureStartTarget);
        if (mResizeDirectionX < 0) mGestureTarget.left += deltaX;
        if (mResizeDirectionX > 0) mGestureTarget.right += deltaX;
        if (mResizeDirectionY > 0) mGestureTarget.bottom += deltaY;
        if (mResizeDirectionY < 0) mGestureTarget.top += deltaY;
        mGeometry.enforceMinimumSize(mGestureTarget, mResizeDirectionX, mResizeDirectionY);
        showShim();
    }

    private void endResize() {
        mResizing = false;
        if (mDecorView != null) mDecorView.setResizePreview(null, null);
        if (mGestureTarget.equals(mGestureStartTarget)) {
            applyChrome(chromeRectFor(mGestureStartTarget), chromeLayer(false), true);
            return;
        }
        // Snap the surface to the new size first so the Drop WCT has nothing to interpolate.
        snapTaskTo(mGestureTarget);
        commitFree(mGestureTarget);
    }

    /** Apply task bounds to the model and leash immediately, without a variant transition. */
    private void snapTaskTo(@NonNull Rect bounds) {
        TaskPanel target = targetPanel();
        if (target == null || target.getLeash() == null) return;
        Rect snapped = new Rect(bounds);
        target.setBounds(snapped);
        // Same reason as moveDrag: the variant, not the panel field, is what updateInternal reads.
        Variant current = mPanelUtils.getCurrentVariant(mTargetId);
        if (current != null) {
            Win98FreeVariant.assignGeometry(
                    current, snapped, target.getLayer(), chromeInsetsFor(snapped));
        }
        AutoSurfaceTransaction transaction =
                mAutoSurfaceTransactionFactory.createTransaction("Win98ResizeSnap");
        transaction.setTaskSurfacePosition(target.getRootTaskId(), snapped.left, snapped.top);
        transaction.setTaskSurfaceCrop(target.getRootTaskId(),
                new Rect(0, 0, Math.max(1, snapped.width()), Math.max(1, snapped.height())));
        transaction.apply();
        applyChrome(chromeRectFor(snapped), chromeLayer(false), true);
        applyChromeInsets(target, snapped);
    }

    /**
     * Raise the decor over the task, stretch it to union(old, new), draw the dashed shim. The
     * task surface is not touched.
     */
    private void showShim() {
        Rect oldChrome = chromeRectFor(mGestureStartTarget);
        Rect newChrome = chromeRectFor(mGestureTarget);
        Rect union = new Rect(oldChrome);
        union.union(newChrome);
        applyChrome(union, targetLayer() + 1, true);
        if (mDecorView == null) return;
        Rect windowInView = new Rect(oldChrome);
        windowInView.offset(-union.left, -union.top);
        Rect shimInView = new Rect(newChrome);
        shimInView.offset(-union.left, -union.top);
        mDecorView.setResizePreview(windowInView, shimInView);
    }

    // ---------------------------------------------------------------- free placement

    /**
     * Write {@code taskBounds} into the next free variant and fire its Drop event. Creates the
     * A/B pair and their transitions on first use.
     */
    private void commitFree(@NonNull Rect taskBounds) {
        if (!ensureFreeVariants()) {
            Log.w(TAG, mPanelId + ": cannot install free variants for " + mTargetId);
            fire(EVENT_RESTORE);
            return;
        }
        PanelState state = StateManager.getPanelState(mTargetId);
        if (state == null) return;
        boolean useA = mUseFreeA;
        Win98FreeVariant destination = (Win98FreeVariant) state.getVariant(
                useA ? Win98FreeVariant.ID_A : Win98FreeVariant.ID_B);
        if (destination == null) {
            Log.w(TAG, mPanelId + ": free variant missing after install");
            return;
        }
        TaskPanel target = targetPanel();
        int layer = target != null ? target.getLayer() : 0;
        if (mBringToFront && target != null) {
            layer = Math.max(layer, mLayerStack.frontLayer(target));
        }
        // One stamp with the final layer, so bounds and layer are never momentarily inconsistent.
        // The drop target is by definition neither fullscreen nor a chip, so only its own
        // geometry decides whether the caption overlaps it.
        Win98FreeVariant.assignGeometry(destination, taskBounds, layer,
                chromeInsetsFor(taskBounds, mGeometry.captionOverlapsTask(taskBounds)));
        Log.d(TAG, mPanelId + ": commitFree " + destination.getIdName() + " bounds=" + taskBounds
                + " layer=" + layer);
        mLastFreeBounds.set(taskBounds);
        mUseFreeA = !useA;
        fire(useA ? EVENT_DROP_A : EVENT_DROP_B);
    }

    private boolean ensureFreeVariants() {
        PanelState state = StateManager.getPanelState(mTargetId);
        if (state == null) return false;
        Variant existingA = state.getVariant(Win98FreeVariant.ID_A);
        Variant existingB = state.getVariant(Win98FreeVariant.ID_B);
        if (existingA instanceof Win98FreeVariant && existingB instanceof Win98FreeVariant) {
            mFreeInstalled = true;
            return true;
        }
        Variant parent = state.getCurrentVariant();
        if (parent == null || !parent.isVisible()
                || parent.getIdName().equals(VARIANT_FULLSCREEN)) {
            parent = state.getVariant(VARIANT_OPEN);
        }
        if (parent == null) return false;
        Win98FreeVariant freeA = new Win98FreeVariant(Win98FreeVariant.ID_A, parent);
        Win98FreeVariant freeB = new Win98FreeVariant(Win98FreeVariant.ID_B, parent);
        state.addVariant(freeA);
        state.addVariant(freeB);
        state.addTransition(new Transition.Builder(null, freeA)
                .addEvent(new Event.Builder(EVENT_PREFIX + mTargetId + EVENT_DROP_A).build())
                .setDefaultDuration(0)
                .build());
        state.addTransition(new Transition.Builder(null, freeB)
                .addEvent(new Event.Builder(EVENT_PREFIX + mTargetId + EVENT_DROP_B).build())
                .setDefaultDuration(0)
                .build());
        mFreeInstalled = true;
        return true;
    }

    // ---------------------------------------------------------------- buttons

    private final Win98TitleBarView.Listener mViewListener = new Win98TitleBarView.Listener() {
        @Override public void onStripTouch(@NonNull MotionEvent event) {
            Win98TitleBarController.this.onStripTouch(event);
        }

        @Override public void onResizeTouch(@NonNull MotionEvent event, int directionX,
                int directionY) {
            Win98TitleBarController.this.onResizeTouch(event, directionX, directionY);
        }

        @Override public void onMinimize() {
            focusTarget(/* raise= */ false);
            fire(EVENT_MINIMIZE);
        }

        @Override public void onMaximizeToggle() {
            // No bringToFront() here: its WCT would commit the current bounds and fight the
            // fullscreen/restore transition. The destination variant is stamped with a front
            // layer instead, so the one WCT lands in front.
            focusTarget(/* raise= */ false);
            if (isTargetFullscreen()) {
                if (mFreeInstalled && !mLastFreeBounds.isEmpty()) {
                    commitFree(mLastFreeBounds);
                } else {
                    stampFrontLayer(VARIANT_OPEN);
                    fire(EVENT_RESTORE);
                }
            } else {
                stampFrontLayer(VARIANT_FULLSCREEN);
                fire(EVENT_FULLSCREEN);
            }
        }

        @Override public void onClose() {
            focusTarget(/* raise= */ false);
            fire(EVENT_CLOSE);
        }

        @Override public void onChipTap() {
            if (mFreeInstalled && !mLastFreeBounds.isEmpty()) {
                commitFree(mLastFreeBounds);
            } else {
                fire(EVENT_RESTORE);
            }
        }
    };

    private void fire(@NonNull String suffix) {
        mEventDispatcher.executeEvent(new Event.Builder(EVENT_PREFIX + mTargetId + suffix)
                .setPanelId(mPanelId)
                .build());
    }

    /** Caption click or drag: this window becomes the focused task, caption goes blue. */
    private void focusTarget() {
        focusTarget(/* raise= */ true);
    }

    /**
     * Paint the caption active and, when {@code raise}, put the window in front.
     *
     * <p>Focus is never requested through {@code ActivityTaskManager.setFocusedTask}: that emits
     * a {@code MOVE_TO_TOP} transition of its own. The raise carries the focus instead
     * ({@code AutoTaskStackTransaction.setFocusedTaskStack}), so a click is one transition.
     * With {@code raise} false, nothing is asked of WindowManager: a button press is about to
     * start a variant transition that a raise would fight.
     */
    private void focusTarget(boolean raise) {
        if (!mBringToFront) return;
        if (targetPanel() == null) return;
        if (mDecorView != null) mDecorView.setActive(true);
        if (raise) bringToFront();
    }

    /**
     * Write a front layer onto the live task and {@code destinationVariantName} so the upcoming
     * event's WCT uses it. Does not start its own transition.
     */
    private void stampFrontLayer(@NonNull String destinationVariantName) {
        if (!mBringToFront) return;
        TaskPanel target = targetPanel();
        PanelState state = StateManager.getPanelState(mTargetId);
        if (target == null || state == null) return;
        int layer = Math.max(target.getLayer(), mLayerStack.frontLayer(target));
        target.setLayer(layer);
        Variant current = mPanelUtils.getCurrentVariant(mTargetId);
        if (current != null) {
            // The transition animates from the current variant, so it must hold the live rect.
            Rect liveBounds = new Rect(mTargetBounds);
            Win98FreeVariant.assignGeometry(current, liveBounds, layer, chromeInsetsFor(liveBounds));
        }
        // Only the layer on the destination: its bounds ARE the point of the transition.
        Variant destination = state.getVariant(destinationVariantName);
        if (destination != null) Win98FreeVariant.assignLayer(destination, layer);
        Log.d(TAG, mPanelId + ": stampFrontLayer destination=" + destinationVariantName
                + " layer=" + layer
                + " from=" + (current != null ? current.getIdName() : "null")
                + " liveBounds=" + mTargetBounds);
    }

    /**
     * Raise this window above every other floating window, in one transition that also carries
     * the focus. The chrome belongs to no task stack, so its z goes through {@link #applyChrome}.
     */
    @Override
    public void bringToFront() {
        if (mReleased || !mBringToFront || mChipMode || !mTargetVisible) return;
        TaskPanel target = targetPanel();
        if (target == null || target.getLeash() == null) return;
        RootTaskStack stack = target.getRootStack();
        if (stack == null) return;
        // Already in front: no transition at all. Judged on the shell's applied state plus the
        // raise in flight, so the focus change this raise triggers does not send a second one.
        if (mLayerStack.isInFront(target)) return;

        // One transaction for the whole restack; stacks left out keep their old surface z.
        AutoTaskStackTransaction stackTransaction = new AutoTaskStackTransaction();
        int taskLayer = mLayerStack.frontLayer(target, stackTransaction);
        boolean fullscreen = isTargetFullscreen();
        int raisedChromeLayer = fullscreen ? taskLayer + 1 : Math.max(0, taskLayer - 1);
        Rect liveBounds = new Rect(mTargetBounds);

        target.setLayer(taskLayer);
        Variant taskVariant = mPanelUtils.getCurrentVariant(mTargetId);
        if (taskVariant != null) {
            // The whole tuple: the surface is rebuilt from the variant in one go.
            Log.d(TAG, mPanelId + ": bringToFront stamp variant=" + taskVariant.getIdName()
                    + " bounds " + taskVariant.getBounds() + " -> " + liveBounds
                    + " layer " + taskVariant.getLayer() + " -> " + taskLayer);
            Win98FreeVariant.assignGeometry(
                    taskVariant, liveBounds, taskLayer, chromeInsetsFor(liveBounds));
        }

        // AutoTaskStackState is a full description: bounds pass through unchanged.
        boolean raiseNeeded = mLayerStack.appliedLayer(stack.getId()) != taskLayer;
        if (raiseNeeded) {
            stackTransaction.setTaskStackState(
                    stack.getId(),
                    new AutoTaskStackState(liveBounds, /* isAboveBarrier= */ true, taskLayer));
            mLayerStack.rememberRequested(stack.getId(), taskLayer);
        }
        // Focus rides in the same transaction; the shell applies it last as a reorder to top.
        boolean restacking = raiseNeeded || !stackTransaction.getTaskStackStates().isEmpty();
        if (restacking) {
            stackTransaction.setFocusedTaskStack(stack.getId());
            mLayerStack.startTransition(stackTransaction);
        }

        if (!mDragging && !mResizing) {
            applyChrome(chromeRectFor(mTargetBounds), raisedChromeLayer, true);
        }
    }

    // ---------------------------------------------------------------- caption state

    private void refreshTitle() {
        if (mDecorView == null) return;
        TaskPanel target = targetPanel();
        String packageName = target == null ? null : target.getTopTaskPackageName();
        CharSequence label = mTargetId;
        if (packageName != null) {
            try {
                PackageManager packageManager = mContext.getPackageManager();
                ApplicationInfo applicationInfo =
                        packageManager.getApplicationInfo(packageName, 0);
                label = packageManager.getApplicationLabel(applicationInfo);
            } catch (PackageManager.NameNotFoundException e) {
                label = packageName;
            }
        }
        mDecorView.setTitle(label);
    }

    private void refreshActive() {
        if (mDecorView == null) return;
        mDecorView.setActive(isFocusedRootTask());
    }

    /**
     * Whether the focused root task is this bar's target; decides the caption colour and whether
     * a focus change raises the window. Root against root: {@code onTaskFocusChanged} reports
     * the leaf task, never the panel's root.
     */
    private boolean isFocusedRootTask() {
        TaskPanel target = targetPanel();
        if (target == null) return false;
        try {
            ActivityTaskManager.RootTaskInfo focused =
                    ActivityTaskManager.getService().getFocusedRootTaskInfo();
            return focused != null && focused.taskId == target.getRootTaskId();
        } catch (RemoteException e) {
            Log.w(TAG, "getFocusedRootTaskInfo failed", e);
            return false;
        }
    }

    // ---------------------------------------------------------------- model queries

    private boolean isTargetFullscreen() {
        Variant current = mPanelUtils.getCurrentVariant(mTargetId);
        return current != null && VARIANT_FULLSCREEN.equals(current.getIdName());
    }

    private boolean isTargetMinimized() {
        Variant current = mPanelUtils.getCurrentVariant(mTargetId);
        return current != null && VARIANT_MINIMIZED.equals(current.getIdName());
    }

    private int targetLayer() {
        TaskPanel target = targetPanel();
        return target == null ? 0 : target.getLayer();
    }

    private int displayId() {
        TaskPanel target = targetPanel();
        return target == null ? 0 : target.getDisplayId();
    }

    @Nullable
    private TaskPanel targetPanel() {
        Panel panel = PanelPool.getInstance().getPanel(mTargetId);
        return panel instanceof TaskPanel ? (TaskPanel) panel : null;
    }

    @Nullable
    private DecorPanel barPanel() {
        Panel panel = PanelPool.getInstance().getPanel(mPanelId);
        return panel instanceof DecorPanel ? (DecorPanel) panel : null;
    }
}
