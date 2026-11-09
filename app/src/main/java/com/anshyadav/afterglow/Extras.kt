package com.anshyadav.afterglow

import android.graphics.*
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** Synced lyrics from lrclib.net, a free community database. Only used when the Lyrics switch is on. */
object Lyrics {
    private val pool = Executors.newSingleThreadExecutor()
    private val cache = object : LinkedHashMap<String, List<LyricLine>>(16, .75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<LyricLine>>?) = size > 40
    }
    private val lrc = Regex("""\[(\d+):(\d+(?:\.\d+)?)]\s*(.*)""")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 6000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Afterglow/1.0 (Android display app)")
            if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null
        } catch (_: Exception) { null } finally { c.disconnect() }
    }

    private fun parse(text: String): List<LyricLine> = text.lines().mapNotNull { l ->
        lrc.find(l)?.let { m ->
            LyricLine((m.groupValues[1].toLong() * 60000 + m.groupValues[2].toDouble() * 1000).toLong(), m.groupValues[3].trim())
        }
    }.sortedBy { it.timeMs }

    fun fetch(title: String, artist: String, album: String, durationMs: Long, done: (List<LyricLine>) -> Unit) {
        val key = "$title|$artist"
        cache[key]?.let { done(it); return }
        pool.execute {
            val who = artist.split(",", "&").first().trim()
            var synced: String? = get(
                "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(who)}" +
                    "&album_name=${enc(album)}&duration=${durationMs / 1000}",
            )?.let { JSONObject(it).optString("syncedLyrics").ifBlank { null } }
            if (synced == null) {
                val found = get("https://lrclib.net/api/search?track_name=${enc(title)}&artist_name=${enc(who)}")
                if (found != null) {
                    val arr = JSONArray(found)
                    for (i in 0 until arr.length()) {
                        val s = arr.getJSONObject(i).optString("syncedLyrics")
                        if (s.isNotBlank() && s != "null") { synced = s; break }
                    }
                }
            }
            val out = synced?.let { parse(it) }.orEmpty()
            cache[key] = out
            done(out)
        }
    }
}

/** Built-in fake playlist, so the display can be previewed without any music app. */
class Demo(private val emit: (NowPlaying) -> Unit) {
    private class T(val title: String, val artist: String, val art: Bitmap, val dur: Long)

    private val tracks = listOf(
        T("Everything Is Orange After Seven", "The Late Trains", art1(), 214_000),
        T("Kite Season", "Harbour Lights", art2(), 187_000),
        T("Salt", "Ila & The Monsoon", art3(), 243_000),
    )
    private var i = 0
    private var pos = 64_000L
    private var at = SystemClock.elapsedRealtime()
    private var playing = true

    fun publish() {
        val t = tracks[i]
        emit(NowPlaying("preview", t.title, t.artist, "Preview", t.art, t.dur, pos, at, 1f, playing))
    }

    private fun mark() {
        if (playing) pos += SystemClock.elapsedRealtime() - at
        at = SystemClock.elapsedRealtime()
    }

    fun toggle() { mark(); playing = !playing; publish() }
    fun next() { i = (i + 1) % tracks.size; pos = 0; at = SystemClock.elapsedRealtime(); playing = true; publish() }
    fun prev() { i = (i + tracks.size - 1) % tracks.size; pos = 0; at = SystemClock.elapsedRealtime(); playing = true; publish() }
    fun seek(ms: Long) { pos = ms; at = SystemClock.elapsedRealtime(); publish() }

    private fun canvas(): Pair<Bitmap, Canvas> {
        val b = Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888)
        return b to Canvas(b)
    }

    private fun art1(): Bitmap {
        val (b, c) = canvas()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(0xFFE8451C.toInt())
        p.color = 0xFFF6C445.toInt(); c.drawCircle(360f, 300f, 208f, p)
        p.color = 0xFF2B0F0B.toInt(); c.drawRect(0f, 430f, 720f, 720f, p)
        p.color = 0xFFE8451C.toInt()
        for (k in 0..6) c.drawRect(120f + k * 12f, 470f + k * 34f, 600f - k * 12f, 480f + k * 34f, p)
        return b
    }

    private fun art2(): Bitmap {
        val (b, c) = canvas()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(0xFF19443F.toInt())
        val path = Path()
        p.color = 0xFFF2E2B8.toInt()
        path.moveTo(360f, 70f); path.lineTo(590f, 330f); path.lineTo(360f, 600f); path.lineTo(130f, 330f); path.close()
        c.drawPath(path, p)
        p.color = 0xFFEF7B45.toInt()
        path.reset(); path.moveTo(360f, 70f); path.lineTo(590f, 330f); path.lineTo(360f, 330f); path.close()
        c.drawPath(path, p)
        path.reset(); path.moveTo(360f, 600f); path.lineTo(130f, 330f); path.lineTo(360f, 330f); path.close()
        c.drawPath(path, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = 0xFFF2E2B8.toInt()
        path.reset(); path.moveTo(360f, 600f); path.cubicTo(420f, 650f, 300f, 680f, 360f, 730f)
        c.drawPath(path, p)
        return b
    }

    private fun art3(): Bitmap {
        val (b, c) = canvas()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(0xFFEDE6D6.toInt())
        p.color = 0xFF8C1C2C.toInt(); c.drawCircle(250f, 420f, 190f, p)
        p.color = 0xFF171514.toInt(); c.drawRect(380f, 90f, 460f, 630f, p)
        p.color = 0xFFD99A2B.toInt(); c.drawCircle(540f, 210f, 96f, p)
        return b
    }
}
