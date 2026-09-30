package com.adham1223990.twofahelper

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal, self-contained RFC 4226 (HOTP) / RFC 6238 (TOTP) implementation.
 * No network calls, no external libraries — everything needed is in the standard JDK.
 */
object Totp {

    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val PERIOD_SECONDS = 30L
    private const val CODE_DIGITS = 6

    /** True if [secret] looks like a plausible Base32 TOTP secret. */
    fun isLikelySecret(secret: String): Boolean {
        val cleaned = clean(secret)
        if (cleaned.length < 8) return false
        var i = 0
        while (i < cleaned.length) {
            if (BASE32_ALPHABET.indexOf(cleaned[i]) < 0) return false
            i++
        }
        return true
    }

    /** Current 6-digit TOTP code for [secret], or null if the secret cannot be decoded. */
    fun currentCode(secret: String, timeMillis: Long = System.currentTimeMillis()): String? {
        val key = try {
            base32Decode(clean(secret))
        } catch (t: Throwable) {
            return null
        }
        if (key.isEmpty()) return null

        val counter = timeMillis / 1000L / PERIOD_SECONDS
        val counterBytes = ByteArray(8)
        var c = counter
        var i = 7
        while (i >= 0) {
            counterBytes[i] = (c and 0xFF).toByte()
            c = c shr 8
            i--
        }

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val hash = mac.doFinal(counterBytes)

        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)

        var otp = (binary % 1_000_000).toString()
        while (otp.length < CODE_DIGITS) {
            otp = "0$otp"
        }
        return otp
    }

    /** Seconds remaining in the current 30-second TOTP window, for a countdown UI. */
    fun secondsRemaining(timeMillis: Long = System.currentTimeMillis()): Long {
        val elapsed = (timeMillis / 1000L) % PERIOD_SECONDS
        return PERIOD_SECONDS - elapsed
    }

    private fun clean(secret: String): String {
        val upper = secret.uppercase()
        val sb = StringBuilder()
        var i = 0
        while (i < upper.length) {
            val ch = upper[i]
            if (ch != ' ' && ch != '-' && ch != '=') sb.append(ch)
            i++
        }
        return sb.toString()
    }

    private fun base32Decode(input: String): ByteArray {
        val out = ArrayList<Byte>()
        var buffer = 0L
        var bitsLeft = 0
        var i = 0
        while (i < input.length) {
            val value = BASE32_ALPHABET.indexOf(input[i])
            if (value < 0) {
                i++
                continue
            }
            buffer = (buffer shl 5) or value.toLong()
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
            i++
        }
        val bytes = ByteArray(out.size)
        i = 0
        while (i < out.size) {
            bytes[i] = out[i]
            i++
        }
        return bytes
    }
}
