package com.evolutionofgames.zgamespsx.emulator

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * On-screen pad in DuckStation's style: thin white outlines, transparent fill so the
 * game shows through, white symbols. L2 above L1 top-left, R2 above R1 top-right;
 * + d-pad mid-left, face diamond mid-right; a stick under each cluster; tiny
 * Select/Start bottom-centre, pause top-centre.
 *
 * Look and layout are user-adjustable (PadSettings): 3 themes (Classic white,
 * PlayStation, Neon), button size, opacity, and "Move buttons" edit mode where
 * every control cluster can be dragged to a new spot. Multi-touch. Produces a
 * libretro joypad mask + analog stick values.
 */
class GamepadView(
    context: Context,
    private val onMask: (Int) -> Unit,
    private val onMenu: () -> Unit,
    private val onAnalog: (stick: Int, x: Int, y: Int) -> Unit
) : View(context) {

    private class Btn(val label: String, val bit: Int, val rect: RectF, val round: Boolean, val color: Int)
    private class Stick(val id: Int, var cx: Float = 0f, var cy: Float = 0f, var r: Float = 0f,
                        var knobX: Float = 0f, var knobY: Float = 0f, var pointer: Int = -1)

    private val buttons = ArrayList<Btn>()
    private val sticks = arrayOf(Stick(0), Stick(1))
    private var dpadCx = 0f
    private var dpadCy = 0f
    private var dpadArm = 0f
    private var dpadHalf = 0f
    private val menuRect = RectF()
    private val clusterBox = HashMap<String, RectF>()
    private var lastMask = 0
    private var pressed = 0

    // Pad look, re-read from PadSettings on every layout pass.
    private var baseU = 0f
    private var theme = PadSettings.Theme.OUTLINE
    private var opacityF = 0.8f
    private var analogOn = true

    // Edit mode ("Move buttons"): clusters can be dragged; no game input is sent.
    var editing = false
        private set
    private var dragCluster: String? = null
    private var dragId = -1
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragStartOff = 0f to 0f

    private val fillPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val fillDisc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1A1A.toInt() }
    private val fillKnob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF555555.toInt() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3.5f }
    private val strokeThin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(16f, 14f), 0f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER }
    private val textSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER }

    // libretro joypad ids
    private companion object {
        const val B = 0; const val Y = 1; const val SELECT = 2; const val START = 3
        const val UP = 4; const val DOWN = 5; const val LEFT = 6; const val RIGHT = 7
        const val A = 8; const val X = 9; const val L = 10; const val R = 11; const val L2 = 12; const val R2 = 13
        // Real PlayStation button colours (PS / Neon themes)
        const val GREEN = 0xFF00A651.toInt()
        const val RED = 0xFFE60012.toInt()
        const val BLUE = 0xFF0072CE.toInt()
        const val PINK = 0xFFE6007E.toInt()
    }

    fun setEditing(e: Boolean) {
        editing = e
        if (e) {
            for (st in sticks) if (st.pointer != -1) releaseStick(st)
            publish(0)
        }
        invalidate()
    }

    /** Re-read theme/size/opacity/layout from PadSettings and redraw. */
    fun applyPadSettings() {
        if (!PadSettings.analogEnabled(context)) {
            // Analog just turned off: drop any stick held mid-touch.
            for (st in sticks) if (st.pointer != -1) releaseStick(st)
            publish(0)
        }
        if (width > 0 && height > 0) computeLayout(width, height)
        invalidate()
    }

    fun resetLayout() {
        PadSettings.resetLayout(context)
        applyPadSettings()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        computeLayout(w, h)
    }

    private fun computeLayout(w: Int, h: Int) {
        buttons.clear()
        clusterBox.clear()
        baseU = min(w, h) * 0.10f
        val u = baseU * PadSettings.scale(context)
        theme = PadSettings.theme(context)
        opacityF = PadSettings.opacity(context)
        analogOn = PadSettings.analogEnabled(context)
        text.textSize = u * 0.72f
        textSmall.textSize = u * 0.42f
        glyph.textSize = u * 0.72f
        val off = PadSettings.offsets(context)
        fun ox(key: String) = (off[key]?.first ?: 0f) * baseU
        fun oy(key: String) = (off[key]?.second ?: 0f) * baseU

        // D-pad: a real + cross, mid-left.
        dpadCx = w * 0.105f + ox(PadSettings.C_DPAD); dpadCy = h * 0.52f + oy(PadSettings.C_DPAD)
        dpadArm = 1.8f * u; dpadHalf = 0.72f * u
        clusterBox[PadSettings.C_DPAD] = RectF(dpadCx - dpadArm, dpadCy - dpadArm, dpadCx + dpadArm, dpadCy + dpadArm)

        // Sticks sit UNDER each cluster, like DuckStation. Hidden when analog is off:
        // radius 0 as well, so no touch can ever grab one through any path.
        sticks[0].apply { cx = w * 0.105f + ox(PadSettings.C_STICK_L); cy = h * 0.88f + oy(PadSettings.C_STICK_L); r = if (analogOn) 1.05f * u else 0f; knobX = cx; knobY = cy }
        sticks[1].apply { cx = w * 0.875f + ox(PadSettings.C_STICK_R); cy = h * 0.88f + oy(PadSettings.C_STICK_R); r = if (analogOn) 1.05f * u else 0f; knobX = cx; knobY = cy }
        if (analogOn) {
            for (st in sticks) {
                val key = if (st.id == 0) PadSettings.C_STICK_L else PadSettings.C_STICK_R
                clusterBox[key] = RectF(st.cx - st.r, st.cy - st.r, st.cx + st.r, st.cy + st.r)
            }
        }

        // Face buttons: classic diamond, mid-right. Spaced wide apart (1.6u between
        // centres) so neighbouring buttons can't be hit by mistake.
        val fx = w * 0.86f + ox(PadSettings.C_FACE); val fy = h * 0.52f + oy(PadSettings.C_FACE)
        val o = 1.6f * u; val r = 0.85f * u
        fun circle(cx: Float, cy: Float, rr: Float) = RectF(cx - rr, cy - rr, cx + rr, cy + rr)
        buttons.add(Btn("△", X, circle(fx, fy - o, r), true, GREEN))    // triangle
        buttons.add(Btn("✕", B, circle(fx, fy + o, r), true, BLUE))     // cross
        buttons.add(Btn("□", Y, circle(fx - o, fy, r), true, PINK))     // square
        buttons.add(Btn("○", A, circle(fx + o, fy, r), true, RED))      // circle
        clusterBox[PadSettings.C_FACE] = RectF(fx - o - r, fy - o - r, fx + o + r, fy + o + r)

        // Shoulders: STACKED at the top corners, L2 above L1 / R2 above R1.
        val tw = 2.4f * u; val th = 0.85f * u
        val l2 = RectF(0.7f * u + ox(PadSettings.C_SH_L), 0.5f * u + oy(PadSettings.C_SH_L), 0.7f * u + tw + ox(PadSettings.C_SH_L), 0.5f * u + th + oy(PadSettings.C_SH_L))
        val l1 = RectF(0.7f * u + ox(PadSettings.C_SH_L), 1.5f * u + oy(PadSettings.C_SH_L), 0.7f * u + tw + ox(PadSettings.C_SH_L), 1.5f * u + th + oy(PadSettings.C_SH_L))
        val r2 = RectF(w - 0.7f * u - tw + ox(PadSettings.C_SH_R), 0.5f * u + oy(PadSettings.C_SH_R), w - 0.7f * u + ox(PadSettings.C_SH_R), 0.5f * u + th + oy(PadSettings.C_SH_R))
        val r1 = RectF(w - 0.7f * u - tw + ox(PadSettings.C_SH_R), 1.5f * u + oy(PadSettings.C_SH_R), w - 0.7f * u + ox(PadSettings.C_SH_R), 1.5f * u + th + oy(PadSettings.C_SH_R))
        buttons.add(Btn("L2", L2, l2, false, 0))
        buttons.add(Btn("L1", L, l1, false, 0))
        buttons.add(Btn("R2", R2, r2, false, 0))
        buttons.add(Btn("R1", R, r1, false, 0))
        clusterBox[PadSettings.C_SH_L] = RectF(l2.left, l2.top, l2.right, l1.bottom)
        clusterBox[PadSettings.C_SH_R] = RectF(r2.left, r2.top, r2.right, r1.bottom)

        // Select / Start: tiny pills, bottom-centre.
        val sw = 2.0f * u; val sh = 0.85f * u; val sy = h - 1.2f * u
        val sel = RectF(w / 2f - sw - 0.35f * u + ox(PadSettings.C_SEL), sy + oy(PadSettings.C_SEL), w / 2f - 0.35f * u + ox(PadSettings.C_SEL), sy + sh + oy(PadSettings.C_SEL))
        val sta = RectF(w / 2f + 0.35f * u + ox(PadSettings.C_SEL), sy + oy(PadSettings.C_SEL), w / 2f + sw + 0.35f * u + ox(PadSettings.C_SEL), sy + sh + oy(PadSettings.C_SEL))
        buttons.add(Btn("SELECT", SELECT, sel, false, 0))
        buttons.add(Btn("START", START, sta, false, 0))
        clusterBox[PadSettings.C_SEL] = RectF(sel.left, sel.top, sta.right, sta.bottom)

        // Pause: small circle, top-centre.
        val mr = 0.8f * u
        menuRect.set(w / 2f - mr + ox(PadSettings.C_MENU), 0.85f * u - mr + oy(PadSettings.C_MENU), w / 2f + mr + ox(PadSettings.C_MENU), 0.85f * u + mr + oy(PadSettings.C_MENU))
        clusterBox[PadSettings.C_MENU] = RectF(menuRect)
    }

    private fun isOn(bit: Int) = (pressed shr bit) and 1 == 1

    override fun onDraw(canvas: Canvas) {
        // Theme + opacity drive every paint.
        val op = opacityF
        stroke.alpha = (0xA6 * op).toInt()
        strokeThin.alpha = (0x88 * op).toInt()
        text.alpha = (255 * op).toInt()
        textSmall.alpha = (255 * op).toInt()
        glyph.alpha = (255 * op).toInt()
        fillPressed.alpha = (0x40 * op).toInt()
        fillDisc.alpha = (0xCC * op).toInt()
        fillKnob.alpha = (0xAA * op).toInt()
        val solid = theme != PadSettings.Theme.OUTLINE

        // D-pad: + cross, each arm lights up when pressed.
        val vert = RectF(dpadCx - dpadHalf, dpadCy - dpadArm, dpadCx + dpadHalf, dpadCy + dpadArm)
        val horiz = RectF(dpadCx - dpadArm, dpadCy - dpadHalf, dpadCx + dpadArm, dpadCy + dpadHalf)
        if (solid) {
            canvas.drawRoundRect(vert, dpadHalf * 0.45f, dpadHalf * 0.45f, fillDisc)
            canvas.drawRoundRect(horiz, dpadHalf * 0.45f, dpadHalf * 0.45f, fillDisc)
        }
        if (isOn(UP)) canvas.drawRoundRect(RectF(dpadCx - dpadHalf, dpadCy - dpadArm, dpadCx + dpadHalf, dpadCy), dpadHalf * 0.45f, dpadHalf * 0.45f, fillPressed)
        if (isOn(DOWN)) canvas.drawRoundRect(RectF(dpadCx - dpadHalf, dpadCy, dpadCx + dpadHalf, dpadCy + dpadArm), dpadHalf * 0.45f, dpadHalf * 0.45f, fillPressed)
        if (isOn(LEFT)) canvas.drawRoundRect(RectF(dpadCx - dpadArm, dpadCy - dpadHalf, dpadCx, dpadCy + dpadHalf), dpadHalf * 0.45f, dpadHalf * 0.45f, fillPressed)
        if (isOn(RIGHT)) canvas.drawRoundRect(RectF(dpadCx, dpadCy - dpadHalf, dpadCx + dpadArm, dpadCy + dpadHalf), dpadHalf * 0.45f, dpadHalf * 0.45f, fillPressed)
        canvas.drawRoundRect(vert, dpadHalf * 0.45f, dpadHalf * 0.45f, stroke)
        canvas.drawRoundRect(horiz, dpadHalf * 0.45f, dpadHalf * 0.45f, stroke)
        val a = dpadArm * 0.62f
        canvas.drawText("▲", dpadCx, dpadCy - a + text.textSize * 0.35f, text)
        canvas.drawText("▼", dpadCx, dpadCy + a + text.textSize * 0.35f, text)
        canvas.drawText("◀", dpadCx - a, dpadCy + text.textSize * 0.35f, text)
        canvas.drawText("▶", dpadCx + a, dpadCy + text.textSize * 0.35f, text)

        // Analog sticks: ring + knob that follows the thumb. Hidden when analog is off.
        if (analogOn) for (st in sticks) {
            if (solid) canvas.drawCircle(st.cx, st.cy, st.r, fillDisc)
            canvas.drawCircle(st.cx, st.cy, st.r, strokeThin)
            if (solid) canvas.drawCircle(st.knobX, st.knobY, st.r * 0.48f, fillKnob)
            canvas.drawCircle(st.knobX, st.knobY, st.r * 0.48f, strokeThin)
        }

        // Buttons: outline (Classic) or dark disc (PlayStation / Neon).
        for (b in buttons) {
            if (solid) {
                if (b.round) canvas.drawOval(b.rect, fillDisc) else canvas.drawRoundRect(b.rect, 20f, 20f, fillDisc)
            }
            if (isOn(b.bit)) {
                if (b.round) canvas.drawOval(b.rect, fillPressed) else canvas.drawRoundRect(b.rect, 20f, 20f, fillPressed)
            }
            if (b.round) canvas.drawOval(b.rect, stroke) else canvas.drawRoundRect(b.rect, 20f, 20f, stroke)
            if (b.round) {
                // Face symbols: white in Classic, PlayStation colours otherwise (Neon glows).
                glyph.color = if (theme == PadSettings.Theme.OUTLINE) 0xFFFFFFFF.toInt() else b.color
                if (theme == PadSettings.Theme.NEON) glyph.setShadowLayer(18f, 0f, 0f, b.color) else glyph.clearShadowLayer()
                canvas.drawText(b.label, b.rect.centerX(), b.rect.centerY() + glyph.textSize * 0.35f, glyph)
            } else {
                canvas.drawText(b.label, b.rect.centerX(), b.rect.centerY() + textSmall.textSize * 0.35f, textSmall)
            }
        }
        canvas.drawCircle(menuRect.centerX(), menuRect.centerY(), menuRect.width() / 2f, stroke)
        canvas.drawText("❚❚", menuRect.centerX(), menuRect.centerY() + textSmall.textSize * 0.35f, textSmall)

        // Edit mode: dashed box + label around every cluster.
        if (editing) {
            for ((key, box) in clusterBox) {
                canvas.drawRoundRect(box, 18f, 18f, dash)
                val label = PadSettings.CLUSTER_LABELS[key] ?: key
                canvas.drawText(label, box.centerX(), box.top - 10f, textSmall)
            }
        }
    }

    private fun stickAt(x: Float, y: Float): Stick? {
        if (!analogOn) return null
        return sticks.firstOrNull { st -> val dx = x - st.cx; val dy = y - st.cy; dx * dx + dy * dy <= st.r * st.r * 1.44f }
    }

    private fun updateStick(st: Stick, x: Float, y: Float) {
        var dx = x - st.cx; var dy = y - st.cy
        val len = sqrt(dx * dx + dy * dy)
        if (len > st.r) { dx *= st.r / len; dy *= st.r / len }
        st.knobX = st.cx + dx; st.knobY = st.cy + dy
        // libretro analog: -32767..32767, up = negative y.
        onAnalog(st.id, (dx / st.r * 32767).toInt(), (-dy / st.r * 32767).toInt())
        invalidate()
    }

    private fun releaseStick(st: Stick) {
        st.pointer = -1; st.knobX = st.cx; st.knobY = st.cy
        onAnalog(st.id, 0, 0)
        invalidate()
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val action = ev.actionMasked
        if (editing) return onTouchEdit(ev, action)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (action == MotionEvent.ACTION_UP && menuRect.contains(ev.x, ev.y)) onMenu()
            for (st in sticks) if (st.pointer != -1) releaseStick(st)
            publish(0); return true
        }
        if (action == MotionEvent.ACTION_POINTER_UP) {
            val id = ev.getPointerId(ev.actionIndex)
            sticks.firstOrNull { it.pointer == id }?.let { releaseStick(it) }
        }
        if (action == MotionEvent.ACTION_DOWN && menuRect.contains(ev.x, ev.y)) return true
        var mask = 0
        val skip = if (action == MotionEvent.ACTION_POINTER_UP) ev.actionIndex else -1
        for (i in 0 until ev.pointerCount) {
            if (i == skip) continue
            val id = ev.getPointerId(i)
            val x = ev.getX(i); val y = ev.getY(i)
            // Captured stick keeps following its finger.
            val held = sticks.firstOrNull { it.pointer == id }
            if (held != null) { updateStick(held, x, y); continue }
            // A fresh touch inside a free stick base captures it.
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                val free = stickAt(x, y)?.takeIf { it.pointer == -1 }
                if (free != null) { free.pointer = id; updateStick(free, x, y); continue }
            }
            // D-pad: inside the + cross — direction by dominant axis;
            // a clearly diagonal touch presses both directions.
            val dx = x - dpadCx; val dy = y - dpadCy
            val adx = abs(dx); val ady = abs(dy)
            val dead = dpadHalf * 0.45f
            if (adx <= dpadArm * 1.12f && ady <= dpadArm * 1.12f && (adx > dead || ady > dead)) {
                val ratio = min(adx, ady) / max(adx, ady)
                if (ratio > 0.55f) {
                    if (dx < 0) mask = mask or (1 shl LEFT) else mask = mask or (1 shl RIGHT)
                    if (dy < 0) mask = mask or (1 shl UP) else mask = mask or (1 shl DOWN)
                } else if (adx > ady) {
                    if (dx < 0) mask = mask or (1 shl LEFT) else mask = mask or (1 shl RIGHT)
                } else {
                    if (dy < 0) mask = mask or (1 shl UP) else mask = mask or (1 shl DOWN)
                }
            }
            // Buttons: round ones hit-test as circles (so a thumb on the edge of one
            // face button can't trigger its neighbour); pills hit-test as rects.
            for (b in buttons) {
                val hit = if (b.round) {
                    val dx = x - b.rect.centerX(); val dy = y - b.rect.centerY()
                    val rr = b.rect.width() / 2f + 8f
                    dx * dx + dy * dy <= rr * rr
                } else {
                    val pad = 10f
                    x >= b.rect.left - pad && x <= b.rect.right + pad && y >= b.rect.top - pad && y <= b.rect.bottom + pad
                }
                if (hit) mask = mask or (1 shl b.bit)
            }
        }
        publish(mask)
        return true
    }

    /** Edit mode touch: drag a whole cluster to a new spot. No game input is sent. */
    private fun onTouchEdit(ev: MotionEvent, action: Int): Boolean {
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (dragCluster != null) return true
                val idx = ev.actionIndex
                val x = ev.getX(idx); val y = ev.getY(idx)
                val entry = clusterBox.entries.firstOrNull { it.value.contains(x, y) } ?: return true
                dragCluster = entry.key
                dragId = ev.getPointerId(idx)
                dragStartX = x; dragStartY = y
                dragStartOff = PadSettings.offsets(context)[entry.key] ?: (0f to 0f)
            }
            MotionEvent.ACTION_MOVE -> {
                val key = dragCluster ?: return true
                val idx = ev.findPointerIndex(dragId)
                if (idx < 0) return true
                val box = clusterBox[key] ?: return true
                // Keep the whole cluster on screen.
                val ddx = (ev.getX(idx) - dragStartX).coerceIn(8f - box.left, width - 8f - box.right)
                val ddy = (ev.getY(idx) - dragStartY).coerceIn(8f - box.top, height - 8f - box.bottom)
                PadSettings.setOffset(context, key,
                    dragStartOff.first + ddx / baseU, dragStartOff.second + ddy / baseU)
                computeLayout(width, height)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val id = if (action == MotionEvent.ACTION_POINTER_UP) ev.getPointerId(ev.actionIndex) else dragId
                if (id == dragId) { dragCluster = null; dragId = -1 }
            }
        }
        return true
    }

    private fun publish(mask: Int) {
        if (mask != lastMask) {
            lastMask = mask; pressed = mask
            onMask(mask); invalidate()
        }
    }
}
