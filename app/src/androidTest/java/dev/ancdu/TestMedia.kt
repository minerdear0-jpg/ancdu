package dev.ancdu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File

/**
 * Медиа-фикстуры, создаваемые на устройстве во время теста (в каталоге теста под cacheDir):
 * крошечное видео (MediaCodec + MediaMuxer) и JPEG (Bitmap.compress). Ничего из сети и из assets.
 */
object TestMedia {
    /** JPEG [w]×[h] с полосами (не однотонный). */
    fun jpeg(out: File, w: Int = 400, h: Int = 300) {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).apply {
            drawColor(Color.rgb(40, 90, 160))
            val p = android.graphics.Paint().apply { color = Color.rgb(242, 169, 59) }
            for (x in 0 until w step 40) drawRect(x.toFloat(), 0f, x + 20f, h.toFloat(), p)
        }
        out.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        b.recycle()
    }

    /**
     * H.264 MP4 [w]×[h], [frames] кадров по [fps] (каждый — ключевой); false — кодировщика нет или
     * он не справился (тест тогда пропускается).
     */
    fun video(out: File, w: Int = 320, h: Int = 240, frames: Int = 15, fps: Int = 10): Boolean {
        var codec: MediaCodec? = null
        var mux: MediaMuxer? = null
        return try {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 400_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
            }
            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(fmt) ?: return false
            val c = MediaCodec.createByCodecName(name).also { codec = it }
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            val m = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { mux = it }
            val info = MediaCodec.BufferInfo()
            var track = -1
            var fed = 0
            var eos = false
            var written = 0
            val deadline = System.currentTimeMillis() + 10_000
            fun pts(k: Int) = k * 1_000_000L / fps
            while (System.currentTimeMillis() < deadline) {
                if (!eos) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        if (fed == frames) {
                            c.queueInputBuffer(i, 0, 0, pts(fed), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eos = true
                        } else {
                            val img = c.getInputImage(i) ?: return false
                            fill(img, fed, w, h)
                            c.queueInputBuffer(i, 0, w * h * 3 / 2, pts(fed), 0)
                            fed++
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = m.addTrack(c.outputFormat)
                    m.start()
                } else if (o >= 0) {
                    val buf = c.getOutputBuffer(o)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && track >= 0 && buf != null) { m.writeSampleData(track, buf, info); written++ }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            if (track < 0 || written == 0) return false
            m.stop()
            written > 0
        } catch (e: Throwable) {
            Log.i("ancdu", "test video: encoder unavailable (${e.javaClass.simpleName}: ${e.message})")
            false
        } finally {
            try { codec?.stop() } catch (e: Throwable) {}
            try { codec?.release() } catch (e: Throwable) {}
            try { mux?.release() } catch (e: Throwable) {}
        }
    }

    /** Кадр [k]: яркость Y меняется от кадра к кадру, цвет — серый (U = V = 128). */
    private fun fill(img: android.media.Image, k: Int, w: Int, h: Int) {
        val y = img.planes[0]
        val luma = (40 + k * 12) % 230
        for (row in 0 until h) for (col in 0 until w)
            y.buffer.put(row * y.rowStride + col * y.pixelStride, (if (col < w / 2) luma else 255 - luma).toByte())
        for (p in 1..2) {
            val pl = img.planes[p]
            for (row in 0 until h / 2) for (col in 0 until w / 2)
                pl.buffer.put(row * pl.rowStride + col * pl.pixelStride, 128.toByte())
        }
    }
}
