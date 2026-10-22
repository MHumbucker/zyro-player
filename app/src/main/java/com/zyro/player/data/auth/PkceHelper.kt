package com.zyro.player.data.auth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PKCE (Proof Key for Code Exchange), повторяет реализацию официального
 * Android-приложения SoundCloud (см. kittytune: PkceHelper.kt).
 *
 * 1. [generateVerifier] создаёт случайный code_verifier (64 символа).
 * 2. [generateChallenge] считает SHA-256 и кодирует в Base64URL.
 * 3. code_challenge уходит в auth URL, code_verifier — в обмен кода на токен.
 */
object PkceHelper {

    private const val VERIFIER_LENGTH = 64
    private const val VALID_CHARS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private const val VALID_CHAR_COUNT = 66

    fun generateVerifier(): String {
        val random = SecureRandom()
        val chars = CharArray(VERIFIER_LENGTH)
        for (i in 0 until VERIFIER_LENGTH) {
            val idx = (random.nextInt(1000)) % VALID_CHAR_COUNT
            chars[i] = VALID_CHARS[idx]
        }
        return String(chars)
    }

    fun generateChallenge(verifier: String): String {
        val bytes = verifier.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }
}
