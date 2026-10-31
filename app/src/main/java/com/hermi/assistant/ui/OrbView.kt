package com.hermi.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/**
 * Siri-like animated orb.
 *  - Listening: breathes and spikes with the live microphone level.
 *  - Thinking: glowing ring spins.
 *  - Speaking: pulses gently.
 * No external dependencies — pure Canvas.
 */
class OrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val STATE_IDLE = 0
        const val STATE_LISTENING = 1
        const val STATE_THINKING = 2
        const val STATE_SPEAKING = 3
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val matrix = Matrix()

    private var state = STATE_LISTENING
    private var target = 0f   // raw mic level 0..1
    private var level = 0f    // smoothed mic level
    private var started = false

    /** Feed RecognitionListener RMS values (roughly -2..10 dB). */
    fun onRms(db: Float) {
        target = ((db + 2f) / 10f).coerceIn(0f, 1f)
    }

    fun setOrbState(s: Int) {
        state = s
        postInvalidateOnAnimation()
    }

    fun start() {
        started = true
        postInvalidateOnAnimation()
    }

    fun stop() {
        started = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(width, height) / 2f
        if (maxR <= 0f) return
        val t = System.nanoTime() / 1_000_000_000.0

        // smooth mic level + decay
        level += (target - level) * 0.22f
        target *= 0.90f

        val breath = (sin(t * 2.1) * 0.5 + 0.5).toFloat()
        val speakPulse =
            if (state == STATE_SPEAKING) (sin(t * 7.0) * 0.5 + 0.5).toFloat() * 0.30f else 0f
        val energy = (breath * 0.28f + level * 0.60f + speakPulse).coerceIn(0f, 1f)

        val coreR = maxR * (0.32f + 0.24f * energy)

        // ---- glow halo ----
        val glowR = maxR * (0.78f + 0.18f * energy)
        glowPaint.shader = RadialGradient(
            cx, cy, glowR,
            intArrayOf(0x5C8B5CF6.toInt(), 0x268B5CF6.toInt(), 0x008B5CF6.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowR, glowPaint)

        // ---- core sphere ----
        corePaint.shader = RadialGradient(
            cx, cy - coreR * 0.35f, coreR * 1.3f,
            intArrayOf(
                0xFFF6F1FF.toInt(), 0xFFC4A9FA.toInt(), 0xFF8B5CF6.toInt(), 0xFF6D28D9.toInt()
            ),
            floatArrayOf(0f, 0.32f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreR, corePaint)

        // ---- status ring ----
        if (state == STATE_THINKING) {
            matrix.reset()
            matrix.postRotate(((t * 220) % 360).toFloat(), cx, cy)
            val sweep = SweepGradient(cx, cy, 0x0034D399.toInt(), 0xFF34D399.toInt())
            sweep.setLocalMatrix(matrix)
            ringPaint.shader = sweep
            ringPaint.strokeWidth = maxR * 0.05f
            canvas.drawCircle(cx, cy, maxR * 0.86f, ringPaint)
        } else {
            ringPaint.shader = null
            ringPaint.color = 0x2EFFFFFF.toInt()
            ringPaint.strokeWidth = maxR * 0.018f
            canvas.drawCircle(cx, cy, maxR * 0.86f, ringPaint)
        }

        if (started) postInvalidateOnAnimation()
    }
}
