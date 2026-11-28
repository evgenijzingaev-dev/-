package ru.smartbasket.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.net.URI
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
private const val THUMBNAIL_SIZE = 192
private val thumbnails = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}
private val thumbnailWorkers = ThreadPoolExecutor(
    2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(48),
    ThreadPoolExecutor.DiscardOldestPolicy()
).apply { allowCoreThreadTimeOut(true) }
private val mainThread = Handler(Looper.getMainLooper())
private val requests = WeakHashMap<ImageView, String>()

// Call on the UI thread after setting the row's placeholder, including for an empty URL.
internal fun loadCatalogThumbnail(view: ImageView, url: String) {
    requests[view] = url
    if (url.isEmpty() || url.length > 2048) return
    val uri = try { URI(url) } catch (_: java.net.URISyntaxException) { return }
    val host = uri.host?.lowercase(Locale.ROOT) ?: return
    if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443) ||
        !(host == "5ka.ru" || host.endsWith(".5ka.ru") || host == "x5.ru" || host.endsWith(".x5.ru"))) return
    thumbnails.get(url)?.let { view.setImageBitmap(it); return }

    val target = WeakReference(view)
    thumbnailWorkers.execute {
        val image = thumbnails.get(url) ?: try {
            fetchThumbnail(uri)?.also { thumbnails.put(url, it) }
        } catch (_: Exception) { null }
        if (image != null) mainThread.post {
            val row = target.get()
            if (row != null && requests[row] == url) row.setImageBitmap(image)
        }
    }
}

private fun fetchThumbnail(uri: URI): Bitmap? {
    val connection = uri.toURL().openConnection() as HttpsURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 3500
        connection.readTimeout = 3500
        connection.setRequestProperty("Accept", "image/*")
        if (connection.responseCode != 200 || connection.contentLengthLong > MAX_IMAGE_BYTES) return null
        val data = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                if (count > MAX_IMAGE_BYTES - data.size()) return null
                data.write(buffer, 0, count)
            }
        }
        val bytes = data.toByteArray()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth !in 1..8192 || options.outHeight !in 1..8192) return null
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > THUMBNAIL_SIZE) sample *= 2
        options.inJustDecodeBounds = false
        options.inSampleSize = sample
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } finally {
        connection.disconnect()
    }
}
