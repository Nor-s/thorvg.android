/*
 * Copyright (c) 2025 - 2026 ThorVG project. All rights reserved.

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

package org.thorvg.view.lottie.sw

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RawRes
import androidx.annotation.FloatRange
import org.thorvg.core.lottie.LottieConstants
import org.thorvg.core.lottie.LottieSwComposition
import org.thorvg.core.lottie.LottieSwRenderState
import org.thorvg.core.lottie.LottieRepeatMode
import org.thorvg.view.ThorVGDrawable
import org.thorvg.view.lottie.LottieListener

/**
 * Drawable adapter that renders a ThorVG Lottie composition into an Android [Canvas].
 */
class LottieDrawable internal constructor() : ThorVGDrawable(), Animatable {
    private var lottieState: LottieDrawableState = LottieDrawableState()
    private var listener: LottieListener? = null

    private var isRunning = false
    private var isEnded = false
    private var isStarted = false
    private var repeated = 0

    /** Integer frame index "anchor" — the start of the current interval. */
    private var frame = 0
    /** Frame index that was actually drawn last. Exposed via [currentFrame]. */
    private var displayedFrame = 0
    /** Wall clock time when the current integer-frame interval started. */
    private var anchorMs = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val nextFrameRunnable = Runnable { invalidateSelf() }
    private val tmpPaint = Paint()

    private var mutated = false

    internal constructor(state: LottieDrawableState) : this() {
        lottieState = state
    }

    /**
     * Releases the underlying composition and bitmap buffer.
     *
     * Call this when the drawable is no longer needed.
     */
    override fun release() {
        lottieState.releaseComposition()
    }

    override fun mutate(): Drawable {
        if (!mutated && super.mutate() === this) {
            lottieState = LottieDrawableState(lottieState)
            mutated = true
        }
        return this
    }

    override fun draw(canvas: Canvas) {
        if (!(lottieState.valid() && isRunning)) return

        if (!isStarted) {
            isStarted = true
            dispatchAnimationStart()
        }

        val nowMs = SystemClock.uptimeMillis()
        if (anchorMs == 0L) anchorMs = nowMs

        val interval = lottieState.frameInterval
        val direction = lottieState.framesPerUpdate
        val elapsed = (nowMs - anchorMs).coerceAtLeast(0L)
        val fractionalFrames = if (interval > 0L) elapsed.toFloat() / interval else 0f
        val actualFrame = frame.toFloat() + fractionalFrames * direction

        getFrame(actualFrame)?.let { bitmap ->
            canvas.drawBitmap(bitmap, 0f, 0f, tmpPaint)
        }
        displayedFrame = frame

        if (lottieState.repeatCount != INFINITE && repeated == lottieState.repeatCount) {
            if (!isEnded) {
                isEnded = true
                dispatchAnimationEnd()
            }
            return
        }

        // Advance the integer anchor when whole intervals have elapsed.
        val steps = if (interval > 0L) (elapsed / interval).toInt() else 0
        if (steps > 0) {
            anchorMs += steps * interval
            val cycleSize = (lottieState.lastFrame - lottieState.firstFrame + 1).coerceAtLeast(1)
            val offset = if (direction > 0) frame - lottieState.firstFrame
                         else lottieState.lastFrame - frame
            val advanced = offset.toLong() + steps.toLong()
            val wraps = (advanced / cycleSize).toInt()
            val nextOffset = (advanced % cycleSize).toInt()
            frame = if (direction > 0) lottieState.firstFrame + nextOffset
                    else lottieState.lastFrame - nextOffset
            if (wraps > 0) {
                repeated += wraps
                dispatchAnimationRepeat()
            }
        }

        // Schedule the next vsync redraw (no throttle — sub-frame interpolation).
        handler.post(nextFrameRunnable)
    }

    /**
     * Returns the bitmap containing the requested frame.
     *
     * The returned bitmap is owned by this drawable and may be reused on the next frame render.
     * Accepts fractional values for sub-frame interpolation.
     */
    fun getFrame(frame: Float): Bitmap? {
        return lottieState.renderFrame(frame)
    }

    override fun setAlpha(alpha: Int) = Unit

    @Deprecated("Deprecated in Drawable")
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Drawable")
    override fun getOpacity(): Int {
        return PixelFormat.TRANSLUCENT
    }

    override fun getIntrinsicWidth(): Int {
        return lottieState.width
    }

    override fun getIntrinsicHeight(): Int {
        return lottieState.height
    }

    /**
     * Number of times the animation repeats after its first pass.
     *
     * Use [INFINITE] to loop forever.
     */
    var repeatCount: Int
        get() = lottieState.repeatCount
        set(count) {
            lottieState.repeatCount = count
            repeated = 0
        }

    /**
     * Sets the repeat behavior to either [RESTART] or [REVERSE].
     */
    fun setRepeatMode(@LottieRepeatMode mode: Int) {
        lottieState.repeatMode = mode
    }

    /**
     * Current repeat behavior.
     */
    @get:LottieRepeatMode
    val repeatMode: Int
        get() = lottieState.repeatMode

    /**
     * Sets the first frame that playback is allowed to render.
     */
    fun setFirstFrame(frame: Int) {
        lottieState.firstFrame = frame
    }

    /**
     * First frame used for playback.
     */
    val firstFrame: Int
        get() = lottieState.firstFrame

    /**
     * Sets the last frame that playback is allowed to render.
     */
    fun setLastFrame(frame: Int) {
        lottieState.lastFrame = frame
    }

    /**
     * Last frame used for playback.
     */
    val lastFrame: Int
        get() = lottieState.lastFrame

    /**
     * Whether playback should start automatically after the drawable is attached by its host.
     */
    val isAutoPlay: Boolean
        get() = lottieState.autoPlay

    /**
     * Total animation duration in milliseconds.
     */
    val duration: Long
        get() = if (lottieState.valid()) lottieState.composition?.duration ?: 0L else 0L

    /**
     * Playback speed multiplier.
     *
     * Values larger than `1f` play faster. `0f` pauses frame progression.
     */
    var speed: Float
        @FloatRange(from = 0.0)
        get() = lottieState.speed
        set(@FloatRange(from = 0.0) value) {
            lottieState.speed = value
        }

    /**
     * Resizes the composition buffer to the given dimensions in pixels.
     */
    override fun setSize(width: Int, height: Int) {
        require(width > 0) { "LottieDrawable requires width > 0" }
        require(height > 0) { "LottieDrawable requires height > 0" }
        lottieState.setCompositionSize(width, height)
    }

    override fun isRunning(): Boolean {
        return isRunning
    }

    /**
     * Starts playback from [firstFrame].
     */
    override fun start() {
        isRunning = true
        isEnded = false
        isStarted = false
        repeated = 0
        frame = lottieState.firstFrame
        anchorMs = 0L
        invalidateSelf()
    }

    /**
     * Stops playback and removes any scheduled invalidation callbacks.
     */
    override fun stop() {
        isRunning = false
        anchorMs = 0L
        handler.removeCallbacks(nextFrameRunnable)
    }

    /**
     * Pauses playback without resetting the current frame.
     */
    fun pause() {
        isRunning = false
        anchorMs = 0L
        handler.removeCallbacks(nextFrameRunnable)
    }

    /**
     * Resumes playback from the current frame.
     */
    fun resume() {
        isRunning = true
        anchorMs = 0L
        invalidateSelf()
    }

    /**
     * Frame index currently visible on screen (the last frame actually drawn).
     */
    val currentFrame: Int
        get() = displayedFrame

    /**
     * Registers a listener for playback lifecycle callbacks.
     */
    fun setAnimationListener(listener: LottieListener?) {
        this.listener = listener
    }

    internal fun dispatchAnimationStart() {
        listener?.onAnimationStart()
    }

    internal fun dispatchAnimationRepeat() {
        listener?.onAnimationRepeat()
    }

    internal fun dispatchAnimationEnd() {
        listener?.onAnimationEnd()
    }

    internal class LottieDrawableState() : ConstantState() {
        private val renderState = LottieSwRenderState()

        var composition: LottieSwComposition?
            get() = renderState.composition
            set(value) {
                renderState.composition = value
            }

        var baseWidth: Float
            get() = renderState.baseWidth
            set(value) {
                renderState.baseWidth = value
            }

        var baseHeight: Float
            get() = renderState.baseHeight
            set(value) {
                renderState.baseHeight = value
            }

        var width: Int
            get() = renderState.width
            set(value) {
                renderState.width = value
            }

        var height: Int
            get() = renderState.height
            set(value) {
                renderState.height = value
            }

        var repeatCount: Int
            get() = renderState.repeatCount
            set(value) {
                renderState.repeatCount = value
            }

        var repeatMode: Int
            get() = renderState.repeatMode
            set(value) {
                renderState.repeatMode = value
            }

        var framesPerUpdate: Int
            get() = renderState.framesPerUpdate
            set(value) {
                renderState.framesPerUpdate = value
            }

        var autoPlay: Boolean
            get() = renderState.autoPlay
            set(value) {
                renderState.autoPlay = value
            }

        var speed: Float
            get() = renderState.speed
            set(value) {
                renderState.speed = value
            }

        var firstFrame: Int
            get() = renderState.firstFrame
            set(value) {
                renderState.firstFrame = value
            }

        var lastFrame: Int
            get() = renderState.lastFrame
            set(value) {
                renderState.lastFrame = value
            }

        var frameInterval: Long
            get() = renderState.frameInterval
            set(value) {
                renderState.frameInterval = value
            }

        val resolvedLastFrame: Int
            get() = renderState.resolvedLastFrame()

        constructor(copy: LottieDrawableState?) : this() {
            copy ?: return
            composition = copy.composition?.copy()
            baseWidth = copy.baseWidth
            baseHeight = copy.baseHeight
            width = copy.width
            height = copy.height
            repeatCount = copy.repeatCount
            repeatMode = copy.repeatMode
            framesPerUpdate = copy.framesPerUpdate
            autoPlay = copy.autoPlay
            speed = copy.speed
            firstFrame = copy.firstFrame
            lastFrame = copy.lastFrame
            frameInterval = copy.frameInterval
        }

        fun releaseComposition() {
            renderState.release()
        }

        fun valid(): Boolean {
            return renderState.valid()
        }

        fun setCompositionSize(width: Int, height: Int) {
            renderState.setSize(width, height)
        }

        fun renderFrame(frame: Float): Bitmap? {
            return renderState.renderFrame(frame)
        }

        override fun newDrawable(): Drawable {
            return LottieDrawable(this)
        }

        override fun getChangingConfigurations(): Int {
            return 0
        }
    }

    companion object {
        /**
         * Repeats playback without an end.
         */
        const val INFINITE = LottieConstants.INFINITE

        /**
         * Restarts from the first frame after reaching the end frame.
         */
        const val RESTART = LottieConstants.RESTART

        /**
         * Reverses the playback direction after reaching the end frame.
         */
        const val REVERSE = LottieConstants.REVERSE

        @JvmStatic
        fun fromRawResource(resources: Resources, @RawRes resId: Int): LottieDrawable {
            val drawable = LottieDrawable()
            drawable.lottieState.composition = LottieSwComposition.fromRawResource(resources, resId)
            drawable.lottieState.composition?.let { composition ->
                drawable.setLastFrame(composition.lastFrameIndex)
            }
            return drawable
        }
    }
}
