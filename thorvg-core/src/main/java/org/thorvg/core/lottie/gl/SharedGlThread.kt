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

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer
import androidx.annotation.RestrictTo
import java.util.concurrent.atomic.AtomicInteger

/**
 * A single GL worker — one [HandlerThread] + one [EGLContext] (shared with [GlEnv.rootContext])
 * + its own [Choreographer]. Multiple workers are created by [acquire] to form a pool so that
 * GL render work for different Lottie instances can run in parallel on multi-core devices
 * without giving up GL resource sharing.
 *
 * Workers themselves still serialize their own clients (each worker has one EGL context that
 * can only be current on its thread), so the win is across-worker parallelism: with N workers
 * and N+ clients, the effective single-vsync render budget multiplies by N.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class SharedGlThread private constructor(name: String) {
    private val thread = HandlerThread(name).also { it.start() }
    val handler = Handler(thread.looper)

    val eglDisplay: EGLDisplay
        get() = GlEnv.display

    var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private set

    private var eglPbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private val clients = mutableListOf<RenderClient>()
    private var choreographer: Choreographer? = null
    private var choreographerRunning = false

    /** Tracks which client last rendered, so clients can skip redundant setGlTarget calls. */
    var lastRenderedClient: RenderClient? = null
        private set

    val clientCount: Int
        get() = clients.size

    interface RenderClient {
        /**
         * Whether this client should keep being polled by the shared thread.
         * Stays true across vsyncs the client chooses to skip; only flips false
         * when the client is fully idle (paused, no surface, etc.).
         */
        fun isActive(): Boolean
        /** Whether this client wants to render in the current choreographer frame. */
        fun shouldRender(): Boolean
        /** Called on the shared GL thread during each choreographer frame. */
        fun onRenderFrame(): Boolean
    }

    init {
        handler.post {
            initEgl()
            choreographer = Choreographer.getInstance()
        }
    }

    fun createWindowSurface(surface: SurfaceTexture): EGLSurface {
        val display = GlEnv.display
        val config = GlEnv.config ?: return EGL14.EGL_NO_SURFACE
        if (display == EGL14.EGL_NO_DISPLAY) return EGL14.EGL_NO_SURFACE
        val eglSurface = EGL14.eglCreateWindowSurface(
            display,
            config,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
        return eglSurface
    }

    fun destroyWindowSurface(surface: EGLSurface) {
        val display = GlEnv.display
        if (display != EGL14.EGL_NO_DISPLAY && surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, surface)
        }
    }

    fun makeCurrent(surface: EGLSurface): Boolean {
        val display = GlEnv.display
        if (display == EGL14.EGL_NO_DISPLAY ||
            eglContext == EGL14.EGL_NO_CONTEXT ||
            surface == EGL14.EGL_NO_SURFACE
        ) {
            return false
        }
        return EGL14.eglMakeCurrent(display, surface, surface, eglContext)
    }

    /** Make the internal PBuffer surface current. For clients that render to FBOs only. */
    fun makeDefaultCurrent(): Boolean = makeCurrent(eglPbuffer)

    fun swapBuffers(surface: EGLSurface): Boolean {
        val display = GlEnv.display
        if (display == EGL14.EGL_NO_DISPLAY || surface == EGL14.EGL_NO_SURFACE) return false
        return EGL14.eglSwapBuffers(display, surface)
    }

    fun register(client: RenderClient) {
        handler.post {
            if (!clients.contains(client)) {
                clients.add(client)
            }
            startChoreographerIfNeeded()
        }
    }

    /** Remove client from the render list. Must be called on the GL thread. */
    fun unregisterOnGlThread(client: RenderClient) {
        clients.remove(client)
        if (lastRenderedClient == client) {
            lastRenderedClient = null
        }
        if (clients.isEmpty()) {
            stopChoreographer()
            makeDefaultCurrent()
        }
    }

    /** Kick the choreographer if there are active clients. */
    fun requestRender() {
        handler.post { startChoreographerIfNeeded() }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!choreographerRunning) return

            for (client in clients.toList()) {
                if (client.shouldRender()) {
                    if (client.onRenderFrame()) {
                        lastRenderedClient = client
                    }
                }
            }

            if (clients.any { it.isActive() }) {
                choreographer?.postFrameCallback(this)
            } else {
                choreographerRunning = false
            }
        }
    }

    private fun startChoreographerIfNeeded() {
        if (!choreographerRunning && clients.any { it.isActive() }) {
            choreographerRunning = true
            choreographer?.postFrameCallback(frameCallback)
        }
    }

    private fun stopChoreographer() {
        choreographerRunning = false
        choreographer?.removeFrameCallback(frameCallback)
    }

    private fun initEgl() {
        if (!GlEnv.ensureInitialized()) return

        val display = GlEnv.display
        val config = GlEnv.config ?: return
        val shareContext = GlEnv.rootContext
        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)

        eglContext = EGL14.eglCreateContext(display, config, shareContext, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            // Some drivers reject share contexts in obscure modes — try standalone.
            Log.w(TAG, "shared eglCreateContext failed: 0x${Integer.toHexString(EGL14.eglGetError())}; retrying without share")
            eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                Log.w(TAG, "eglCreateContext failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
                return
            }
        }

        eglPbuffer = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0
        )
        if (eglPbuffer == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "eglCreatePbufferSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
            return
        }
        makeDefaultCurrent()
    }

    companion object {
        private const val TAG = "ThorVGSharedGL"

        /**
         * Number of GL worker threads to create. Defaults to `min(4, cores)` to balance
         * across-worker parallelism with thread/EGL-context overhead. Must be set before
         * the first [acquire] call to take effect; later mutations are ignored.
         */
        @Volatile
        var poolSize: Int = defaultPoolSize()
            set(value) {
                require(value >= 1) { "poolSize must be >= 1" }
                field = value
            }

        private fun defaultPoolSize(): Int =
            Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        private val workers: Array<SharedGlThread> by lazy {
            Array(poolSize) { SharedGlThread("ThorVG-Lottie-GL-$it") }
        }

        private val acquireCounter = AtomicInteger(0)

        /**
         * Returns one of the pool workers using round-robin distribution. Each [GlRenderer]
         * should call this once on creation and reuse the result for its lifetime.
         */
        fun acquire(): SharedGlThread {
            val pool = workers
            val idx = (acquireCounter.getAndIncrement() and Int.MAX_VALUE) % pool.size
            return pool[idx]
        }
    }
}
