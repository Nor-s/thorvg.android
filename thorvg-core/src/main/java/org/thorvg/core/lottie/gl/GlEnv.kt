/*
 * Copyright (c) 2026 ThorVG project. All rights reserved.

 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:

 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.

 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.thorvg.core.lottie.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.util.Log
import androidx.annotation.RestrictTo

/**
 * Process-wide EGL environment shared by every [SharedGlThread] worker.
 *
 * Holds the [EGLDisplay], a chosen [EGLConfig], and a long-lived "root" [EGLContext]
 * that worker contexts pass as their `share_context`. Sharing lets workers see each
 * other's textures, shaders and buffer objects without copies — critical when multiple
 * worker threads render in parallel.
 *
 * Initialization is lazy and idempotent; the first worker that comes up triggers it.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
internal object GlEnv {
    private const val TAG = "ThorVG-GlEnv"
    private const val EGL_OPENGL_ES3_BIT_KHR = 0x00000040

    var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private set
    var config: EGLConfig? = null
        private set

    /** Root context — used only as the `share_context` source for worker contexts. */
    var rootContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private set

    val isReady: Boolean
        get() = display != EGL14.EGL_NO_DISPLAY &&
            config != null &&
            rootContext != EGL14.EGL_NO_CONTEXT

    @Synchronized
    fun ensureInitialized(): Boolean {
        if (isReady) return true

        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            Log.w(TAG, "eglGetDisplay failed")
            return false
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            Log.w(TAG, "eglInitialize failed")
            display = EGL14.EGL_NO_DISPLAY
            return false
        }

        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_NONE
        )
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) ||
            count[0] == 0 ||
            configs[0] == null
        ) {
            Log.w(TAG, "eglChooseConfig failed")
            return false
        }
        config = configs[0]

        rootContext = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
            0
        )
        if (rootContext == EGL14.EGL_NO_CONTEXT) {
            Log.w(TAG, "eglCreateContext (root) failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
            return false
        }

        return true
    }
}
