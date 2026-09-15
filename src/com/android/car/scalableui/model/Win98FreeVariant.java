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
package com.android.car.scalableui.model;

import android.graphics.Insets;
import android.graphics.Rect;

import androidx.annotation.NonNull;

/**
 * A {@link Variant} whose geometry can be rewritten after construction, and the pod's single
 * seam for rewriting geometry on any other variant.
 *
 * <p>{@code Variant}'s geometry setters are {@code protected} and its copying constructor is
 * package-private (the same seam {@link KeyFrameVariant} uses). This class lives in the
 * library's package so it can call both, without a framework patch. Two instances (A/B) are
 * ping-ponged on each drop because {@code StateManager} skips a transition whose from and to
 * are the same object.
 *
 * <p>The variant, not the panel's own fields, is what {@code TaskPanel.updateInternal} reads
 * when it rebuilds a task surface, so a window that has been moved on screen is only really
 * moved once its variant says so. See {@link #assignGeometry}.
 */
public final class Win98FreeVariant extends Variant {

    public static final String ID_A = "win98_free_a";
    public static final String ID_B = "win98_free_b";

    public Win98FreeVariant(@NonNull String variantId, @NonNull Variant baseVariant) {
        super(variantId, baseVariant, variantId);
    }

    /**
     * Rewrite the whole geometry tuple on any variant.
     *
     * <p>{@code TaskPanel.updateInternal} rebuilds the task surface from the variant on every
     * transition: position, visibility and layer together. Stamping only one of them leaves the
     * others at their XML values, and the next unrelated transition drags the task back out from
     * under the chrome. Every write that means "the window is here now" goes through here, so
     * the tuple can never be half-updated.
     *
     * <p>Safe bounds follow the bounds: {@code applyState} writes both and an empty safe rect
     * throws on TaskPanel. Insets are the caller's combined XML + chrome padding, not cleared.
     *
     * <p>{@code Variant.setBounds} stores the reference it is given, so the rect is copied.
     */
    public static void assignGeometry(@NonNull Variant variant, @NonNull Rect bounds, int layer,
            @NonNull Insets insets) {
        assignGeometry(variant, bounds, layer, insets, /* isVisible= */ true);
    }

    /** {@link #assignGeometry(Variant, Rect, int, Insets)} with an explicit visibility. */
    public static void assignGeometry(@NonNull Variant variant, @NonNull Rect bounds, int layer,
            @NonNull Insets insets, boolean isVisible) {
        variant.setBounds(new Rect(bounds));
        variant.setSafeBounds(new Rect(bounds));
        variant.setLayer(layer);
        variant.setInsets(insets);
        variant.setVisibility(isVisible);
    }

    /**
     * Rewrite only the layer. For a variant the panel is transitioning TO, whose bounds are the
     * point of the transition and must not be touched.
     */
    public static void assignLayer(@NonNull Variant variant, int layer) {
        variant.setLayer(layer);
    }

    /** Rewrite only the insets, when bounds and layer are already current. */
    public static void assignInsets(@NonNull Variant variant, @NonNull Insets insets) {
        variant.setInsets(insets);
    }
}
