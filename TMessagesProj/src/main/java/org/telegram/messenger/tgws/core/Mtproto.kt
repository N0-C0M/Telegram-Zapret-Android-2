package org.telegram.messenger.tgws.core

import org.telegram.messenger.tgws.AppLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

object Mtproto {
    fun dcFromInit(data: ByteArray): Pair<Int?, Boolean> {
        return try {
            if (data.size < 64) return Pair(null, false)
            val key = data.copyOfRange(8, 40)
            val iv = data.copyOfRange(40, 56)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val keystream = cipher.doFinal(ByteArray(64))
            val plain = ByteArray(8)
            for (i in 0 until 8) {
                plain[i] = (data[56 + i].toInt() xor keystream[56 + i].toInt()).toByte()
            }
            val proto = ByteBuffer.wrap(plain, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val dcRaw = ByteBuffer.wrap(plain, 4, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            if (proto == 0xEFEFEFEF.toInt() || proto == 0xEEEEEEEE.toInt() || proto == 0xDDDDDDDD.toInt()) {
                val dc = abs(dcRaw)
                if (dc in 1..1000) {
                    return Pair(dc, dcRaw < 0)
                }
            }
            Pair(null, false)
        } catch (e: Exception) {
            AppLogger.d("Mtproto", "DC extraction failed: ${e.message}")
            Pair(null, false)
        }
    }

    fun patchInitDc(data: ByteArray, dc: Int): ByteArray {
        if (data.size < 64) return data
        return try {
            val key = data.copyOfRange(8, 40)
            val iv = data.copyOfRange(40, 56)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val keystream = cipher.doFinal(ByteArray(64))

            val dcBytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(dc.toShort()).array()
            val patched = data.copyOf()
            patched[60] = (keystream[60].toInt() xor dcBytes[0].toInt()).toByte()
            patched[61] = (keystream[61].toInt() xor dcBytes[1].toInt()).toByte()
            patched
        } catch (_: Exception) {
            data
        }
    }

    class MsgSplitter(initData: ByteArray) {
        private val cipher: Cipher

        init {
            val key = initData.copyOfRange(8, 40)
            val iv = initData.copyOfRange(40, 56)
            cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.update(ByteArray(64))
        }

        fun split(chunk: ByteArray): List<ByteArray> {
            val plain = cipher.update(chunk)
            val boundaries = mutableListOf<Int>()
            var pos = 0
            while (pos < plain.size) {
                val first = plain[pos].toInt() and 0xFF
                var msgLen: Int
                if (first == 0x7f) {
                    if (pos + 4 > plain.size) break
                    val len = ByteBuffer.wrap(plain, pos + 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    msgLen = (len and 0xFFFFFF) * 4
                    pos += 4
                } else {
                    msgLen = first * 4
                    pos += 1
                }
                if (msgLen == 0 || pos + msgLen > plain.size) break
                pos += msgLen
                boundaries.add(pos)
            }
            if (boundaries.size <= 1) return listOf(chunk)

            val parts = ArrayList<ByteArray>(boundaries.size + 1)
            var prev = 0
            for (b in boundaries) {
                parts.add(chunk.copyOfRange(prev, b))
                prev = b
            }
            if (prev < chunk.size) {
                parts.add(chunk.copyOfRange(prev, chunk.size))
            }
            return parts
        }
    }
}

