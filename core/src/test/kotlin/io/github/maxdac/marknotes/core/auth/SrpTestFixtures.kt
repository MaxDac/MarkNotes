package io.github.maxdac.marknotes.core.auth

import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64

/** Test helpers: go-srp test modulus and a minimal SRP server used to verify the client side. */
object SrpTestFixtures {
    const val MODULUS_BASE64 =
        "W2z5HBi8RvsfYzZTS7qBaUxxPhsfHJFZpu3Kd6s1JafNrCCH9rfvPLrfuqocxWPgWDH2R8neK7PkNvjxto9TStuY5z7jAzWR" +
            "vFWN9cQhAKkdWgy0JY6ywVn22+HFpF4cYesHrqFIKUPDMSSIlWjBVmEJZ/MusD44ZT29xcPrOqeZvwtCffKtGAIjLYPZIEbZ" +
            "KnDM1Dm3q2K/xS5h+xdhjnndhsrkwm9U9oyA2wxzSXFL+pdfj2fOdRwuR5nW0J2NFrq3kJjkRmpO/Genq1UW+TEknIWAb6Vz" +
            "JJJA244K/H8cnSx2+nSNZO3bbo6Ys228ruV9A8m6DhxmS+bihN3ttQ=="

    val SIGNED_MODULUS = """
        -----BEGIN PGP SIGNED MESSAGE-----
        Hash: SHA256

        $MODULUS_BASE64
        -----BEGIN PGP SIGNATURE-----
        Version: ProtonMail
        Comment: https://protonmail.com

        wl4EARYIABAFAlwB1j0JEDUFhcTpUY8mAAD8CgEAnsFnF4cF0uSHKkXa1GIa
        GO86yMV4zDZEZcDSJo0fgr8A/AlupGN9EdHlsrZLmTA1vhIx+rOgxdEff28N
        kvNM7qIK
        =q6vu
        -----END PGP SIGNATURE-----
    """.trimIndent()

    class Server(private val modulusBytes: ByteArray, verifierSource: ByteArray) {
        private val random = SecureRandom()
        private val n = ProtonSrp.toBigInt(modulusBytes)
        private val g = BigInteger.valueOf(2)
        private val k = ProtonSrp.toBigInt(ProtonSrp.expandHash(ProtonSrp.fromBigInt(g) + modulusBytes)).mod(n)
        private val verifier = g.modPow(ProtonSrp.toBigInt(verifierSource), n)
        private val secret = BigInteger(ProtonSrp.BIT_LENGTH, random).mod(n.subtract(BigInteger.ONE))
        val serverEphemeral: ByteArray =
            ProtonSrp.fromBigInt(k.multiply(verifier).add(g.modPow(secret, n)).mod(n))

        /** Returns the server proof if the client proof is valid, null otherwise. */
        fun verify(clientEphemeral: ByteArray, clientProof: ByteArray): ByteArray? {
            val a = ProtonSrp.toBigInt(clientEphemeral)
            val u = ProtonSrp.toBigInt(ProtonSrp.expandHash(clientEphemeral + serverEphemeral))
            val shared = a.multiply(verifier.modPow(u, n)).mod(n).modPow(secret, n)
            val sharedBytes = ProtonSrp.fromBigInt(shared)
            val expected = ProtonSrp.expandHash(clientEphemeral + serverEphemeral + sharedBytes)
            if (!expected.contentEquals(clientProof)) return null
            return ProtonSrp.expandHash(clientEphemeral + clientProof + sharedBytes)
        }
    }

    fun randomSalt(): String {
        val bytes = ByteArray(10)
        SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }
}
