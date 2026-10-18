package com.acmods.acnotes.data

import android.util.Base64
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object OwnerVaultKey {
    // High-security encrypted owner credentials (AES-256-GCM + PBKDF2-HMAC-SHA256)
    // Salt (16 bytes, base64)
    private const val SALT_B64 = "iXZjlyxGOnYni+7HRpeUBw=="
    // IV (12 bytes, base64)
    private const val IV_B64 = "8AqJvT/Elga7E7eN"
    // Ciphertext + 128-bit authentication tag (base64)
    private const val CIPHERTEXT_B64 = "k/FIWRyls9vif8lGS/3wel4esysyCVml2QRYwvWB2d7bk+jjd5HalMUBHO/AN1XoC9jqcRvk+YAv2BFFlIzrbIVdH7QZg14n2SOggoDccV85TA0ZZc3uR7MO5FvR+F0raYtXnmoIrqgSv+UN+45KiTqFBdAugk/iRsdce+30Uh2AICzIGMfzcYx5+tS249KBfXT6AY8muUidFUi7doWllQltaZyThciaeYcVvyoH+jJp2GiGKtXjRl8C5V0E5FviGNWN5x/yz9G50h8CrlP1sewJ5vMB/3ZQSPr3TJX0cSqPcGKDHPSWEj6TVeGB71+jqNWI9/k="

    data class DecryptedCredentials(
        val endpointHost: String,
        val bucket: String,
        val accessKey: String,
        val secretKey: String
    )

    fun unlock(password: String): DecryptedCredentials? {
        return try {
            val salt = Base64.decode(SALT_B64, Base64.DEFAULT)
            val iv = Base64.decode(IV_B64, Base64.DEFAULT)
            val ct = Base64.decode(CIPHERTEXT_B64, Base64.DEFAULT)

            val spec = PBEKeySpec(password.toCharArray(), salt, 65536, 256)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val keyBytes = factory.generateSecret(spec).encoded
            val secretKey = SecretKeySpec(keyBytes, "AES")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val decryptedBytes = cipher.doFinal(ct)
            val json = JSONObject(String(decryptedBytes, Charsets.UTF_8))

            DecryptedCredentials(
                endpointHost = json.getString("endpointHost"),
                bucket = json.getString("bucket"),
                accessKey = json.getString("accessKey"),
                secretKey = json.getString("secretKey")
            )
        } catch (e: Exception) {
            null
        }
    }
}
