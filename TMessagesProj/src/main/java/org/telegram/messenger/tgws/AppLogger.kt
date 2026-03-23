package org.telegram.messenger.tgws

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val TAG = "TgWsProxy"
    private const val MAX_LOG_FILE_BYTES = 2 * 1024 * 1024L
    private const val TRIM_LOG_FILE_TO_BYTES = 1 * 1024 * 1024L
    private val lock = Any()
    private var logFile: File? = null
    @Volatile
    private var enabled: Boolean = true
    private var verbose: Boolean = false

    fun init(context: Context, verbose: Boolean, enabled: Boolean) {
        this.enabled = enabled
        this.verbose = verbose
        logFile = File(context.filesDir, "proxy.log")
        if (!this.enabled) return
        synchronized(lock) {
            trimFileIfNeeded(logFile ?: return@synchronized, incomingBytes = 0L)
        }
        write("INFO", "Logger", "Logger initialized")
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    fun setVerbose(enabled: Boolean) {
        verbose = enabled
    }

    fun d(tag: String, message: String) {
        if (!enabled) return
        if (!verbose) return
        Log.d(TAG, "[$tag] $message")
        write("DEBUG", tag, message)
    }

    fun i(tag: String, message: String) {
        if (!enabled) return
        Log.i(TAG, "[$tag] $message")
        write("INFO", tag, message)
    }

    fun w(tag: String, message: String) {
        if (!enabled) return
        Log.w(TAG, "[$tag] $message")
        write("WARN", tag, message)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (!enabled) return
        Log.e(TAG, "[$tag] $message", error)
        write("ERROR", tag, message + (error?.let { ": ${it.message}" } ?: ""))
    }

    fun readAll(): String {
        val file = logFile ?: return ""
        return if (file.exists()) file.readText() else ""
    }

    private fun write(level: String, tag: String, message: String) {
        if (!enabled) return
        val file = logFile ?: return
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val line = "$time  $level  $tag  $message\n"
        val incomingBytes = line.toByteArray(Charsets.UTF_8).size.toLong()
        synchronized(lock) {
            try {
                trimFileIfNeeded(file, incomingBytes)
                FileWriter(file, true).use { it.write(line) }
            } catch (_: Exception) {
            }
        }
    }

    private fun trimFileIfNeeded(file: File, incomingBytes: Long) {
        if (!file.exists()) return
        val currentSize = file.length()
        if (currentSize + incomingBytes <= MAX_LOG_FILE_BYTES) return

        val tailBytes = readTailBytes(file, TRIM_LOG_FILE_TO_BYTES)
        try {
            FileOutputStream(file, false).use { out ->
                if (tailBytes.isNotEmpty()) {
                    out.write(tailBytes)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun readTailBytes(file: File, bytesToKeep: Long): ByteArray {
        if (!file.exists() || bytesToKeep <= 0L) return ByteArray(0)
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val total = raf.length()
                if (total <= 0L) return@use ByteArray(0)

                val start = (total - bytesToKeep).coerceAtLeast(0L)
                raf.seek(start)
                val raw = ByteArray((total - start).toInt())
                raf.readFully(raw)

                if (start == 0L) return@use raw

             
                var firstNewline = -1
                for (i in raw.indices) {
                    if (raw[i] == '\n'.code.toByte()) {
                        firstNewline = i
                        break
                    }
                }
                if (firstNewline >= 0 && firstNewline + 1 < raw.size) {
                    raw.copyOfRange(firstNewline + 1, raw.size)
                } else {
                    raw
                }
            }
        } catch (_: Exception) {
            ByteArray(0)
        }
    }
}

