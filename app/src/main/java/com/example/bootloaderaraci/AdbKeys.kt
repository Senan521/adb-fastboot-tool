package com.example.bootloaderaraci

import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/** Uygulamanın kendi ADB anahtar çifti. Bir kez üretilir, dosyada saklanır. */
class AdbKeys(dir: File) {
    private val priv: PrivateKey
    private val pub: RSAPublicKey

    init {
        val pf = File(dir, "adbkey.pk8")
        val uf = File(dir, "adbkey.pub")
        val kf = KeyFactory.getInstance("RSA")
        if (pf.exists() && uf.exists()) {
            priv = kf.generatePrivate(PKCS8EncodedKeySpec(pf.readBytes()))
            pub = kf.generatePublic(X509EncodedKeySpec(uf.readBytes())) as RSAPublicKey
        } else {
            val g = KeyPairGenerator.getInstance("RSA")
            g.initialize(2048)
            val kp = g.generateKeyPair()
            priv = kp.private
            pub = kp.public as RSAPublicKey
            pf.writeBytes(priv.encoded)
            uf.writeBytes(pub.encoded)
        }
    }

    /** ADB, 20 baytlık token'ı SHA-1 özeti sayarak PKCS#1 v1.5 ile imzalar. */
    fun sign(token: ByteArray): ByteArray {
        val prefix = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
        val block = ByteArray(256)
        val psLen = 256 - 3 - prefix.size - token.size
        block[1] = 1
        for (i in 0 until psLen) block[2 + i] = 0xFF.toByte()
        System.arraycopy(prefix, 0, block, 3 + psLen, prefix.size)
        System.arraycopy(token, 0, block, 3 + psLen + prefix.size, token.size)
        val c = Cipher.getInstance("RSA/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, priv)
        return c.doFinal(block)
    }

    /** Android'in RSAPublicKey yapısı (524 bayt) + base64 + ad. */
    fun adbPublicKey(): ByteArray {
        val words = 64
        val r32 = BigInteger.ZERO.setBit(32)
        val n = pub.modulus
        val rr = BigInteger.ZERO.setBit(2048).pow(2).mod(n)
        val n0inv = r32.subtract(n.remainder(r32).modInverse(r32)).toInt()
        val bb = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(words).putInt(n0inv)
        var t = n
        repeat(words) { val d = t.divideAndRemainder(r32); t = d[0]; bb.putInt(d[1].toInt()) }
        t = rr
        repeat(words) { val d = t.divideAndRemainder(r32); t = d[0]; bb.putInt(d[1].toInt()) }
        bb.putInt(pub.publicExponent.toInt())
        val b64 = Base64.encodeToString(bb.array(), Base64.NO_WRAP)
        return "$b64 adb-fastboot-tool@android\u0000".toByteArray()
    }
}
