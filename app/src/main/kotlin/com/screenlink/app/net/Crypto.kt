package com.screenlink.app.net

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Crypto {

    /** 去掉易混淆的 0/O/1/I/L，剩 32 个字符，每位 5 bit */
    private const val KEY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    /** 生成形如 XXXX-XXXX-XXXX-XXXX 的随机密钥 */
    fun randomKey(groups: Int = 4, groupSize: Int = 4): String =
        (0 until groups).joinToString("-") {
            (0 until groupSize)
                .map { KEY_ALPHABET[random.nextInt(KEY_ALPHABET.length)] }
                .joinToString("")
        }

    /** 只保留 A-Z 0-9，方便用户带着横线/空格粘贴 */
    fun normalizeKey(raw: String): String {
        val sb = StringBuilder()
        for (c in raw.uppercase()) {
            when {
                c in 'A'..'Z' -> sb.append(c)
                c in '0'..'9' -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun keyBytes(key: String): ByteArray = normalizeKey(key).toByteArray(Charsets.UTF_8)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** HKDF-SHA256（extract + expand），用来从密钥派生会话密钥 */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val prk = hmacSha256(salt, ikm)
        val out = ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            block = mac.doFinal()
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    /** 客户端证明"我知道密钥"，不发送密钥本身 */
    fun authTag(key: String, serverNonce: ByteArray, clientNonce: ByteArray): ByteArray =
        hmacSha256(
            keyBytes(key),
            "SLNK-AUTH-v1".toByteArray(Charsets.UTF_8) + serverNonce + clientNonce,
        )

    fun sessionKey(key: String, serverNonce: ByteArray, clientNonce: ByteArray): ByteArray =
        hkdf(
            ikm = keyBytes(key),
            salt = serverNonce + clientNonce,
            info = "SLNK-SESSION-v1".toByteArray(Charsets.UTF_8),
            length = 32,
        )

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}

/**
 * 握手完成之后的通道：每条消息 AES-256-GCM 加密，IV 随机 12 字节，
 * AAD 用消息类型，防止把 TOUCH 改头换面当成 FRAME。
 */
class SecureChannel(
    private val input: DataInputStream,
    private val output: DataOutputStream,
    sessionKey: ByteArray,
) {
    private val key = SecretKeySpec(sessionKey, "AES")
    private val writeLock = Any()

    fun send(type: Int, payload: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = Crypto.randomBytes(12)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(byteArrayOf(type.toByte()))
        val sealed = cipher.doFinal(payload)
        val out = ByteArray(iv.size + sealed.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(sealed, 0, out, iv.size, sealed.size)
        synchronized(writeLock) {
            FrameIO.write(output, type, out)
        }
    }

    fun receive(): Frame {
        val frame = FrameIO.read(input)
        val body = frame.payload
        if (body.size < 12 + 16) throw ProtocolException("密文长度不对")
        val iv = body.copyOfRange(0, 12)
        val cipherText = body.copyOfRange(12, body.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(byteArrayOf(frame.type.toByte()))
        val plain = try {
            cipher.doFinal(cipherText)
        } catch (t: Throwable) {
            throw ProtocolException("解密失败：密钥不匹配或数据被篡改")
        }
        return Frame(frame.type, plain)
    }
}
