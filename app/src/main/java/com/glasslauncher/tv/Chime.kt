package com.glasslauncher.tv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** A soft two-note doorbell chime ("ding-dong"), made on the fly: no sound file, no extra permission. */
object Chime {
    fun play() = Thread {
        runCatching {
            val rate = 44100
            val notes = listOf(1318.5 to 0.0, 1046.5 to 0.42)   // E6, then C6
            val len = (rate * 1.6).toInt()
            val pcm = ShortArray(len)
            for ((f, at) in notes) {
                val start = (at * rate).toInt()
                for (i in start until len) {
                    val t = (i - start).toDouble() / rate
                    // A bell: the note with a quieter octave, a quick attack and a long soft fade.
                    val env = (1 - exp(-t * 90)) * exp(-t * 3.2)
                    val v = (sin(2 * PI * f * t) + 0.35 * sin(4 * PI * f * t)) * env * 0.33
                    pcm[i] = (pcm[i] + (v * Short.MAX_VALUE)).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(1800)
            track.release()
        }.onFailure { com.glasslauncher.tv.data.Glog.w("Chime", it) }
    }.start()
}
