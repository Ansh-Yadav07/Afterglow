package com.anshyadav.afterglow

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService

/** Android only hands out other apps' media sessions to an enabled notification listener. */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        MediaHub.start(applicationContext)
    }
}

data class NowPlaying(
    val pkg: String,
    val title: String,
    val artist: String,
    val album: String,
    val art: Bitmap?,
    val duration: Long,
    val position: Long,
    val updateTime: Long,
    val speed: Float,
    val playing: Boolean,
) {
    val key get() = "$pkg|$title|$artist"
    val source get() = appName(pkg)

    fun positionNow(): Long {
        if (!playing) return position
        val p = position + ((SystemClock.elapsedRealtime() - updateTime) * speed).toLong()
        return if (duration > 0) p.coerceIn(0, duration) else p.coerceAtLeast(0)
    }
}

fun appName(pkg: String) = when (pkg) {
    "com.spotify.music" -> "Spotify"
    "com.google.android.apps.youtube.music" -> "YouTube Music"
    "com.google.android.youtube" -> "YouTube"
    "com.jio.media.jiobeats" -> "JioSaavn"
    "com.gaana" -> "Gaana"
    "com.bsbportal.music" -> "Wynk"
    "com.soundcloud.android" -> "SoundCloud"
    "com.apple.android.music" -> "Apple Music"
    "com.amazon.mp3" -> "Amazon Music"
    "com.sec.android.app.music" -> "Samsung Music"
    "preview" -> "Preview"
    else -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
}

/** Music apps only: a YouTube video or a game must never get pulled off the screen. */
private val AUTO_OPEN = setOf(
    "com.spotify.music", "com.google.android.apps.youtube.music", "com.jio.media.jiobeats", "com.gaana",
    "com.bsbportal.music", "com.soundcloud.android", "com.apple.android.music", "com.amazon.mp3",
    "com.sec.android.app.music", "com.maxmpz.audioplayer",
)

object Titles {
    private val noise = Regex(
        """\s*[(\[][^)\]]*\b(official|lyrics?|video|audio|from|visuali[sz]er|remaster(ed)?|hd|4k|full song)\b[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )

    fun clean(pkg: String, rawTitle: String, rawArtist: String): Pair<String, String> {
        var title = rawTitle.replace(noise, "").substringBefore(" | ").trim().ifEmpty { rawTitle }
        var artist = rawArtist.removeSuffix(" - Topic").removeSuffix("VEVO").trim()
        if (pkg == "com.google.android.youtube" && title.contains(" - ")) {
            artist = title.substringBefore(" - ").trim()
            title = title.substringAfter(" - ").trim()
        }
        return title to artist
    }
}

object MediaHub : MediaSessionManager.OnActiveSessionsChangedListener {
    private val main = Handler(Looper.getMainLooper())
    private var ctx: Context? = null
    private var started = false
    private val callbacks = HashMap<MediaController, MediaController.Callback>()
    private var active: MediaController? = null
    private var wasPlaying = false
    private var openTask: Runnable? = null

    var current: NowPlaying? = null; private set
    var listener: ((NowPlaying?) -> Unit)? = null
    var activityVisible = false
    var autoOpen = true

    fun hasAccess(c: Context): Boolean {
        val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(c.packageName)
    }

    fun start(c: Context) {
        ctx = c.applicationContext
        if (started) { publish(); return }
        val msm = c.getSystemService(MediaSessionManager::class.java)
        val cn = ComponentName(c, MediaListenerService::class.java)
        try {
            msm.addOnActiveSessionsChangedListener(this, cn, main)
            started = true
            onActiveSessionsChanged(msm.getActiveSessions(cn))
        } catch (_: SecurityException) {
            started = false
        }
    }

    override fun onActiveSessionsChanged(list: MutableList<MediaController>?) {
        val next = list.orEmpty()
        val nextTokens = next.map { it.sessionToken }
        callbacks.entries.removeAll { (mc, cb) ->
            val gone = mc.sessionToken !in nextTokens
            if (gone) mc.unregisterCallback(cb)
            gone
        }
        for (mc in next) {
            if (callbacks.keys.none { it.sessionToken == mc.sessionToken }) {
                val cb = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) = schedule()
                    override fun onPlaybackStateChanged(state: PlaybackState?) = schedule()
                    override fun onSessionDestroyed() = schedule()
                }
                mc.registerCallback(cb, main)
                callbacks[mc] = cb
            }
        }
        schedule()
    }

    // Apps often send the title and the artwork in separate updates; wait for both.
    private val publishTask = Runnable { publish() }
    private fun schedule() {
        main.removeCallbacks(publishTask)
        main.postDelayed(publishTask, 140)
    }

    private fun isPlaying(s: PlaybackState?) = when (s?.state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM, PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING -> true
        else -> false
    }

    private fun pick(): MediaController? {
        val usable = callbacks.keys.filter { !it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank() }
        return usable.firstOrNull { isPlaying(it.playbackState) }
            ?: usable.firstOrNull { it.sessionToken == active?.sessionToken }
            ?: usable.firstOrNull()
    }

    private fun publish() {
        val mc = pick()
        active = mc
        val m = mc?.metadata
        val s = mc?.playbackState
        current = if (mc == null || m == null) null else {
            val (title, artist) = Titles.clean(
                mc.packageName,
                m.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                m.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "",
            )
            NowPlaying(
                pkg = mc.packageName,
                title = title,
                artist = artist,
                album = m.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
                art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
                duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION),
                position = s?.position ?: 0L,
                updateTime = s?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
                speed = s?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
                playing = isPlaying(s),
            )
        }
        val playing = current?.playing == true
        if (playing && !wasPlaying) maybeOpen()
        wasPlaying = playing
        listener?.invoke(current)
    }

    /** After a short beat, come back to the front when a music app starts playing. */
    private fun maybeOpen() {
        val c = ctx ?: return
        openTask?.let { main.removeCallbacks(it) }
        val task = Runnable {
            val np = current
            if (autoOpen && !activityVisible && np?.playing == true && np.pkg in AUTO_OPEN && Settings.canDrawOverlays(c)) {
                c.startActivity(
                    Intent(c, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            }
        }
        openTask = task
        main.postDelayed(task, 3000)
    }

    private val tc get() = active?.transportControls
    fun play() { tc?.play() }
    fun pause() { tc?.pause() }
    fun next() { tc?.skipToNext() }
    fun previous() { tc?.skipToPrevious() }
    fun seek(ms: Long) { tc?.seekTo(ms) }
}
