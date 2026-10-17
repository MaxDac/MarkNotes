package io.github.maxdac.marknotes.core.auth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.SecureRandom

class ProtonSrpTest {

    @Test
    fun expandHashIs256Bytes() {
        assertEquals(256, ProtonSrp.expandHash(byteArrayOf(1, 2, 3)).size)
    }

    @Test
    fun littleEndianRoundTrip() {
        val bytes = ByteArray(256) { it.toByte() }
        assertArrayEquals(bytes, ProtonSrp.fromBigInt(ProtonSrp.toBigInt(bytes)))
    }

    @Test
    fun decodesSignedModulus() {
        val modulus = ProtonSrp.decodeModulus(SrpTestFixtures.SIGNED_MODULUS)
        assertEquals(256, modulus.size)
        assertEquals(2048, ProtonSrp.toBigInt(modulus).bitLength())
    }

    @Test
    fun bcryptMatchesGoSrpVectors() {
        // Vectors from ProtonMail/go-srp hash_test.go (password "test!!!").
        val vectors = mapOf(
            "PTTsDBs/mlLnSk6VmtFghe" to "\$2y\$10\$PTTsDBs/mlLnSk6VmtFgheNSiK/lSwtJsrBLLDK3kZYI7193nInqy",
            "4DZHd6WZX4fEaWKtCfYdde" to "\$2y\$10\$4DZHd6WZX4fEaWKtCfYddeZfcryISo9eEMgbA90O.Wnnz1s1VKmKC",
            "RpyeXO7K2eD3r/ZZ/B63V." to "\$2y\$10\$RpyeXO7K2eD3r/ZZ/B63V.Tya53OExbyO8LR7TB93KYP4PvC.EPMW",
        )
        for ((encodedSalt, expected) in vectors) {
            val salt = decodeBcryptBase64(encodedSalt)
            val hashed = ProtonSrp.bcrypt("test!!!".toByteArray(), salt).toString(Charsets.UTF_8)
            assertEquals(expected, hashed)
        }
    }

    @Test
    fun clientProofIsAcceptedBySrpServer() {
        val modulus = ProtonSrp.decodeModulus(SrpTestFixtures.SIGNED_MODULUS)
        val salt = SrpTestFixtures.randomSalt()
        val hashed = ProtonSrp.hashPassword(4, "correct horse", salt, modulus)
        val server = SrpTestFixtures.Server(modulus, hashed)

        val proofs = ProtonSrp.generateProofs(modulus, server.serverEphemeral, hashed, SecureRandom())
        val serverProof = server.verify(proofs.clientEphemeral, proofs.clientProof)

        assertNotNull(serverProof)
        assertArrayEquals(proofs.expectedServerProof, serverProof)
    }

    @Test
    fun wrongPasswordIsRejectedBySrpServer() {
        val modulus = ProtonSrp.decodeModulus(SrpTestFixtures.SIGNED_MODULUS)
        val salt = SrpTestFixtures.randomSalt()
        val server = SrpTestFixtures.Server(modulus, ProtonSrp.hashPassword(4, "right", salt, modulus))

        val wrong = ProtonSrp.hashPassword(4, "wrong", salt, modulus)
        val proofs = ProtonSrp.generateProofs(modulus, server.serverEphemeral, wrong, SecureRandom())

        assertNull(server.verify(proofs.clientEphemeral, proofs.clientProof))
    }

    @Test
    fun mailboxPasswordIs31Chars() {
        val salt = java.util.Base64.getEncoder().encodeToString(ByteArray(16) { 7 })
        assertEquals(31, ProtonSrp.mailboxPassword("secret", salt).length)
    }

    private fun decodeBcryptBase64(text: String): ByteArray {
        val alphabet = "./ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        var buffer = 0
        var bits = 0
        val out = mutableListOf<Byte>()
        for (c in text) {
            buffer = (buffer shl 6) or alphabet.indexOf(c)
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out += ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return out.take(16).toByteArray()
    }
}
