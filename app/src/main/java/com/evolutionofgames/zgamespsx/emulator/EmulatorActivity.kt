package com.evolutionofgames.zgamespsx.emulator

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * The built-in PS1 emulator screen. Runs in its own process (":emulator") so a core crash can
 * never take the app down and every launch starts from a clean emulator.
 *
 * Needs the libretro core in jniLibs (see CORE_README.md): libswanstation_libretro_android.so
 * or libpcsx_rearmed_libretro_android.so.
 *
 * All PS1 settings live HERE (pause menu -> Emulator settings): resolution, PGXP, texture
 * filter, true colour, CPU mode, disc speed, fast boot, region and BIOS. They are saved
 * straight away and take effect when the game restarts (menu -> Restart game).
 * The on-screen controls live HERE too (pause menu -> Controller settings): button theme,
 * button size, button opacity, and "Move buttons" to drag every control where you want it.
 */
class EmulatorActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_TITLE = "title"
        const val CORE_SWANSTATION = "libswanstation_libretro_android.so"
        const val CORE_PCSX = "libpcsx_rearmed_libretro_android.so"
        private const val REQ_BIOS = 1001

        /** Absolute path of the bundled core, preferring [preferred]; null when none is shipped. */
        fun corePath(context: android.content.Context, preferred: String): String? {
            val dir = context.applicationInfo.nativeLibraryDir
            val order = if (preferred == "pcsx_rearmed") listOf(CORE_PCSX, CORE_SWANSTATION) else listOf(CORE_SWANSTATION, CORE_PCSX)
            return order.map { File(dir, it) }.firstOrNull { it.isFile }?.absolutePath
        }
    }

    @Volatile private var running = true
    @Volatile private var paused = false
    @Volatile private var pendingStateOp = 0  // >0 save to slot, <0 load from slot, 0 none
    private var thread: Thread? = null
    private lateinit var root: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var padView: GamepadView
    private var dialog: AlertDialog? = null
    private var settingsDialog: AlertDialog? = null
    private var controllerDialog: AlertDialog? = null
    private var editBar: View? = null
    private var biosValue: TextView? = null
    private var corePath: String? = null
    private var gamePath: String? = null
    private lateinit var systemDir: File
    private lateinit var saveDir: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        val path = intent.getStringExtra(EXTRA_PATH)
        val core = corePath(this, intent.getStringExtra("core") ?: "swanstation")
        if (path == null || core == null) {
            Toast.makeText(this, "The built-in emulator core is missing from this build.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        corePath = core
        gamePath = path

        root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        surface = SurfaceView(this)
        val surfaceParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER)
        root.addView(surface, surfaceParams)
        // Keep the picture at the chosen shape (4:3 by default), centred.
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l; val h = b - t
            if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
            val (aw, ah) = if (EmuSettings.aspectRatio(this) == "16:9") 16 to 9 else 4 to 3
            val targetW = if (w * ah > h * aw) h * aw / ah else w
            val targetH = if (w * ah > h * aw) h else w * ah / aw
            if (surfaceParams.width != targetW || surfaceParams.height != targetH) {
                surfaceParams.width = targetW; surfaceParams.height = targetH
                surface.post { surface.layoutParams = surfaceParams }
            }
        }
        padView = GamepadView(this, { NativeCore.nativeSetInput(it) }, { showMenu() }, { stick, x, y -> NativeCore.nativeSetAnalog(stick, x, y) })
        root.addView(padView)
        setContentView(root)

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { NativeCore.nativeSetSurface(holder.surface) }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { NativeCore.nativeSetSurface(holder.surface) }
            override fun surfaceDestroyed(holder: SurfaceHolder) { NativeCore.nativeSetSurface(null) }
        })

        val systemDir = EmuSettings.systemDir(this)
        val saveDir = File(getExternalFilesDir(null) ?: filesDir, "saves").apply { mkdirs() }
        this.systemDir = systemDir
        this.saveDir = saveDir
        startEmulation()
    }

    private fun startEmulation() {
        val core = corePath ?: return
        val path = gamePath ?: return
        thread = Thread({ emulate(core, path, systemDir.absolutePath, saveDir.absolutePath) }, "psx-emu").also { it.start() }
    }

    /** Stops the current run and starts the game again with the latest settings. */
    private fun restartGame() {
        running = false
        paused = false
        try { thread?.join(3000) } catch (_: Throwable) { }
        running = true
        startEmulation()
    }

    private fun emulate(corePath: String, gamePath: String, systemDir: String, saveDir: String) {
        var audio: AudioTrack? = null
        try {
            // (Re-)attach the picture surface: a restart unloads the core, which releases it.
            try { NativeCore.nativeSetSurface(surface.holder.surface) } catch (_: Throwable) { }
            // Push the user's emulator settings into the core before it loads.
            EmuSettings.applyAll(this)
            NativeCore.nativeSetAnalogEnabled(PadSettings.analogEnabled(this))
            if (!NativeCore.nativeLoadCore(corePath, systemDir, saveDir)) { fail("The emulator core could not be loaded."); return }
            if (!NativeCore.nativeLoadGame(gamePath)) { fail("The game could not be started. Check that the file is a valid PS1 disc image."); NativeCore.nativeUnload(); return }
            val rate = NativeCore.nativeSampleRate().coerceIn(8000, 96000)
            val fps = NativeCore.nativeFps().coerceIn(20.0, 120.0)
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            audio = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(maxOf(minBuf, rate / 6 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audio.play()
            val buf = ShortArray(16384)
            val frameNs = (1_000_000_000.0 / fps).toLong()
            var wasPaused = false
            while (running) {
                val op = pendingStateOp
                if (op != 0) {
                    pendingStateOp = 0
                    val ok = if (op > 0) NativeCore.nativeSaveState(stateFile(op).absolutePath)
                        else NativeCore.nativeLoadState(stateFile(-op).absolutePath)
                    val msg = if (ok) (if (op > 0) "State saved to slot $op." else "State loaded from slot ${-op}.")
                        else "Could not ${if (op > 0) "save" else "load"} the state."
                    runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
                }
                if (paused) {
                    if (!wasPaused) { audio.pause(); wasPaused = true }
                    Thread.sleep(40); continue
                }
                if (wasPaused) { audio.play(); wasPaused = false }
                val start = System.nanoTime()
                val n = NativeCore.nativeRunFrame(buf)
                if (n == -2) { runOnUiThread { finish() }; break }
                if (n < 0) { fail("The emulator stopped unexpectedly."); break }
                if (n > 0) {
                    audio.write(buf, 0, n)          // blocking write paces the emulation to real time
                } else {
                    val left = frameNs - (System.nanoTime() - start)
                    if (left > 0) Thread.sleep(left / 1_000_000, (left % 1_000_000).toInt())
                }
            }
        } catch (t: Throwable) {
            fail("The emulator hit an error: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            try { NativeCore.nativeUnload() } catch (_: Throwable) { }
            try { audio?.stop(); audio?.release() } catch (_: Throwable) { }
        }
    }

    private fun fail(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun showMenu() {
        if (dialog?.isShowing == true) return
        paused = true
        dialog = AlertDialog.Builder(this)
            .setTitle(intent.getStringExtra(EXTRA_TITLE) ?: "Game")
            .setItems(arrayOf("Resume", "Emulator settings", "Controller settings", "Save state", "Load state", "Restart game", "Exit game")) { _, which ->
                when (which) {
                    0 -> { paused = false; hideSystemBars() }
                    1 -> showEmuSettings()
                    2 -> showControllerSettings()
                    3 -> showStateSlots(save = true)
                    4 -> showStateSlots(save = false)
                    5 -> { paused = false; hideSystemBars(); restartGame() }
                    6 -> quit()
                }
            }
            .setOnCancelListener { paused = false; hideSystemBars() }
            .show()
    }

    private fun stateFile(slot: Int): File {
        val key = File(gamePath ?: "game").nameWithoutExtension
            .replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "game" }
        return File(saveDir, "$key.state$slot")
    }

    /** Save/load state slots 1-5. The actual work runs on the emulation thread. */
    private fun showStateSlots(save: Boolean) {
        val items = (1..5).map { slot ->
            val f = stateFile(slot)
            if (f.isFile) "Slot $slot — saved ${android.text.format.DateFormat.format("dd MMM HH:mm", f.lastModified())}"
            else "Slot $slot — empty"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (save) "Save state" else "Load state")
            .setItems(items) { _, which ->
                val slot = which + 1
                if (!save && !stateFile(slot).isFile) {
                    Toast.makeText(this, "Slot $slot is empty.", Toast.LENGTH_SHORT).show()
                    showMenu(); return@setItems
                }
                pendingStateOp = if (save) slot else -slot
                paused = false; hideSystemBars()
            }
            .setOnCancelListener { showMenu() }
            .show()
    }

    // ------------------------------------------------------------------
    // Settings UI: cards, section chips, choice lists (shared by both dialogs).
    // ------------------------------------------------------------------

    private fun px(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun cardBg(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = px(14f).toFloat()
        setColor(0xFF1D2027.toInt())
        setStroke(px(1f), 0xFF2C313C.toInt())
    }

    private fun pillBg(color: Int = 0xFF2C313C.toInt()): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = px(20f).toFloat()
        setColor(color)
    }

    private class Shell(val root: LinearLayout, val list: LinearLayout, val scroll: ScrollView, val headers: MutableMap<String, View>)

    /** Full-screen settings page: title bar with close, section chips, scrolling cards. */
    private fun buildShell(ctx: android.content.Context, titleText: String, chipSections: List<String>, onClose: () -> Unit): Shell {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF14161B.toInt())
            setPadding(px(16f), px(12f), px(16f), px(12f))
        }
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(ctx).apply {
            text = titleText; setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(ctx).apply {
            text = "✕"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); setTextColor(Color.WHITE)
            setPadding(px(12f), px(8f), px(12f), px(8f))
            setOnClickListener { onClose() }
        })
        root.addView(header)
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val headers = HashMap<String, View>()
        if (chipSections.isNotEmpty()) {
            val chipRow = HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, px(10f), 0, px(10f))
            }
            val chipList = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (sec in chipSections) {
                chipList.addView(TextView(ctx).apply {
                    text = sec; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(Color.WHITE)
                    background = pillBg()
                    setPadding(px(14f), px(8f), px(14f), px(8f))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = px(8f) }
                    setOnClickListener {
                        val hv = headers[sec]
                        if (hv != null) scroll.post { scroll.smoothScrollTo(0, hv.top) }
                    }
                })
            }
            chipRow.addView(chipList)
            root.addView(chipRow)
        }
        root.addView(scroll)
        return Shell(root, list, scroll, headers)
    }

    private fun sectionHeader(list: LinearLayout, text: String): TextView {
        val tv = TextView(this).apply {
            setText(text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#FFB74D"))
            letterSpacing = 0.12f
            setPadding(0, px(20f), 0, px(6f))
        }
        list.addView(tv)
        return tv
    }

    /** One card row: title + description on the left, a control on the right. */
    private fun rowCard(list: LinearLayout, title: String, sub: String, control: View, onClick: (() -> Unit)? = null): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = cardBg()
            setPadding(px(16f), px(12f), px(16f), px(12f))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(10f) }
            if (onClick != null) { isClickable = true; setOnClickListener { onClick() } }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = px(12f) }
        }
        texts.addView(TextView(this).apply {
            setText(title); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        })
        texts.addView(TextView(this).apply {
            setText(sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#B0B0B0"))
        })
        row.addView(texts)
        control.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        row.addView(control)
        list.addView(row)
        return row
    }

    private fun valuePill(text: String): TextView = TextView(this).apply {
        setText(text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#FFB74D"))
        background = pillBg()
        setPadding(px(14f), px(8f), px(14f), px(8f))
    }

    private fun singleChoice(title: String, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { d, which -> onPick(which); d.dismiss() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun note(list: LinearLayout, text: String) {
        list.addView(TextView(this).apply {
            setText(text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#B0B0B0"))
            setPadding(0, px(6f), 0, 0)
        })
    }

    /** Every PS1 setting, right inside the emulator. Changes save at once and take
     *  effect when the game restarts (button below, or next launch). */
    private fun showEmuSettings() {
        if (settingsDialog?.isShowing == true) return
        val ctx = this
        var dlg: AlertDialog? = null
        val sections = EmuSettings.OPTIONS.map { it.section }.distinct()
        val shell = buildShell(ctx, "Emulator settings", sections) { dlg?.dismiss(); showMenu() }
        val list = shell.list

        fun cycleRow(opt: EmuSettings.Opt) {
            val pill = valuePill(EmuSettings.label(opt, EmuSettings.value(ctx, opt)) + "  ›")
            rowCard(list, opt.title, opt.sub, pill) {
                val cur = opt.values.indexOf(EmuSettings.value(ctx, opt))
                singleChoice(opt.title, opt.labels, cur) { which ->
                    EmuSettings.set(ctx, opt, opt.values[which])
                    pill.text = EmuSettings.label(opt, opt.values[which]) + "  ›"
                }
            }
        }
        fun switchRow(opt: EmuSettings.Opt) {
            val sw = Switch(ctx).apply {
                isChecked = EmuSettings.value(ctx, opt) == "true"
                setOnCheckedChangeListener { _, c -> EmuSettings.set(ctx, opt, c.toString()) }
            }
            rowCard(list, opt.title, opt.sub, sw)
        }

        var section: String? = null
        for (opt in EmuSettings.OPTIONS) {
            if (opt.section != section) {
                section = opt.section
                shell.headers[section] = sectionHeader(list, section)
                if (section == "BIOS") {
                    val biosName = EmuSettings.installedBiosName(ctx)
                    biosValue = valuePill((biosName ?: "OpenBIOS (built-in)") + "  ›")
                    rowCard(list, "PlayStation BIOS",
                        "Tap to install a real BIOS (scph1001.bin). Without one, the built-in OpenBIOS is used.",
                        biosValue ?: valuePill("OpenBIOS (built-in)")) {
                        startActivityForResult(
                            Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) },
                            REQ_BIOS
                        )
                    }
                }
            }
            when {
                opt.values.size == 1 -> rowCard(list, opt.title, opt.sub, valuePill(opt.labels.first()))
                opt.values == listOf("false", "true") -> switchRow(opt)
                else -> cycleRow(opt)
            }
        }
        note(list, "Settings apply when the game restarts.")

        settingsDialog = AlertDialog.Builder(ctx)
            .setView(shell.root)
            .setPositiveButton("Done") { _, _ -> showMenu() }
            .setNeutralButton("Restart game") { _, _ -> paused = false; hideSystemBars(); restartGame() }
            .setOnCancelListener { showMenu() }
            .show().also { dlg = it }
        settingsDialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    /** On-screen controls: theme, size, opacity, and dragging the layout. */
    private fun showControllerSettings() {
        if (controllerDialog?.isShowing == true) return
        val ctx = this
        var dlg: AlertDialog? = null
        val shell = buildShell(ctx, "Controller settings", emptyList()) { dlg?.dismiss(); showMenu() }
        val list = shell.list
        sectionHeader(list, "CONTROLS")

        // Analog sticks first, and the whole row taps to flip it — the switch
        // alone was too easy to miss.
        val analogSw = Switch(ctx).apply {
            isChecked = PadSettings.analogEnabled(ctx)
            setOnCheckedChangeListener { _, c ->
                PadSettings.setAnalogEnabled(ctx, c)
                NativeCore.nativeSetAnalogEnabled(c)
                padView.applyPadSettings()
            }
        }
        rowCard(list, "Analog sticks", "Off hides the sticks — D-pad only. Some games need analog sticks.", analogSw) {
            analogSw.toggle()
        }
        val themePill = valuePill(PadSettings.theme(ctx).label + "  ›")
        rowCard(list, "Button theme", "How the on-screen controls look.", themePill) {
            val cur = PadSettings.Theme.values().indexOf(PadSettings.theme(ctx))
            singleChoice("Button theme", PadSettings.Theme.values().map { it.label }, cur) { which ->
                PadSettings.setTheme(ctx, PadSettings.Theme.values()[which])
                themePill.text = PadSettings.Theme.values()[which].label + "  ›"
                padView.applyPadSettings()
            }
        }
        val sizePill = valuePill(PadSettings.scaleLabel(ctx) + "  ›")
        rowCard(list, "Button size", "Bigger controls are easier to hit.", sizePill) {
            val cur = PadSettings.SCALES.indexOfFirst { it.first == PadSettings.scale(ctx) }.coerceAtLeast(0)
            singleChoice("Button size", PadSettings.SCALES.map { it.second }, cur) { which ->
                PadSettings.setScale(ctx, PadSettings.SCALES[which].first)
                sizePill.text = PadSettings.SCALES[which].second + "  ›"
                padView.applyPadSettings()
            }
        }
        val opPill = valuePill(PadSettings.opacityLabel(ctx) + "  ›")
        rowCard(list, "Button opacity", "Fainter controls let more of the game show through.", opPill) {
            val cur = PadSettings.OPACITIES.indexOfFirst { it.first == PadSettings.opacity(ctx) }.coerceAtLeast(0)
            singleChoice("Button opacity", PadSettings.OPACITIES.map { it.second }, cur) { which ->
                PadSettings.setOpacity(ctx, PadSettings.OPACITIES[which].first)
                opPill.text = PadSettings.OPACITIES[which].second + "  ›"
                padView.applyPadSettings()
            }
        }
        rowCard(list, "Move buttons", "Drag every control to where your thumbs want it.", valuePill("Move  ›")) {
            dlg?.dismiss()
            enterEditMode()
        }
        rowCard(list, "Reset layout", "Put every control back where it started.", valuePill("Reset")) {
            padView.resetLayout()
            Toast.makeText(ctx, "Controller layout reset.", Toast.LENGTH_SHORT).show()
        }
        note(list, "Theme, size and opacity apply right away.")

        controllerDialog = AlertDialog.Builder(ctx)
            .setView(shell.root)
            .setPositiveButton("Done") { _, _ -> showMenu() }
            .setOnCancelListener { showMenu() }
            .show().also { dlg = it }
        controllerDialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    /** "Move buttons": drag the controls around right on the game screen. */
    private fun enterEditMode() {
        paused = true
        padView.setEditing(true)
        val ctx = this
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding(px(16f), px(12f), px(16f), px(12f))
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(TextView(ctx).apply {
            text = "Drag the controls to move them"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(TextView(ctx).apply {
            text = "RESET"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FFB74D")); setPadding(px(14f), px(8f), px(14f), px(8f))
            setOnClickListener { padView.resetLayout() }
        })
        bar.addView(TextView(ctx).apply {
            text = "DONE"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); background = pillBg(0xFF2E7D32.toInt())
            setPadding(px(16f), px(8f), px(16f), px(8f))
            setOnClickListener { exitEditMode() }
        })
        root.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        editBar = bar
    }

    private fun exitEditMode() {
        padView.setEditing(false)
        editBar?.let { root.removeView(it) }
        editBar = null
        showMenu()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_BIOS || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        Thread {
            val displayName = try {
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            } catch (_: Exception) { null }
            val name = EmuSettings.installBios(this, uri, displayName)
            runOnUiThread {
                if (name != null) {
                    biosValue?.text = name
                    Toast.makeText(this, "BIOS installed: $name", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "That BIOS file could not be installed.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun quit() {
        running = false
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (editBar != null) exitEditMode() else showMenu()
    }

    override fun onPause() { super.onPause(); paused = true }
    override fun onResume() {
        super.onResume()
        if (dialog?.isShowing != true && settingsDialog?.isShowing != true &&
            controllerDialog?.isShowing != true && editBar == null) paused = false
        hideSystemBars()
    }

    override fun onDestroy() {
        running = false
        try { thread?.join(2500) } catch (_: Throwable) { }
        super.onDestroy()
        // This activity lives in its own process: end it so the next launch starts clean.
        Process.killProcess(Process.myPid())
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
}
