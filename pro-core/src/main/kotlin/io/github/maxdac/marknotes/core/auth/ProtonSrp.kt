package io.github.maxdac.marknotes.core.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Proton's SRP-6a variant (auth versions 3 and 4), ported from the MIT-licensed
 * ProtonMail/go-srp library. All big integers are encoded little-endian over 256 bytes.
 */
object ProtonSrp {
    const val BIT_LENGTH = 2048
    const val BYTE_LENGTH = BIT_LENGTH / 8
    private val GENERATOR = BigInteger.valueOf(2)

    class Proofs(
        val clientEphemeral: ByteArray,
        val clientProof: ByteArray,
        val expectedServerProof: ByteArray
    )

    fun expandHash(data: ByteArray): ByteArray {
        val out = ByteArray(64 * 4)
        for (i in 0 until 4) {
            val digest = MessageDigest.getInstance("SHA-512")
            digest.update(data)
            digest.update(i.toByte())
            System.arraycopy(digest.digest(), 0, out, i * 64, 64)
        }
        return out
    }

    fun toBigInt(littleEndian: ByteArray): BigInteger = BigInteger(1, littleEndian.reversedArray())

    fun fromBigInt(value: BigInteger, length: Int = BYTE_LENGTH): ByteArray {
        val bigEndian = value.toByteArray()
        val out = ByteArray(length)
        var i = 0
        var j = bigEndian.size - 1
        while (j >= 0 && i < length) {
            out[i++] = bigEndian[j--]
        }
        return out
    }

    /**
     * Extracts the base64 modulus from the PGP clear-signed message returned by the API.
     * Note: the PGP signature is not verified (see README security notes).
     */
    fun decodeModulus(signedModulus: String): ByteArray {
        val lines = signedModulus.replace("\r\n", "\n").lines()
        val begin = lines.indexOfFirst { it.startsWith("-----BEGIN PGP SIGNED MESSAGE-----") }
        val end = lines.indexOfFirst { it.startsWith("-----BEGIN PGP SIGNATURE-----") }
        val body = if (begin >= 0 && end > begin) {
            // Skip armor headers ("Hash: ...") up to the first blank line.
            val afterHeader = lines.subList(begin + 1, end).dropWhile { it.isNotBlank() }
            afterHeader.joinToString("") { it.trim() }
        } else {
            signedModulus.trim()
        }
        val bytes = Base64.getDecoder().decode(body)
        require(bytes.size == BYTE_LENGTH) { "Unexpected modulus size ${bytes.size}" }
        return bytes
    }

    /** Hashes the password for auth versions 3 and 4. [salt] is the base64 salt from auth/info. */
    fun hashPassword(version: Int, password: String, salt: String, modulus: ByteArray): ByteArray {
        require(version == 3 || version == 4) { "Unsupported auth version $version" }
        val decodedSalt = Base64.getDecoder().decode(salt)
        val bcryptSalt = decodedSalt + "proton".toByteArray(Charsets.UTF_8)
        require(bcryptSalt.size == 16) { "Unexpected salt size ${decodedSalt.size}" }
        val crypted = bcrypt(password.toByteArray(Charsets.UTF_8), bcryptSalt)
        return expandHash(crypted + modulus)
    }

    /** The passphrase used to unlock the user's private keys (go-srp MailboxPassword). */
    fun mailboxPassword(password: String, keySalt: String): String {
        val salt = Base64.getDecoder().decode(keySalt)
        require(salt.size == 16) { "Unexpected key salt size ${salt.size}" }
        val hash = bcrypt(password.toByteArray(Charsets.UTF_8), salt).toString(Charsets.UTF_8)
        return hash.takeLast(31)
    }

    /** Returns the 60-byte "$2y$10$..." bcrypt string for [salt] (16 bytes). */
    fun bcrypt(password: ByteArray, salt: ByteArray): ByteArray {
        // bcrypt only uses the first 72 bytes including the null terminator.
        val input = if (password.size > 71) password.copyOf(71) else password
        return BCrypt.with(BCrypt.Version.VERSION_2Y).hash(10, salt, input)
    }

    fun generateProofs(
        modulusBytes: ByteArray,
        serverEphemeralBytes: ByteArray,
        hashedPassword: ByteArray,
        random: SecureRandom = SecureRandom()
    ): Proofs {
        val modulus = toBigInt(modulusBytes)
        require(modulus.bitLength() == BIT_LENGTH) { "Invalid modulus" }
        val modulusMinusOne = modulus.subtract(BigInteger.ONE)
        val serverEphemeral = toBigInt(serverEphemeralBytes)
        val multiplier = toBigInt(expandHash(fromBigInt(GENERATOR) + modulusBytes)).mod(modulus)

        require(multiplier > BigInteger.ONE && multiplier < modulusMinusOne) { "Invalid SRP multiplier" }
        require(serverEphemeral > BigInteger.ONE && serverEphemeral < modulusMinusOne) { "Invalid server ephemeral" }

        val hashedPasswordInt = toBigInt(hashedPassword)
        val minSecret = BigInteger.valueOf((BIT_LENGTH * 2).toLong())
        var clientSecret: BigInteger
        var clientEphemeral: BigInteger
        var scramblingParam: BigInteger
        do {
            do {
                clientSecret = BigInteger(BIT_LENGTH, random)
            } while (clientSecret <= minSecret || clientSecret >= modulusMinusOne)
            clientEphemeral = GENERATOR.modPow(clientSecret, modulus)
            scramblingParam = toBigInt(expandHash(fromBigInt(clientEphemeral) + serverEphemeralBytes))
        } while (scramblingParam.signum() == 0)

        val subtracted = serverEphemeral
            .subtract(GENERATOR.modPow(hashedPasswordInt, modulus).multiply(multiplier).mod(modulus))
            .mod(modulus)
        val exponent = scramblingParam.multiply(hashedPasswordInt).add(clientSecret).mod(modulusMinusOne)
        val sharedSession = subtracted.modPow(exponent, modulus)

        val clientEphemeralBytes = fromBigInt(clientEphemeral)
        val sharedSessionBytes = fromBigInt(sharedSession)
        val clientProof = expandHash(clientEphemeralBytes + serverEphemeralBytes + sharedSessionBytes)
        val serverProof = expandHash(clientEphemeralBytes + clientProof + sharedSessionBytes)
        return Proofs(clientEphemeralBytes, clientProof, serverProof)
    }
}
