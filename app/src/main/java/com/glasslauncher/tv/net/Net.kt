package com.glasslauncher.tv.net

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

object Net {
    /**
     * The one OkHttp client the others are made from (`ok.newBuilder()`): the pictures', Home Assistant's and the
     * cameras' controls' share its connections and threads instead of each keeping its own pool.
     */
    val ok: okhttp3.OkHttpClient by lazy { okhttp3.OkHttpClient() }
    fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    fun token(): String {
        val b = ByteArray(9); SecureRandom().nextBytes(b)
        return android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    }

    fun qr(text: String, sizePx: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val px = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) for (x in 0 until sizePx) px[y * sizePx + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }
}
