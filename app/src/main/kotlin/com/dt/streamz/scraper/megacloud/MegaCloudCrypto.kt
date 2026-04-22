package com.dt.streamz.scraper.megacloud

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * OpenSSL-compatible AES-256-CBC decryption used by MegaCloud's getSources
 * response. The base64 payload is `Salted__` + 8-byte salt + ciphertext.
 * Key and IV are derived from (password, salt) via EVP_BytesToKey with MD5.
 */
internal object MegaCloudCrypto {

    private const val KEY_LEN = 32
    private const val IV_LEN = 16

    fun decryptOpenSsl(encryptedBase64: String, password: String): String {
        val blob = Base64.decode(encryptedBase64, Base64.DEFAULT)
        require(blob.size >= 16) { "ciphertext too short" }
        require(String(blob.copyOfRange(0, 8)) == "Salted__") { "missing Salted__ header" }
        val salt = blob.copyOfRange(8, 16)
        val ciphertext = blob.copyOfRange(16, blob.size)

        val (key, iv) = evpBytesToKey(password.toByteArray(Charsets.UTF_8), salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val plain = cipher.doFinal(ciphertext)
        return String(plain, Charsets.UTF_8)
    }

    /**
     * EVP_BytesToKey with MD5 — OpenSSL's legacy KDF. Matches the key+IV
     * generation used by `openssl enc -aes-256-cbc -pass pass:<password>`.
     */
    private fun evpBytesToKey(password: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val out = ByteArray(KEY_LEN + IV_LEN)
        val md5 = MessageDigest.getInstance("MD5")
        var prev = ByteArray(0)
        var written = 0
        while (written < out.size) {
            md5.reset()
            md5.update(prev)
            md5.update(password)
            md5.update(salt)
            prev = md5.digest()
            val toCopy = minOf(prev.size, out.size - written)
            System.arraycopy(prev, 0, out, written, toCopy)
            written += toCopy
        }
        return out.copyOfRange(0, KEY_LEN) to out.copyOfRange(KEY_LEN, KEY_LEN + IV_LEN)
    }
}
