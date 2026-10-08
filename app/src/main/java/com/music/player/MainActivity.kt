package com.music.player

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Base64
import android.util.LruCache
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream

private const val HOST = "music.local"

/** Limita un stream a "left" bytes (para responder peticiones Range del audio). */
private class LimitedStream(inp: InputStream, private var left: Long) : FilterInputStream(inp) {
    override fun read(): Int {
        if (left <= 0) return -1
        val r = super.read()
        if (r >= 0) left--
        return r
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val r = super.read(b, off, minOf(len.toLong(), left).toInt())
        if (r > 0) left -= r.toLong()
        return r
    }
}

class MainActivity : Activity() {

    companion object {
        @Volatile
        var instance: MainActivity? = null
    }

    private lateinit var web: WebView
    private val coverCache = LruCache<Long, ByteArray>(40)
    private var lastCoverStr: String = ""
    private var lastCoverBmp: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#171214"))
        setContentView(web)

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.allowContentAccess = false

        web.addJavascriptInterface(Bridge(), "MusicBridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? = handle(request)
        }
        web.loadUrl("https://$HOST/index.html")

        askPermissions()
    }

    /** Al pulsar "atrás" la app pasa a segundo plano y la música sigue sonando. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        if (isFinishing) {
            instance = null
            PlaybackService.stop(this)
            web.destroy()
        }
        super.onDestroy()
    }

    fun js(code: String) {
        web.post { web.evaluateJavascript(code, null) }
    }

    // ───────── Permisos ─────────

    private fun audioGranted(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun askPermissions() {
        val need = mutableListOf<String>()
        if (!audioGranted()) {
            need.add(
                if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                else Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        js("window.onLibraryReady && window.onLibraryReady()")
    }

    // ───────── Servidor interno: página, audio y portadas ─────────

    private fun notFound() = WebResourceResponse(
        "text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0))
    )

    private fun handle(req: WebResourceRequest?): WebResourceResponse? {
        val u = req?.url ?: return null
        if (u.host != HOST) return null
        val path = u.path ?: "/"
        return try {
            when {
                path == "/" || path == "/index.html" -> {
                    val r = WebResourceResponse("text/html", "utf-8", assets.open("index.html"))
                    r.responseHeaders = mapOf("Cache-Control" to "no-cache")
                    r
                }
                path.startsWith("/a/") -> audio(u, req.requestHeaders)
                path.startsWith("/c/") -> cover(u)
                else -> notFound()
            }
        } catch (e: Exception) {
            notFound()
        }
    }

    private fun trackUri(u: Uri): Uri? {
        val id = u.lastPathSegment?.toLongOrNull() ?: return null
        return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    }

    private fun audio(u: Uri, headers: Map<String, String>?): WebResourceResponse {
        val uri = trackUri(u) ?: return notFound()
        val pfd: ParcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r") ?: return notFound()
        val total = pfd.statSize
        val mime = contentResolver.getType(uri) ?: "audio/mpeg"
        val input = ParcelFileDescriptor.AutoCloseInputStream(pfd)

        val range = headers?.entries?.firstOrNull { it.key.equals("range", ignoreCase = true) }?.value
        var start = 0L
        var end = total - 1
        var partial = false
        if (range != null && range.startsWith("bytes=")) {
            val parts = range.removePrefix("bytes=").split("-")
            parts.getOrNull(0)?.toLongOrNull()?.let { start = it }
            parts.getOrNull(1)?.toLongOrNull()?.let { end = minOf(it, total - 1) }
            partial = true
        }
        if (start > total) start = total
        val len = maxOf(0L, end - start + 1)
        if (start > 0) (input as FileInputStream).channel.position(start)

        val h = HashMap<String, String>()
        h["Accept-Ranges"] = "bytes"
        h["Content-Length"] = len.toString()
        h["Access-Control-Allow-Origin"] = "*"
        if (partial) h["Content-Range"] = "bytes $start-$end/$total"

        return WebResourceResponse(
            mime, null,
            if (partial) 206 else 200,
            if (partial) "Partial Content" else "OK",
            h, LimitedStream(input, len)
        )
    }

    private fun cover(u: Uri): WebResourceResponse {
        val id = u.lastPathSegment?.toLongOrNull() ?: return notFound()
        val cached = coverCache.get(id)
        val bytes: ByteArray = cached ?: loadCover(id).also { coverCache.put(id, it) }
        if (bytes.isEmpty()) return notFound()
        val h = mapOf("Cache-Control" to "max-age=86400", "Access-Control-Allow-Origin" to "*")
        return WebResourceResponse("image/jpeg", null, 200, "OK", h, ByteArrayInputStream(bytes))
    }

    /** Lee la imagen incrustada en el archivo de audio y la reduce a máx. ~800 px. */
    private fun loadCover(id: Long): ByteArray {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        val mmr = MediaMetadataRetriever()
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { mmr.setDataSource(it.fileDescriptor) }
                ?: return ByteArray(0)
            val raw = mmr.embeddedPicture ?: return ByteArray(0)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 800) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return ByteArray(0)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
            bmp.recycle()
            return out.toByteArray()
        } catch (e: Exception) {
            return ByteArray(0)
        } finally {
            try {
                mmr.release()
            } catch (e: Exception) {
            }
        }
    }

    // ───────── Puente JavaScript ─────────

    inner class Bridge {

        @JavascriptInterface
        fun getLibrary(): String {
            val tracks = JSONArray()
            val result = JSONObject()
            if (!audioGranted()) {
                result.put("granted", false).put("tracks", tracks)
                return result.toString()
            }
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION
            )
            try {
                contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                    null,
                    "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
                )?.use { c ->
                    while (c.moveToNext()) {
                        val artist = c.getString(2) ?: ""
                        val album = c.getString(3) ?: ""
                        tracks.put(
                            JSONObject()
                                .put("id", c.getLong(0))
                                .put("title", c.getString(1) ?: "")
                                .put("artist", if (artist == "<unknown>") "" else artist)
                                .put("album", if (album == "<unknown>") "" else album)
                                .put("dur", c.getLong(4))
                        )
                    }
                }
            } catch (e: Exception) {
            }
            result.put("granted", true).put("tracks", tracks)
            return result.toString()
        }

        @JavascriptInterface
        fun requestPermission() {
            runOnUiThread { askPermissions() }
        }

        @JavascriptInterface
        fun nowPlaying(
            title: String, artist: String, album: String,
            playing: Boolean, posMs: Double, durMs: Double, cover: String
        ) {
            if (cover != lastCoverStr) {
                lastCoverStr = cover
                lastCoverBmp = try {
                    if (cover.isEmpty()) null
                    else {
                        val b = Base64.decode(cover, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(b, 0, b.size)
                    }
                } catch (e: Exception) {
                    null
                }
            }
            PlaybackService.update(
                applicationContext, title, artist, album, playing,
                posMs.toLong(), durMs.toLong(), lastCoverBmp
            )
        }
    }
}
