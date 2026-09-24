package com.screenlink.app.net

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer

/** 协议版本，双方必须一致 */
const val PROTO_VERSION = 1

object MsgType {
    // —— 握手阶段（明文）——
    const val HELLO = 1
    const val AUTH = 2
    const val AUTH_RESULT = 3

    // —— 会话阶段（AES-GCM 加密）——
    const val FRAME = 10
    const val TOUCH = 11
    const val KEY = 12
    const val PING = 13
    const val PONG = 14
    const val CONFIG = 15
    const val SET_QUALITY = 16
    const val BYE = 17
    const val LOG = 18
}

object TouchAction {
    const val DOWN = 0
    const val MOVE = 1
    const val UP = 2
    const val CANCEL = 3
}

/** 单条消息上限，防止对端发个超大长度把内存干爆 */
const val MAX_FRAME_BYTES = 8 * 1024 * 1024
private const val MAGIC = 0x534C4E4B // "SLNK"

class ProtocolException(message: String) : IOException(message)

class Frame(val type: Int, val payload: ByteArray)

/**
 * 帧格式： MAGIC(4) | type(1) | length(4) | payload(length)
 */
object FrameIO {

    fun write(out: DataOutputStream, type: Int, payload: ByteArray) {
        out.writeInt(MAGIC)
        out.writeByte(type)
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    fun read(input: DataInputStream): Frame {
        val magic = input.readInt() // 流结束会抛 EOFException
        if (magic != MAGIC) throw ProtocolException("数据格式不对：0x" + Integer.toHexString(magic))
        val type = input.readUnsignedByte()
        val length = input.readInt()
        if (length < 0 || length > MAX_FRAME_BYTES) throw ProtocolException("负载长度异常：$length")
        val body = ByteArray(length)
        input.readFully(body)
        return Frame(type, body)
    }
}

object Payloads {

    /** HELLO: ver(1) + nonce(16) + requireKey(1) */
    fun hello(serverNonce: ByteArray, requireKey: Boolean): ByteArray =
        ByteBuffer.allocate(18)
            .put(PROTO_VERSION.toByte())
            .put(serverNonce)
            .put(if (requireKey) 1 else 0)
            .array()

    /** AUTH: clientNonce(16) + hmac(32) */
    fun auth(clientNonce: ByteArray, tag: ByteArray): ByteArray =
        ByteBuffer.allocate(clientNonce.size + tag.size).put(clientNonce).put(tag).array()

    /** AUTH_RESULT: ok(1) + reasonLen(2) + reason */
    fun authResult(ok: Boolean, reason: String): ByteArray {
        val bytes = reason.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(3 + bytes.size)
            .put(if (ok) 1 else 0)
            .putShort(bytes.size.toShort())
            .put(bytes)
            .array()
    }

    fun parseAuthResult(payload: ByteArray): Pair<Boolean, String> {
        if (payload.size < 3) return false to "响应异常"
        val ok = payload[0].toInt() == 1
        val len = ByteBuffer.wrap(payload, 1, 2).short.toInt().coerceAtLeast(0)
        val safeLen = len.coerceAtMost(payload.size - 3)
        val reason = String(payload, 3, safeLen, Charsets.UTF_8)
        return ok to reason
    }

    /** FRAME: timestamp(8) + width(4) + height(4) + jpeg */
    fun frame(timestamp: Long, width: Int, height: Int, jpeg: ByteArray): ByteArray =
        ByteBuffer.allocate(16 + jpeg.size)
            .putLong(timestamp)
            .putInt(width)
            .putInt(height)
            .put(jpeg)
            .array()

    fun parseFrameHeader(payload: ByteArray): LongArray? {
        if (payload.size < 16) return null
        val buf = ByteBuffer.wrap(payload)
        val ts = buf.long
        val w = buf.int
        val h = buf.int
        return longArrayOf(ts, w.toLong(), h.toLong())
    }

    fun jpegOf(payload: ByteArray): ByteArray {
        if (payload.size <= 16) return ByteArray(0)
        return payload.copyOfRange(16, payload.size)
    }

    /** TOUCH: action(1) + x(4) + y(4)，x/y 为 0~1 归一化坐标 */
    fun touch(action: Int, x: Float, y: Float): ByteArray =
        ByteBuffer.allocate(9).put(action.toByte()).putFloat(x).putFloat(y).array()

    fun parseTouch(payload: ByteArray): Triple<Int, Float, Float>? {
        if (payload.size < 9) return null
        val buf = ByteBuffer.wrap(payload, 1, 8)
        return Triple(payload[0].toInt(), buf.float, buf.float)
    }

    /** KEY: keyCode(4) */
    fun key(keyCode: Int): ByteArray = ByteBuffer.allocate(4).putInt(keyCode).array()

    fun parseKey(payload: ByteArray): Int? =
        if (payload.size < 4) null else ByteBuffer.wrap(payload, 0, 4).int

    /** CONFIG: realW(4) + realH(4) + captureW(4) + captureH(4) */
    fun config(realW: Int, realH: Int, captureW: Int, captureH: Int): ByteArray =
        ByteBuffer.allocate(16).putInt(realW).putInt(realH).putInt(captureW).putInt(captureH).array()

    fun parseConfig(payload: ByteArray): IntArray? {
        if (payload.size < 16) return null
        val buf = ByteBuffer.wrap(payload, 0, 16)
        return intArrayOf(buf.int, buf.int, buf.int, buf.int)
    }

    /** SET_QUALITY: width(4) + quality(4) + fps(4) */
    fun setQuality(width: Int, quality: Int, fps: Int): ByteArray =
        ByteBuffer.allocate(12).putInt(width).putInt(quality).putInt(fps).array()

    fun parseSetQuality(payload: ByteArray): IntArray? {
        if (payload.size < 12) return null
        val buf = ByteBuffer.wrap(payload, 0, 12)
        return intArrayOf(buf.int, buf.int, buf.int)
    }

    fun text(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)
}

class EofException : IOException("连接已关闭")
