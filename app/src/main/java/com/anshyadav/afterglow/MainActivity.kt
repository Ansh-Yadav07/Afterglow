package com.anshyadav.afterglow

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : Activity(), Choreographer.FrameCallback {
    private enum class Mode { IDLE, PLAYING }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val audio = AudioEngine()
    private lateinit var prefs: SharedPreferences

    private lateinit var root: FrameLayout
    private lateinit var ambient: AmbientView
    private lateinit var vis: VisualizerView
    private lateinit var art: ArtView
    private lateinit var clock: ClockView
    private lateinit var column: FrameLayout
    private lateinit var label: TextView
    private lateinit var title: PosterTitle
    private lateinit var artist: TextView
    private lateinit var lyrics: LyricsView
    private lateinit var progress: ProgressView
    private lateinit var elapsed: TextView
    private lateinit var total: TextView
    private lateinit var controls: FrameLayout
    private lateinit var playBtn: IconButton
    private lateinit var tArt: TextView
    private lateinit var tVis: TextView
    private lateinit var tLyr: TextView
    private lateinit var dateTop: TextView
    private lateinit var batteryTop: TextView
    private lateinit var idleDate: TextView
    private lateinit var resume: TextView
    private lateinit var setupLink: TextView
    private var setup: View? = null

    private var u = 1f
    private var W = 0
    private var H = 0
    private var built = false

    private var mode = Mode.IDLE
    private var np: NowPlaying? = null
    private var loadedKey = ""
    private var loadedSig = 0
    private var demo: Demo? = null
    private var mood: Mood = ArtAnalysis.moodForNow()
    private var lyricLines: List<LyricLine> = emptyList()
    private var lyricsWanted = false
    private var visMode = 1
    private var vinyl = false

    private var last = 0L
    private var frame = 0
    private var running = false
    private var dimNow = 0.92f
    private var motionNow = 0.16f
    private var motesNow = 0.3f
    private var lastMinute = -1
    private var controlsShown = true
    private var audioOn = false
    private var audioMode = 0

    private val goIdle = Runnable { if (np?.playing != true) exitPlaying() }
    private val hideControls = Runnable { if (np?.playing == true) setControls(false) }
    private val hideLink = Runnable { setupLink.animate().alpha(0f).setDuration(500).start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(this)
        prefs = getSharedPreferences("afterglow", Context.MODE_PRIVATE)
        visMode = prefs.getInt("vis", 1)
        vinyl = prefs.getBoolean("vinyl", false)
        lyricsWanted = prefs.getBoolean("lyrics", false)
        MediaHub.autoOpen = prefs.getBoolean("auto", true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        root = FrameLayout(this)
        ambient = AmbientView(this)
        root.addView(ambient, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l; val h = b - t
            if (w > h && !built) { built = true; build(w, h); handleIntent(intent) }
        }
        hideBars()
    }

    private fun hideBars() {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { if (hasFocus) hideBars() }

    private fun mono(size: Float, alpha: Float = 1f, medium: Boolean = false) = TextView(this).apply {
        typeface = if (medium) Fonts.monoMedium else Fonts.mono
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u)
        setTextColor(PAPER)
        this.alpha = alpha
        letterSpacing = 0.11f
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    private fun place(parent: FrameLayout, v: View, x: Float, y: Float, w: Float, h: Float) {
        parent.addView(v, FrameLayout.LayoutParams(w.toInt(), h.toInt()).apply {
            leftMargin = x.toInt(); topMargin = y.toInt()
        })
    }

    private fun build(w: Int, h: Int) {
        W = w; H = h; u = h / 800f
        // A soft gradient loses nothing at half size, and the Adreno 504 gets 4x fewer pixels to shade.
        ambient.holder.setFixedSize(w / 2, h / 2)
        val artSide = 560f * u
        val artCx = 72f * u + artSide / 2
        val artCy = 414f * u
        ambient.artX = artCx / w
        ambient.artY = 1f - artCy / h
        ambient.setMood(mood, 1f)

        vis = VisualizerView(this, u, audio).apply {
            mode = visMode; this.artCx = artCx; this.artCy = artCy; artHalf = artSide / 2; alpha = 0f
        }
        root.addView(vis, FrameLayout.LayoutParams(-1, -1))

        val pad = 110f * u
        art = ArtView(this).apply {
            half = artSide / 2; vinylTarget = vinyl; alpha = 0f; visibility = View.INVISIBLE
        }
        place(root, art, artCx - artSide / 2 - pad, artCy - artSide / 2 - pad, artSide + 2 * pad, artSide + 2 * pad)

        val cx = 712f * u
        val cw = w - cx - 64f * u
        column = FrameLayout(this).apply { alpha = 0f; visibility = View.INVISIBLE; clipChildren = false }
        place(root, column, cx, 0f, cw, h.toFloat())

        label = mono(15f, 0.72f, true)
        place(column, label, 0f, 136f * u, cw, 24f * u)

        title = PosterTitle(this).apply {
            maxSize = 148f * u; minSize = 46f * u; maxH = 356f * u
            onHeight = { hh ->
                artist.animate().translationY(hh + 14f * u).setDuration(600).setInterpolator(EXPRESSIVE).start()
            }
        }
        place(column, title, 0f, 174f * u, cw, 372f * u)

        artist = mono(21f, 0.86f).apply { letterSpacing = 0.04f; translationY = 200f * u }
        place(column, artist, 0f, 174f * u, cw, 34f * u)

        lyrics = LyricsView(this, u).apply { visibility = View.INVISIBLE }
        place(column, lyrics, 0f, 170f * u, cw, 400f * u)

        progress = ProgressView(this, u).apply {
            onSeek = { f -> seek(f) }
            onScrub = { f -> np?.let { elapsed.text = fmtTime((f * it.duration).toLong()) } }
        }
        place(column, progress, 0f, 584f * u, cw, 40f * u)

        controls = FrameLayout(this).apply { clipChildren = false }
        place(column, controls, 0f, 620f * u, cw, 104f * u)

        elapsed = mono(14f, 0.72f)
        place(controls, elapsed, 0f, 0f, 120f * u, 20f * u)
        total = mono(14f, 0.72f).apply { gravity = Gravity.END }
        place(controls, total, cw - 120f * u, 0f, 120f * u, 20f * u)

        val prevBtn = IconButton(this, IconButton.Kind.PREV, u).apply { setOnClickListener { prev(); poke() } }
        playBtn = IconButton(this, IconButton.Kind.PLAY, u).apply { setOnClickListener { playPause(); poke() } }
        val nextBtn = IconButton(this, IconButton.Kind.NEXT, u).apply { setOnClickListener { next(); poke() } }
        place(controls, prevBtn, -10f * u, 26f * u, 72f * u, 76f * u)
        place(controls, playBtn, 60f * u, 26f * u, 80f * u, 76f * u)
        place(controls, nextBtn, 140f * u, 26f * u, 72f * u, 76f * u)

        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        place(controls, row, cw - 300f * u, 26f * u, 300f * u, 76f * u)
        fun toggle() = mono(13f, 0.82f, true).apply {
            gravity = Gravity.CENTER
            setPadding((11 * u).toInt(), 0, (11 * u).toInt(), 0)
        }.also { row.addView(it, LinearLayout.LayoutParams(-2, -1)) }
        tArt = toggle().apply { setOnClickListener { toggleVinyl(); poke() } }
        tVis = toggle().apply { setOnClickListener { cycleVis(); poke() } }
        tLyr = toggle().apply { setOnClickListener { toggleLyrics(); poke() } }
        refreshToggles()

        dateTop = mono(14f, 0f)
        place(root, dateTop, 72f * u, 28f * u, 300f * u, 22f * u)
        batteryTop = mono(14f, 0f).apply { gravity = Gravity.END }
        place(root, batteryTop, w - 364f * u, 28f * u, 300f * u, 22f * u)

        clock = ClockView(this)
        root.addView(clock, FrameLayout.LayoutParams(-1, -1))

        idleDate = mono(21f, 0.82f, false).apply { gravity = Gravity.CENTER; letterSpacing = 0.34f }
        place(root, idleDate, 0f, 662f * u, w.toFloat(), 32f * u)
        resume = mono(15f, 0f, true).apply { gravity = Gravity.CENTER; setOnClickListener { playPause() } }
        place(root, resume, w * 0.18f, 712f * u, w * 0.64f, 52f * u)
        setupLink = mono(13f, 0f, true).apply {
            text = "SETUP"; gravity = Gravity.CENTER
            setOnClickListener { if (it.alpha > 0.05f) showSetup() }
        }
        place(root, setupLink, w - 130f * u, h - 74f * u, 110f * u, 56f * u)

        updateClock(true)
        if (MediaHub.hasAccess(this)) {
            MediaHub.listener = { onMedia(it) }
            MediaHub.start(applicationContext)
        } else {
            main.postDelayed({ if (demo == null) showSetup() }, 500)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (built) handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.hasExtra("visual")) { visMode = i.getIntExtra("visual", 1); vis.mode = visMode; refreshToggles() }
        if (i.hasExtra("vinyl")) { vinyl = i.getBooleanExtra("vinyl", false); art.vinylTarget = vinyl; refreshToggles() }
        if (i.getBooleanExtra("preview", false)) { dismissSetup(); startDemo() }
    }

    // ---- media ----------------------------------------------------------------------------

    private fun onMedia(n: NowPlaying?) {
        if (demo != null) {
            if (n?.playing == true) stopDemo() else return
        }
        handle(n)
    }

    private fun startDemo() {
        demo = Demo { handle(it) }.also { it.publish() }
    }

    private fun stopDemo() {
        demo = null
        audioOn = false
        handle(MediaHub.current)
    }

    private fun sig(b: Bitmap?): Int {
        if (b == null || b.config == Bitmap.Config.HARDWARE) return 0
        var s = b.width * 31 + b.height
        for (i in 1..9) for (j in 1..9) s = s * 31 + b.getPixel(b.width * i / 10, b.height * j / 10)
        return s
    }

    private fun handle(n: NowPlaying?) {
        np = n
        if (n == null) {
            main.removeCallbacks(goIdle)
            loadedKey = ""
            if (mode == Mode.PLAYING) exitPlaying()
            setResume(null)
            syncAudio()
            return
        }
        val s = sig(n.art)
        if (n.key != loadedKey || s != loadedSig) {
            val changedTrack = n.key != loadedKey
            loadedKey = n.key
            loadedSig = s
            worker.execute {
                val src = n.art ?: ArtAnalysis.placeholder(n.title)
                val a = ArtAnalysis.process(src)
                main.post { if (loadedKey == n.key) apply(n, a, changedTrack) }
            }
        } else sync(n)
    }

    private fun apply(n: NowPlaying, a: Analyzed, changedTrack: Boolean) {
        art.setArt(a.square, a.glow, mode == Mode.PLAYING && changedTrack)
        art.setAccent(a.mood.accent)
        mood = a.mood
        vis.setColors(a.mood.accent, a.mood.accent2)
        progress.setAccent(a.mood.accent)
        if (mode == Mode.PLAYING) ambient.setMood(a.mood, 0.65f)
        if (changedTrack) {
            title.setTitle(n.title)
            artist.text = n.artist
            total.text = if (n.duration > 0) fmtTime(n.duration) else ""
            updateLabel()
            loadLyrics(n)
        }
        sync(np ?: n)
    }

    private fun sync(n: NowPlaying) {
        playBtn.setPlaying(n.playing)
        if (n.playing) {
            main.removeCallbacks(goIdle)
            setResume(null)
            if (mode != Mode.PLAYING) enterPlaying()
            if (!controlsShown) { /* stay clean while playing */ } else scheduleHide()
        } else {
            main.removeCallbacks(hideControls)
            if (mode == Mode.PLAYING) {
                setControls(true)
                main.removeCallbacks(goIdle)
                main.postDelayed(goIdle, 45_000)
            } else setResume(n)
        }
        syncAudio()
    }

    private fun setResume(n: NowPlaying?) {
        if (!built) return
        if (n == null) { resume.animate().alpha(0f).setDuration(300).start(); return }
        resume.text = "▶   RESUME   ${n.title.uppercase()}"
        resume.animate().alpha(0.9f).setDuration(600).start()
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun syncAudio() {
        val want = np?.playing == true && running
        val m = if (demo == null && hasMic()) 2 else 1
        if (want && (!audioOn || audioMode != m)) {
            audio.start(m == 2)
            audioOn = true
            audioMode = m
        } else if (!want && audioOn) {
            audio.stop()
            audioOn = false
        }
    }

    private fun playPause() {
        val d = demo
        if (d != null) { d.toggle(); return }
        if (np?.playing == true) MediaHub.pause() else MediaHub.play()
    }
    private fun next() { demo?.next() ?: MediaHub.next() }
    private fun prev() { demo?.prev() ?: MediaHub.previous() }
    private fun seek(f: Float) {
        val n = np ?: return
        val ms = (f * n.duration).toLong()
        demo?.seek(ms) ?: MediaHub.seek(ms)
    }

    // ---- choreography ---------------------------------------------------------------------

    private fun enterPlaying() {
        if (!built || mode == Mode.PLAYING) return
        mode = Mode.PLAYING
        ambient.setMood(mood, 0.75f)
        val topY = 39f * u
        val mini = 21f * u / clock.capH
        clock.animate().translationY(topY - clock.centerY).scaleX(mini).scaleY(mini)
            .setDuration(1150).setInterpolator(EXPRESSIVE).start()
        idleDate.animate().alpha(0f).translationY(24f * u).setDuration(350).start()
        setupLink.animate().alpha(0f).setDuration(200).start()

        art.visibility = View.VISIBLE
        art.alpha = 0f; art.translationY = 190f * u; art.scaleX = 0.8f; art.scaleY = 0.8f
        art.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setStartDelay(160).setDuration(1250).setInterpolator(EXPRESSIVE).start()

        column.visibility = View.VISIBLE
        column.alpha = 0f; column.translationX = 56f * u
        column.animate().alpha(1f).translationX(0f)
            .setStartDelay(340).setDuration(1100).setInterpolator(EXPRESSIVE).start()
        title.replay(0.34f)

        vis.animate().alpha(1f).setStartDelay(800).setDuration(900).start()
        dateTop.animate().alpha(0.68f).setStartDelay(820).setDuration(600).start()
        batteryTop.animate().alpha(0.68f).setStartDelay(820).setDuration(600).start()
        setControls(true)
        applyBrightness()
    }

    private fun exitPlaying() {
        if (!built || mode == Mode.IDLE) return
        mode = Mode.IDLE
        mood = ArtAnalysis.moodForNow()
        ambient.setMood(mood, 1.2f)
        clock.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(1100).setInterpolator(EXPRESSIVE).start()
        idleDate.animate().alpha(0.82f).translationY(0f).setStartDelay(350).setDuration(700).start()
        art.animate().alpha(0f).translationY(120f * u).scaleX(0.9f).scaleY(0.9f)
            .setStartDelay(0).setDuration(520).setInterpolator(SOFT_IN_OUT)
            .withEndAction { if (mode == Mode.IDLE) art.visibility = View.INVISIBLE }.start()
        column.animate().alpha(0f).translationX(40f * u).setStartDelay(0).setDuration(420)
            .withEndAction { if (mode == Mode.IDLE) column.visibility = View.INVISIBLE }.start()
        vis.animate().alpha(0f).setStartDelay(0).setDuration(380).start()
        dateTop.animate().alpha(0f).setStartDelay(0).setDuration(300).start()
        batteryTop.animate().alpha(0f).setStartDelay(0).setDuration(300).start()
        applyBrightness()
        np?.let { if (!it.playing) setResume(it) }
    }

    private fun setControls(show: Boolean) {
        if (!built) return
        controlsShown = show
        controls.animate().alpha(if (show) 1f else 0f).translationY(if (show) 0f else 14f * u)
            .setDuration(if (show) 340 else 700).setInterpolator(EXPRESSIVE)
            .withEndAction { controls.visibility = if (controlsShown) View.VISIBLE else View.INVISIBLE }.start()
        if (show) controls.visibility = View.VISIBLE
        if (show) scheduleHide()
    }

    private fun scheduleHide() {
        main.removeCallbacks(hideControls)
        if (np?.playing == true) main.postDelayed(hideControls, 6000)
    }

    private fun poke() = setControls(true)

    private fun updateLabel() {
        val n = np ?: return
        val showingLyrics = lyricsWanted && lyricLines.isNotEmpty()
        label.text = if (showingLyrics) "${n.title.uppercase()}  ·  ${n.artist.uppercase()}" else "NOW PLAYING  ·  ${n.source.uppercase()}"
    }

    // ---- switches -------------------------------------------------------------------------

    private fun refreshToggles() {
        tArt.text = if (vinyl) "VINYL" else "COVER"
        tVis.text = VisualizerView.NAMES[visMode]
        tLyr.text = "LYRICS"
        tLyr.alpha = if (lyricsWanted) 1f else 0.42f
    }

    private fun toggleVinyl() {
        vinyl = !vinyl
        art.vinylTarget = vinyl
        prefs.edit().putBoolean("vinyl", vinyl).apply()
        refreshToggles()
    }

    private fun cycleVis() {
        visMode = (visMode + 1) % VisualizerView.NAMES.size
        vis.mode = visMode
        prefs.edit().putInt("vis", visMode).apply()
        refreshToggles()
    }

    private fun toggleLyrics() {
        lyricsWanted = !lyricsWanted
        prefs.edit().putBoolean("lyrics", lyricsWanted).apply()
        refreshToggles()
        if (lyricsWanted) np?.let { loadLyrics(it) } else applyLyrics(emptyList())
    }

    private fun loadLyrics(n: NowPlaying) {
        applyLyrics(emptyList())
        if (!lyricsWanted || n.pkg == "preview") return
        val key = n.key
        Lyrics.fetch(n.title, n.artist, n.album, n.duration) { lines ->
            main.post {
                if (np?.key == key && lyricsWanted) {
                    applyLyrics(lines)
                    if (lines.isEmpty()) label.text = "NO SYNCED LYRICS FOR THIS SONG"
                }
            }
        }
    }

    private fun applyLyrics(lines: List<LyricLine>) {
        lyricLines = lines
        lyrics.setLines(lines)
        val on = lines.isNotEmpty()
        lyrics.visibility = if (on) View.VISIBLE else View.INVISIBLE
        title.visibility = if (on) View.INVISIBLE else View.VISIBLE
        artist.visibility = if (on) View.INVISIBLE else View.VISIBLE
        updateLabel()
    }

    private fun applyBrightness() {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val night = prefs.getBoolean("night", true) && mode == Mode.IDLE && (h >= 23 || h < 6)
        window.attributes = window.attributes.apply { screenBrightness = if (night) 0.05f else -1f }
    }

    // ---- frame loop -----------------------------------------------------------------------

    override fun doFrame(nanos: Long) {
        if (!running) return
        Choreographer.getInstance().postFrameCallback(this)
        if (!built) return
        val dt = if (last == 0L) 0.016f else ((nanos - last) / 1e9f).coerceIn(0.001f, 0.1f)
        last = nanos
        frame++
        val n = np
        val playing = n?.playing == true
        val inPlay = mode == Mode.PLAYING

        audio.tick(dt, playing)
        dimNow = smooth(dimNow, if (!inPlay) 0.92f else if (playing) 1f else 0.6f, dt, 0.5f)
        motionNow = smooth(motionNow, if (!inPlay) 0.16f else if (playing) 0.55f else 0.1f, dt, 0.6f)
        motesNow = smooth(motesNow, if (inPlay && playing) 1f else 0.3f, dt, 0.8f)
        ambient.energy = audio.energy
        ambient.beat = audio.beat
        ambient.dim = dimNow
        ambient.motion = motionNow
        ambient.motes = motesNow
        // The clock barely moves, so idle runs the GPU at a third of the rate to stay cool on a stand.
        val due = inPlay || frame % 3 == 0
        if (due) ambient.requestRender()

        clock.tick(dt, frame % 3 == 0)
        updateClock(false)

        if (inPlay && n != null) {
            val pos = n.positionNow()
            val f = if (n.duration > 0) (pos.toFloat() / n.duration).coerceIn(0f, 1f) else 0f
            art.tick(dt, playing, audio.beat, f)
            vis.morph = art.morph
            vis.tick(dt)
            title.tick(dt)
            progress.setFraction(f, dt)
            if (!progress.dragging && frame % 10 == 0) elapsed.text = fmtTime(pos)
            if (lyricLines.isNotEmpty()) lyrics.tick(dt, pos)
        }
    }

    private fun updateClock(force: Boolean) {
        if (!force && frame % 20 != 0) return
        val cal = Calendar.getInstance()
        val m = cal.get(Calendar.MINUTE)
        val pattern = if (DateFormat.is24HourFormat(this)) "H:mm" else "h:mm"
        clock.setTime(SimpleDateFormat(pattern, Locale.US).format(Date()))
        if (m != lastMinute || force) {
            lastMinute = m
            val now = Date()
            dateTop.text = SimpleDateFormat("EEE dd MMM", Locale.US).format(now).uppercase()
            val alarm = getSystemService(AlarmManager::class.java).nextAlarmClock
            val base = SimpleDateFormat("EEEE dd MMMM", Locale.US).format(now).uppercase()
            idleDate.text = if (alarm != null && alarm.triggerTime - System.currentTimeMillis() < 86_400_000) {
                base + "   ·   ALARM " + SimpleDateFormat(pattern, Locale.US).format(Date(alarm.triggerTime))
            } else base
            val bat = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            batteryTop.text = if (level >= 0) "$level%" + if (plugged) " +" else "" else ""
            if (mode == Mode.IDLE) { mood = ArtAnalysis.moodForNow(); ambient.setMood(mood, 3f) }
            applyBrightness()
        }
    }

    // ---- gestures -------------------------------------------------------------------------

    private var gestureArmed = true
    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (mode == Mode.PLAYING) setControls(!controlsShown) else showSetupLink()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (mode == Mode.PLAYING) playPause()
                return true
            }
            override fun onLongPress(e: MotionEvent) { showSetup() }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (mode != Mode.PLAYING || e1 == null) return false
                val dx = e2.x - e1.x
                if (abs(dx) > 120f * u && abs(vx) > abs(vy) * 1.4f) {
                    if (dx < 0) next() else prev()
                    return true
                }
                return false
            }
        })
    }

    private fun showSetupLink() {
        setupLink.animate().alpha(0.75f).setDuration(300).start()
        main.removeCallbacks(hideLink)
        main.postDelayed(hideLink, 5000)
    }

    private fun hit(v: View, x: Float, y: Float): Boolean {
        if (v.visibility != View.VISIBLE || v.alpha < 0.1f) return false
        val r = Rect()
        v.getGlobalVisibleRect(r)
        return r.contains(x.toInt(), y.toInt())
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (built) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                gestureArmed = setup == null && !hit(progress, ev.rawX, ev.rawY) && !hit(controls, ev.rawX, ev.rawY) &&
                    !hit(resume, ev.rawX, ev.rawY) && !hit(setupLink, ev.rawX, ev.rawY)
            }
            if (gestureArmed) gestures.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    // ---- setup panel ----------------------------------------------------------------------

    @SuppressLint("SetTextI18n")
    private fun showSetup() {
        if (setup != null) return
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0xF20B0A09.toInt())
            alpha = 0f
            isClickable = true
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val big = TextView(this).apply {
            typeface = Fonts.display
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 58f * u)
            setTextColor(PAPER)
            text = "SETUP"
        }
        box.addView(big, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (18 * u).toInt() })

        fun row(name: String, body: String, state: () -> String, act: () -> Unit) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (16 * u).toInt(), 0, (16 * u).toInt())
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                typeface = Fonts.displayLight
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 30f * u)
                setTextColor(PAPER)
                text = name
            })
            col.addView(mono(14f, 0.62f).apply {
                isSingleLine = false; letterSpacing = 0.02f; text = body
                setLineSpacing(4f * u, 1f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (5 * u).toInt() })
            r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            val b = mono(14f, 1f, true).apply {
                text = state(); tag = state
                gravity = Gravity.CENTER or Gravity.END
                setOnClickListener { act(); text = state() }
                setPadding((24 * u).toInt(), (16 * u).toInt(), 0, (16 * u).toInt())
            }
            r.addView(b, LinearLayout.LayoutParams((190 * u).toInt(), -2))
            box.addView(r)
            box.addView(View(this).apply { setBackgroundColor(0x24F4EFE6) }, LinearLayout.LayoutParams(-1, 1))
        }

        row("SONG INFO AND CONTROLS", "Android calls this Notification access. It lets the display read the song and press play.",
            { if (MediaHub.hasAccess(this)) "ON" else "TURN ON  →" }) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        row("REACT TO THE MUSIC", "Android files this under Microphone. Only the tablet's own sound output is read, the mic stays off.",
            { if (hasMic()) "ON" else "TURN ON  →" }) {
            if (!hasMic()) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
        }
        row("OPEN WHEN MUSIC STARTS", "Needs Display over other apps. Brings this screen back 3 seconds after a music app starts playing.",
            { if (!Settings.canDrawOverlays(this)) "TURN ON  →" else if (MediaHub.autoOpen) "ON" else "OFF" }) {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                MediaHub.autoOpen = !MediaHub.autoOpen
                prefs.edit().putBoolean("auto", MediaHub.autoOpen).apply()
            }
        }
        row("NIGHT DIM", "Lowers this screen's brightness on the clock from 11 PM to 6 AM.",
            { if (prefs.getBoolean("night", true)) "ON" else "OFF" }) {
            prefs.edit().putBoolean("night", !prefs.getBoolean("night", true)).apply()
            applyBrightness()
        }

        val foot = LinearLayout(this).apply { gravity = Gravity.END }
        val preview = mono(15f, 0.82f, true).apply {
            text = "PREVIEW"; setPadding((30 * u).toInt(), (20 * u).toInt(), (30 * u).toInt(), (20 * u).toInt())
            setOnClickListener { dismissSetup(); startDemo() }
        }
        val close = mono(15f, 1f, true).apply {
            text = "CLOSE"; setPadding((30 * u).toInt(), (20 * u).toInt(), 0, (20 * u).toInt())
            setOnClickListener { dismissSetup() }
        }
        foot.addView(preview); foot.addView(close)
        box.addView(foot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * u).toInt() })

        scrim.addView(box, FrameLayout.LayoutParams((880 * u).toInt(), -2, Gravity.CENTER))
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        scrim.animate().alpha(1f).setDuration(320).start()
        setup = scrim
    }

    private fun dismissSetup() {
        val s = setup ?: return
        setup = null
        s.animate().alpha(0f).setDuration(240).withEndAction { root.removeView(s) }.start()
    }

    private fun refreshSetup() {
        val s = setup ?: return
        fun walk(v: View) {
            if (v is TextView && v.tag is Function0<*>) v.text = (v.tag as Function0<*>).invoke() as String
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(s)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        refreshSetup()
        audioOn = false
        syncAudio()
    }

    // ---- lifecycle ------------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        hideBars()
        MediaHub.activityVisible = true
        ambient.onResume()
        running = true
        last = 0
        Choreographer.getInstance().postFrameCallback(this)
        if (built) {
            if (MediaHub.hasAccess(this)) {
                MediaHub.listener = { onMedia(it) }
                MediaHub.start(applicationContext)
            }
            refreshSetup()
            updateClock(true)
            syncAudio()
        }
    }

    override fun onPause() {
        super.onPause()
        MediaHub.activityVisible = false
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        ambient.onPause()
        audio.stop()
        audioOn = false
    }

    override fun onDestroy() {
        super.onDestroy()
        MediaHub.listener = null
        main.removeCallbacksAndMessages(null)
        worker.shutdown()
    }
}
