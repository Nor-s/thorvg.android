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

package org.thorvg.view.lottie.gl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.thorvg.core.lottie.LottieConstants
import org.thorvg.core.lottie.LottieGlComposition
import org.thorvg.core.lottie.LottieGlRenderState
import org.thorvg.core.lottie.LottieRenderTarget
import org.thorvg.view.lottie.LottieListener
import java.util.concurrent.CountDownLatch
import kotlin.math.min

internal class GlRenderer(
    private val listenerProvider: () -> LottieListener?,
    private val renderFailureListener: () -> Unit
) : SharedGlThread.RenderClient {
    private val sharedGl = SharedGlThread.instance
    private val handler = sharedGl.handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderTarget = GlRenderTarget()

    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var compositionFactory: (() -> LottieGlComposition?)? = null
    private val renderState = LottieGlRenderState()
    private var width = 0
    private var height = 0
    private var repeated = 0
    private var started = false
    private var ended = false
    private var failed = false
    private var surfaceReady = false
    private var dirtyFrame = false
    private var targetDirty = true
    private var lastDrawTimeMs = 0L

    @Volatile
    var isRunning = false
        private set

    @Volatile
    var currentFrame = 0
        private set

    fun setSurface(surface: SurfaceTexture, width: Int, height: Int) {
        post {
            val shouldStart = isRunning || renderState.autoPlay
            clearSurfaceOnGlThread(releaseComposition = false)
            failed = false

            eglSurface = sharedGl.createWindowSurface(surface)
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                notifyRenderFailure()
                return@post
            }

            this.width = width
            this.height = height
            surfaceReady = true
            targetDirty = true

            if (!ensureComposition()) {
                notifyRenderFailure()
                return@post
            }
            if (!bindTarget()) {
                notifyRenderFailure()
                return@post
            }

            sharedGl.register(this)
            if (shouldStart) {
                startInternal()
            } else {
                dirtyFrame = true
                sharedGl.requestRender()
            }
        }
    }

    fun resize(width: Int, height: Int) {
        post {
            if (!surfaceReady) return@post
            if (this.width == width && this.height == height) return@post

            this.width = width
            this.height = height
            targetDirty = true
            if (!bindTarget()) {
                notifyRenderFailure()
                return@post
            }
            dirtyFrame = true
            sharedGl.requestRender()
        }
    }

    fun clearSurface() {
        postAndWait {
            clearSurfaceOnGlThread(releaseComposition = true)
        }
    }

    fun setCompositionFactory(
        compositionFactory: (() -> LottieGlComposition?)?,
        state: LottieGlRenderState
    ) {
        post {
            val shouldStart = isRunning || state.autoPlay
            failed = false
            renderState.release()
            this.compositionFactory = compositionFactory
            state.copyPlaybackTo(renderState)
            repeated = 0
            started = false
            ended = false
            dirtyFrame = false
            targetDirty = true
            lastDrawTimeMs = 0L
            currentFrame = renderState.firstFrame

            if (!ensureComposition()) {
                notifyRenderFailure()
                return@post
            }
            if (!bindTarget()) {
                notifyRenderFailure()
                return@post
            }
            if (renderState.composition != null && shouldStart) {
                startInternal()
            } else {
                isRunning = false
                dirtyFrame = true
                sharedGl.requestRender()
            }
        }
    }

    fun setConfig(state: LottieGlRenderState) {
        post {
            state.copyPlaybackTo(renderState)
            currentFrame = currentFrame.coerceIn(renderState.firstFrame, lastFrame())
            dirtyFrame = true
            sharedGl.requestRender()
        }
    }

    fun start() {
        post { startInternal() }
    }

    fun stop() {
        pause();
    }

    fun pause() {
        post {
            isRunning = false
            lastDrawTimeMs = 0L
        }
    }

    fun resume() {
        post {
            isRunning = true
            dirtyFrame = true
            sharedGl.requestRender()
        }
    }

    fun release() {
        postAndWait {
            isRunning = false
            clearSurfaceOnGlThread(releaseComposition = true)
        }
        mainHandler.removeCallbacksAndMessages(null)
    }

    override fun shouldRender(): Boolean {
        if (!surfaceReady || failed || renderState.composition == null) return false
        return dirtyFrame || isRunning
    }

    override fun onRenderFrame(): Boolean {
        return drawFrame()
    }

    private fun startInternal() {
        isRunning = true
        started = false
        ended = false
        repeated = 0
        currentFrame = renderState.firstFrame
        dirtyFrame = true
        lastDrawTimeMs = 0L
        sharedGl.requestRender()
    }

    private fun ensureComposition(): Boolean {
        if (renderState.composition != null || compositionFactory == null) return true
        if (!surfaceReady) return true
        if (!sharedGl.makeCurrent(eglSurface)) return false

        val composition = compositionFactory?.invoke()
        renderState.composition = composition
        return composition?.isValid() == true
    }

    private fun bindTarget(ensureCurrent: Boolean = true): Boolean {
        if (renderState.composition == null) return true
        if (!surfaceReady || width <= 0 || height <= 0) return true
        if (ensureCurrent && !sharedGl.makeCurrent(eglSurface)) return false
        if (!renderTarget.ensure(width, height)) return false

        val target = LottieRenderTarget.Gl(
            display = sharedGl.eglDisplay.nativeHandle,
            surface = eglSurface.nativeHandle,
            context = sharedGl.eglContext.nativeHandle,
            framebufferId = renderTarget.framebufferId
        )
        renderState.setSize(width, height)
        if (!renderState.target(target)) return false
        targetDirty = false
        return true
    }

    private fun drawFrame(): Boolean {
        if (!surfaceReady || width <= 0 || height <= 0) return false
        if (renderState.composition == null) return false

        // Keep one timing value for draw throttling and frame catch-up.
        val nowMs = SystemClock.uptimeMillis()
        val interval = renderState.frameInterval
        val elapsedMs = if (lastDrawTimeMs > 0L) nowMs - lastDrawTimeMs else interval
        val steps = if (interval > 0L && elapsedMs >= interval) (elapsedMs / interval).toInt() else 0
        val shouldDraw = dirtyFrame || (isRunning && steps > 0)

        // Skip unless we have a forced redraw or there's a frame to advance.
        if (!shouldDraw) return false

        if (!sharedGl.makeCurrent(eglSurface)) {
            notifyRenderFailure()
            return false
        }
        if (targetDirty ||
            renderTarget.framebufferId == 0 ||
            sharedGl.lastRenderedClient != this
        ) {
            if (!bindTarget(ensureCurrent = false)) {
                notifyRenderFailure()
                return false
            }
        }

        renderTarget.bind()
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (!renderState.renderFrame(currentFrame)) {
            notifyRenderFailure()
            return false
        }
        renderTarget.blitToDefaultFramebuffer(width, height)
        if (!sharedGl.swapBuffers(eglSurface)) {
            notifyRenderFailure()
            return false
        }

        dirtyFrame = false

        if (!isRunning) {
            lastDrawTimeMs = nowMs
            return true
        }
        if (!started) {
            started = true
            mainHandler.post { listenerProvider()?.onAnimationStart() }
        }

        // Preserve leftover fractional frame time to avoid playback drift.
        lastDrawTimeMs = if (lastDrawTimeMs > 0L && steps > 0) {
            lastDrawTimeMs + steps * interval
        } else {
            nowMs
        }
        advanceFrame(steps)
        return true
    }

    private fun advanceFrame(steps: Int) {
        if (steps <= 0) return
        if (reachedRepeatLimit()) {
            if (!ended) {
                ended = true
                isRunning = false
                mainHandler.post { listenerProvider()?.onAnimationEnd() }
            }
            return
        }

        val first = renderState.firstFrame
        val last = lastFrame()
        val frameCount = last - first + 1
        val movingForward = renderState.framesPerUpdate > 0
        val offset = if (movingForward) currentFrame - first else last - currentFrame
        val advanced = offset.toLong() + steps.toLong()
        val wraps = (advanced / frameCount).toInt()
        val resets = if (renderState.repeatCount == LottieConstants.INFINITE) {
            wraps
        } else {
            min(wraps, renderState.repeatCount - repeated)
        }

        currentFrame = if (reachedRepeatLimit(resets)) {
            if (movingForward) first else last
        } else {
            val nextOffset = (advanced % frameCount).toInt()
            if (movingForward) first + nextOffset else last - nextOffset
        }

        if (resets > 0) {
            repeated += resets
            mainHandler.post { listenerProvider()?.onAnimationRepeat() }
        }
    }

    private fun reachedRepeatLimit(pendingResets: Int = 0): Boolean {
        return renderState.repeatCount != LottieConstants.INFINITE &&
            repeated + pendingResets >= renderState.repeatCount
    }

    private fun lastFrame(): Int {
        return renderState.resolvedLastFrame()
    }

    private fun clearSurfaceOnGlThread(releaseComposition: Boolean) {
        sharedGl.unregisterOnGlThread(this)
        surfaceReady = false
        isRunning = false
        dirtyFrame = false
        targetDirty = true

        // Ensure glDelete* has a current context.
        if (!sharedGl.makeCurrent(eglSurface)) {
            sharedGl.makeDefaultCurrent()
        }
        renderTarget.release()
        if (releaseComposition) {
            renderState.release()
        }
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            sharedGl.makeDefaultCurrent()
            sharedGl.destroyWindowSurface(eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun notifyRenderFailure() {
        if (failed) return

        failed = true
        isRunning = false
        dirtyFrame = false
        sharedGl.unregisterOnGlThread(this)
        mainHandler.post { renderFailureListener() }
    }

    private fun post(action: () -> Unit) {
        handler.post(action)
    }

    private fun postAndWait(action: () -> Unit) {
        if (Looper.myLooper() == handler.looper) {
            action()
            return
        }

        val latch = CountDownLatch(1)
        handler.post {
            try {
                action()
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    }
}
