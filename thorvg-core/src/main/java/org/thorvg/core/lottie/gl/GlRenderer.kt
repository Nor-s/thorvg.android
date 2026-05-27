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
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RestrictTo
import org.thorvg.core.lottie.LottieConstants
import org.thorvg.core.lottie.LottieGlComposition
import org.thorvg.core.lottie.LottieGlRenderState
import org.thorvg.core.lottie.LottieRenderTarget
import java.util.concurrent.CountDownLatch
import kotlin.math.min

/**
 * Drives a Lottie GL render loop on the shared GL thread.
 *
 * The hosting view/composable wires the render surface, composition factory, and
 * playback callbacks; this class handles EGL surface binding, frame pacing,
 * repeat/end signaling, and target rebinding.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class GlRenderer(
    private val onAnimationStart: () -> Unit = {},
    private val onAnimationEnd: () -> Unit = {},
    private val onAnimationRepeat: () -> Unit = {},
    private val onRenderFailure: () -> Unit = {}
) : SharedGlThread.RenderClient {
    private val sharedGl = SharedGlThread.acquire()
    private val handler = sharedGl.handler
    private val mainHandler = Handler(Looper.getMainLooper())

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

    /** Last frame index that was actually rendered to screen. Exposed for UI binding. */
    @Volatile
    var currentFrame = 0
        private set

    /** Frame index that the next [renderCurrentFrame] will draw. Updated by [advanceFrame]. */
    private var nextFrame = 0

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
            // Disable vsync: eglSwapBuffers becomes non-blocking. The Choreographer
            // still rate-limits the loop trigger, but each swap won't wait for the
            // next refresh, freeing the GL thread to start the next frame immediately.
            if (sharedGl.makeCurrent(eglSurface)) {
                EGL14.eglSwapInterval(sharedGl.eglDisplay, 0)
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
            nextFrame = renderState.firstFrame

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
            val range = renderState.firstFrame..lastFrame()
            currentFrame = currentFrame.coerceIn(range)
            nextFrame = nextFrame.coerceIn(range)
            dirtyFrame = true
            sharedGl.requestRender()
        }
    }

    fun start() {
        post { startInternal() }
    }

    fun stop() {
        post {
            isRunning = false
            started = false
            ended = false
            repeated = 0
            currentFrame = renderState.firstFrame
            nextFrame = renderState.firstFrame
            dirtyFrame = true
            lastDrawTimeMs = 0L
            sharedGl.requestRender()
        }
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

    override fun isActive(): Boolean {
        if (!surfaceReady || failed || renderState.composition == null) return false
        return dirtyFrame || isRunning
    }

    override fun shouldRender(): Boolean {
        if (!isActive()) return false
        if (dirtyFrame) return true
        if (!isRunning) return false
        return hasFrameIntervalElapsed()
    }

    override fun onRenderFrame(): Boolean {
        return drawFrame()
    }

    private fun hasFrameIntervalElapsed(): Boolean {
        val interval = renderState.frameInterval
        if (interval <= 0L) return false
        if (lastDrawTimeMs <= 0L) return true
        return SystemClock.uptimeMillis() - lastDrawTimeMs >= interval
    }

    private fun startInternal() {
        isRunning = true
        started = false
        ended = false
        repeated = 0
        currentFrame = renderState.firstFrame
        nextFrame = renderState.firstFrame
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

        // FBO id 0 = the EGL window surface's default framebuffer; ThorVG draws
        // straight to it, no intermediate FBO + blit step.
        val target = LottieRenderTarget.Gl(
            display = sharedGl.eglDisplay.nativeHandle,
            surface = eglSurface.nativeHandle,
            context = sharedGl.eglContext.nativeHandle,
            framebufferId = 0
        )
        renderState.setSize(width, height)
        if (!renderState.target(target)) return false
        targetDirty = false
        return true
    }

    private fun drawFrame(): Boolean {
        if (!surfaceReady || width <= 0 || height <= 0) return false
        if (renderState.composition == null) return false

        val timing = currentFrameTiming()
        if (!timing.shouldDraw) return false
        if (!prepareFrameTarget()) return false
        if (!renderCurrentFrame()) return false

        finishFrame(timing)
        return true
    }

    private fun currentFrameTiming(): FrameTiming {
        val nowMs = SystemClock.uptimeMillis()
        val interval = renderState.frameInterval
        val elapsedMs = if (lastDrawTimeMs > 0L) nowMs - lastDrawTimeMs else interval
        val steps = if (interval > 0L && elapsedMs >= interval) (elapsedMs / interval).toInt() else 0
        return FrameTiming(
            nowMs = nowMs,
            interval = interval,
            steps = steps,
            shouldDraw = dirtyFrame || (isRunning && steps > 0)
        )
    }

    private fun prepareFrameTarget(): Boolean {
        if (!sharedGl.makeCurrent(eglSurface)) {
            notifyRenderFailure()
            return false
        }
        if (!needsTargetBind()) return true
        if (bindTarget(ensureCurrent = false)) return true

        notifyRenderFailure()
        return false
    }

    private fun needsTargetBind(): Boolean {
        return targetDirty || sharedGl.lastRenderedClient != this
    }

    private fun renderCurrentFrame(): Boolean {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (!renderState.renderFrame(nextFrame)) {
            notifyRenderFailure()
            return false
        }
        if (!sharedGl.swapBuffers(eglSurface)) {
            notifyRenderFailure()
            return false
        }
        currentFrame = nextFrame
        return true
    }

    private fun finishFrame(timing: FrameTiming) {
        dirtyFrame = false

        if (!isRunning) {
            lastDrawTimeMs = timing.nowMs
            return
        }
        if (!started) {
            started = true
            mainHandler.post { onAnimationStart() }
        }

        // Preserve leftover fractional frame time to avoid playback drift.
        lastDrawTimeMs = if (lastDrawTimeMs > 0L && timing.steps > 0) {
            lastDrawTimeMs + timing.steps * timing.interval
        } else {
            timing.nowMs
        }
        advanceFrame(timing.steps)
    }

    private fun advanceFrame(steps: Int) {
        if (steps <= 0 || ended) return

        val first = renderState.firstFrame
        val last = lastFrame()
        val frameCount = last - first + 1
        if (frameCount <= 0) return

        val movingForward = renderState.framesPerUpdate > 0
        val terminal = if (movingForward) last else first
        val isInfinite = renderState.repeatCount == LottieConstants.INFINITE

        // SW-parity end: the terminal frame of the final play has just been rendered.
        if (!isInfinite &&
            repeated >= renderState.repeatCount &&
            currentFrame == terminal
        ) {
            ended = true
            isRunning = false
            mainHandler.post { onAnimationEnd() }
            return
        }

        val offset = if (movingForward) currentFrame - first else last - currentFrame
        val advanced = offset.toLong() + steps.toLong()
        val rawWraps = (advanced / frameCount).toInt()

        val wraps = if (isInfinite) {
            rawWraps
        } else {
            min(rawWraps, renderState.repeatCount - repeated)
        }
        // If steps would carry us past the final play, park at the terminal so the
        // next render shows it and the following advanceFrame fires onAnimationEnd.
        val overshoot = !isInfinite && (repeated + rawWraps) > renderState.repeatCount

        nextFrame = if (overshoot) {
            terminal
        } else {
            val nextOffset = (advanced % frameCount).toInt()
            if (movingForward) first + nextOffset else last - nextOffset
        }

        if (wraps > 0) {
            repeated += wraps
            mainHandler.post { onAnimationRepeat() }
        }
    }

    private fun lastFrame(): Int {
        return renderState.resolvedLastFrame()
    }

    private data class FrameTiming(
        val nowMs: Long,
        val interval: Long,
        val steps: Int,
        val shouldDraw: Boolean
    )

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
        mainHandler.post { onRenderFailure() }
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
