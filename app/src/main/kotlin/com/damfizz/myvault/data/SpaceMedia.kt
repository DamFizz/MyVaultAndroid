package com.damfizz.myvault.data

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import com.damfizz.myvault.core.SpaceEntry
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Images for My Space: importing a payment QR picked by the user, and making temporary files to
 * share. Nothing here logs content. Decrypted share files live only in `cache/share/`, are exposed
 * through the app's non-exported FileProvider with a one-off read grant, and are deleted later.
 */
object SpaceMedia {
    /** Picked files larger than this are refused (a QR screenshot is far smaller). */
    private const val MAX_INPUT_BYTES = 25L * 1024 * 1024
    /** Originals up to this size are stored byte-for-byte (best scan quality). */
    private const val KEEP_ORIGINAL_BYTES = 1_500_000
    private const val MAX_SIDE = 2000

    class ImportError(val reason: Reason) : Exception(reason.name) {
        enum class Reason { NOT_AN_IMAGE, TOO_LARGE, UNREADABLE }
    }

    /** A picked image ready to store. [qrText] non-null = a QR code was found and decoded. */
    class Imported(
        val bytes: ByteArray,
        val mime: String,
        val width: Int,
        val height: Int,
        val qrFound: Boolean,
        /** Where the QR sits (in this image's pixels), for "crop to QR". */
        val qrBox: Rect?,
    )

    fun import(context: Context, uri: Uri): Imported {
        val input = try {
            context.contentResolver.openInputStream(uri)?.use { s ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_INPUT_BYTES) throw ImportError(ImportError.Reason.TOO_LARGE)
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } ?: throw ImportError(ImportError.Reason.UNREADABLE)
        } catch (e: ImportError) {
            throw e
        } catch (e: Exception) {
            throw ImportError(ImportError.Reason.UNREADABLE)
        }
        return fromBytes(input)
    }

    fun fromBytes(input: ByteArray): Imported {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ImportError(ImportError.Reason.NOT_AN_IMAGE)
        val mime = bounds.outMimeType ?: "image/png"
        val keep = input.size <= KEEP_ORIGINAL_BYTES && mime in setOf("image/png", "image/jpeg", "image/webp") &&
            max(bounds.outWidth, bounds.outHeight) <= 4096
        val bitmap = decodeScaled(input, if (keep) 4096 else MAX_SIDE) ?: throw ImportError(ImportError.Reason.NOT_AN_IMAGE)
        try {
            val (bytes, outMime, w, h) = if (keep) {
                Quad(input, mime, bounds.outWidth, bounds.outHeight)
            } else {
                val scaled = scaleToMax(bitmap, MAX_SIDE)
                val enc = encode(scaled)
                val r = Quad(enc.first, enc.second, scaled.width, scaled.height)
                if (scaled !== bitmap) scaled.recycle()
                r
            }
            val (found, box) = detect(bitmap)
            // Box is in decoded-bitmap pixels; map it to the stored image's pixels.
            val sx = w.toFloat() / bitmap.width
            val sy = h.toFloat() / bitmap.height
            val mapped = box?.let { Rect((it.left * sx).roundToInt(), (it.top * sy).roundToInt(), (it.right * sx).roundToInt(), (it.bottom * sy).roundToInt()) }
            return Imported(bytes, outMime, w, h, found, mapped)
        } finally {
            bitmap.recycle()
        }
    }

    /** Crop to [box] (lossless PNG) and re-check that the QR still reads. */
    fun crop(img: Imported, box: Rect): Imported {
        val full = BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size) ?: throw ImportError(ImportError.Reason.NOT_AN_IMAGE)
        val r = Rect(box).apply { intersect(0, 0, full.width, full.height) }
        val cropped = Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height())
        val png = ByteArrayOutputStream().also { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val (found, _) = detect(cropped)
        val out = Imported(png, "image/png", cropped.width, cropped.height, found, null)
        if (cropped !== full) cropped.recycle()
        full.recycle()
        return out
    }

    fun decode(bytes: ByteArray): Bitmap? = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()

    private data class Quad(val bytes: ByteArray, val mime: String, val w: Int, val h: Int)

    private fun decodeScaled(bytes: ByteArray, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun scaleToMax(b: Bitmap, maxSide: Int): Bitmap {
        val m = max(b.width, b.height)
        if (m <= maxSide) return b
        val f = maxSide.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * f).roundToInt(), (b.height * f).roundToInt(), true)
    }

    /** PNG (lossless, ideal for QR); falls back to high-quality JPEG for photo-sized images. */
    private fun encode(b: Bitmap): Pair<ByteArray, String> {
        val png = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        if (png.size <= KEEP_ORIGINAL_BYTES) return png to "image/png"
        val jpg = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        return jpg to "image/jpeg"
    }

    /**
     * Look for a QR code (on a copy at most 1200 px). Returns whether one decoded and a box around
     * it with room for the quiet zone. Only a yes/no leaves this function: the payload is not kept.
     */
    fun detect(src: Bitmap): Pair<Boolean, Rect?> = runCatching {
        val m = max(src.width, src.height)
        val f = if (m > 1200) 1200f / m else 1f
        val b = if (f < 1f) Bitmap.createScaledBitmap(src, (src.width * f).roundToInt(), (src.height * f).roundToInt(), true) else src
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
        }
        val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(b.width, b.height, px))))
        val pts = result.resultPoints?.filterNotNull().orEmpty()
        val box = if (pts.size >= 3) {
            val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }
            val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
            val side = max(maxX - minX, maxY - minY)
            val pad = side * 0.32f
            Rect(
                ((minX - pad) / f).roundToInt().coerceAtLeast(0), ((minY - pad) / f).roundToInt().coerceAtLeast(0),
                ((maxX + pad) / f).roundToInt().coerceAtMost(src.width), ((maxY + pad) / f).roundToInt().coerceAtMost(src.height),
            )
        } else null
        if (b !== src) b.recycle()
        true to box
    }.getOrDefault(false to null)

    // ------------------------------------------------------------------ sharing

    private fun shareDir(context: Context) = File(context.cacheDir, "share").apply { mkdirs() }

    /** Delete share files older than [olderThanMs] (0 = all). Recent ones stay for the receiving app. */
    fun cleanup(context: Context, olderThanMs: Long) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        shareDir(context).listFiles()?.forEach { if (olderThanMs <= 0 || it.lastModified() < cutoff) it.delete() }
    }

    fun shareFile(context: Context, bytes: ByteArray, name: String): Uri {
        cleanup(context, 10 * 60_000L)
        val dir = File(shareDir(context), java.util.UUID.randomUUID().toString()).apply { mkdirs() }
        val f = File(dir, name)
        f.writeBytes(bytes)
        return FileProvider.getUriForFile(context, "${context.packageName}.updates", f)
    }

    fun extFor(mime: String) = when (mime) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> "png" }

    /** Opens the Android Sharesheet. Returns false when no app can receive it. */
    fun shareText(context: Context, text: String, title: String): Boolean =
        launch(context, Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), title)

    fun shareImage(context: Context, uri: Uri, mime: String, title: String, text: String? = null): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            if (!text.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, text)
            clipData = ClipData.newUri(context.contentResolver, "image", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return launch(context, intent, title)
    }

    private fun launch(context: Context, intent: Intent, title: String): Boolean = try {
        context.startActivity(Intent.createChooser(intent, title).apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /** Labels drawn on the payment card (localised by the caller). */
    class CardLabels(val holder: String, val number: String, val footer: String)

    /**
     * One shareable image with bank, holder, the exact account number and the ORIGINAL QR drawn
     * without effects. Clean typography, not styled like a bank card. PNG.
     */
    fun paymentCard(e: SpaceEntry, qrBytes: ByteArray?, accent: Int, labels: CardLabels): ByteArray {
        val w = 1080
        val pad = 72f
        val qr = qrBytes?.let { decode(it) }
        val qrSide = 820f
        val header = 210f
        val h = (header + (if (qr != null) qrSide * (qr.height.toFloat() / qr.width).coerceIn(0.5f, 1.6f) + 80f else 40f) + 400f).roundToInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // Header band with the account colour.
        p.color = accent
        c.drawRect(0f, 0f, w.toFloat(), header, p)
        p.color = Color.WHITE
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        p.textSize = 60f
        c.drawText(fit(e.bankName ?: e.displayName, p, w - pad * 2), pad, 112f, p)
        p.typeface = Typeface.DEFAULT
        p.textSize = 36f
        p.alpha = 220
        if (e.title.isNotBlank() && e.title != e.bankName) c.drawText(fit(e.title, p, w - pad * 2), pad, 168f, p)
        p.alpha = 255
        var y = header + 60f
        if (qr != null) {
            val ratio = qr.height.toFloat() / qr.width
            val dw = qrSide
            val dh = qrSide * ratio.coerceIn(0.5f, 1.6f)
            val left = (w - dw) / 2f
            val dst = RectF(left, y, left + dw, y + dh)
            // Upscaling: no smoothing so modules stay sharp; downscaling: filtered to avoid aliasing.
            val qp = Paint().apply { isFilterBitmap = qr.width > dw }
            c.drawBitmap(qr, null, dst, qp)
            qr.recycle()
            y += dh + 80f
        }
        fun field(label: String, value: String?, big: Boolean) {
            if (value.isNullOrBlank()) return
            p.color = Color.rgb(100, 116, 139)
            p.typeface = Typeface.DEFAULT
            p.textSize = 32f
            c.drawText(label, pad, y, p)
            y += if (big) 74f else 62f
            p.color = Color.rgb(15, 23, 42)
            p.typeface = if (big) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            p.textSize = if (big) 60f else 46f
            c.drawText(fit(value, p, w - pad * 2), pad, y, p)
            y += 70f
        }
        field(labels.holder, e.holder, big = false)
        field(labels.number, e.accountNumber, big = true)
        p.color = Color.rgb(148, 163, 184)
        p.typeface = Typeface.DEFAULT
        p.textSize = 26f
        c.drawText(labels.footer, pad, h - 44f, p)
        val png = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        out.recycle()
        return png
    }

    private fun fit(text: String, p: Paint, maxW: Float): String {
        if (p.measureText(text) <= maxW) return text
        var t = text
        while (t.length > 1 && p.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }
}
