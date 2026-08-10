package com.appathy.musicroom.audio

import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 生成済み PCM をその場で鳴らす単発プレイヤ。 */
object OneShotPlayer {

    private var current: AudioTrack? = null

    @Synchronized
    fun play(pcm: ShortArray, gain: Double = 1.0) {
        release()
        if (pcm.isEmpty()) return
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SynthEngine.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        val data = if (gain == 1.0) pcm else ShortArray(pcm.size) { i ->
            (pcm[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
        track.write(data, 0, data.size)
        track.play()
        current = track
    }

    @Synchronized
    fun release() {
        try {
            current?.pause()
            current?.flush()
            current?.release()
        } catch (_: Exception) {
        }
        current = null
    }
}

object WavExporter {

    private const val FOLDER = "MusicRoom"

    fun toWavBytes(pcm: ShortArray, sampleRate: Int = SynthEngine.SAMPLE_RATE): ByteArray {
        val dataSize = pcm.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(dataSize)
        out.write(header.array())
        val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { body.putShort(it) }
        out.write(body.array())
        return out.toByteArray()
    }

    /** Download/MusicRoom/ に保存し、表示用のパス文字列を返す。失敗時は null。 */
    fun saveToDownloads(context: Context, fileName: String, pcm: ShortArray): String? {
        val bytes = toWavBytes(pcm)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Download/" + FOLDER + "/" + fileName
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    FOLDER
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                FileOutputStream(file).use { it.write(bytes) }
                file.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }
}
