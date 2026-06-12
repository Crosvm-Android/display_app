/*
 * Copyright 2024 The Android Open Source Project
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

package android.crosvm;

import android.crosvm.DisplayConfig;
import android.os.ParcelFileDescriptor;
import android.view.MotionEvent;
import android.view.Surface;

/**
 * Service to provide Crosvm with an Android Surface for showing a guest's
 * display, and forward input events to the guest.
 */
interface ICrosvmAndroidDisplayService {
    // ── Display surface management ──────────────────────────────────────────
    void setSurface(in Surface surface, boolean forCursor);
    void setCursorStream(in ParcelFileDescriptor stream);
    void removeSurface(boolean forCursor);
    void saveFrameForSurface(boolean forCursor);
    void drawSavedFrameForSurface(boolean forCursor);

    // ── Display configuration (read-only from guest) ────────────────────────
    /**
     * Returns the guest display configuration.
     * @return DisplayConfig with width/height/dpi/refreshRate, or null if not available yet.
     */
    DisplayConfig getDisplayConfig();

    // ── Input forwarding ────────────────────────────────────────────────────
    /**
     * Sends a touch event to the guest.
     * @param event The MotionEvent from Android view system
     * @param scaleX Scale factor to convert Android X coordinate to guest X
     * @param scaleY Scale factor to convert Android Y coordinate to guest Y
     */
    void sendTouchEvent(in MotionEvent event, float scaleX, float scaleY);

    /**
     * Sends a mouse event to the guest.
     * @param event The MotionEvent
     * @param isRelative True for relative (pointer capture) mode, false for absolute
     */
    void sendMouseEvent(in MotionEvent event, boolean isRelative);

    /**
     * Sends a key event to the guest.
     * @param scanCode Linux evdev scan code
     * @param pressed True for key down, false for key up
     */
    void sendKeyEvent(int scanCode, boolean pressed);

    /**
     * Sends tablet/desktop mode state to the guest.
     * @param isTablet True for tablet mode, false for desktop mode
     */
    void sendTabletModeEvent(boolean isTablet);
}
