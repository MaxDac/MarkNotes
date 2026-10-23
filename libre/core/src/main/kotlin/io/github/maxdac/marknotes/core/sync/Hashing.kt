package io.github.maxdac.marknotes.core.sync

import java.security.MessageDigest

object Hashing {
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
