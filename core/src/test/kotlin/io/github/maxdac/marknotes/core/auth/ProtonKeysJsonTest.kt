package io.github.maxdac.marknotes.core.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonKeysJsonTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun parsesUserKeys() {
        val keys = ProtonKeysJson.userKeys(
            json(
                """{"Code":1000,"User":{"ID":"u","Keys":[
                    {"ID":"k1","PrivateKey":"PK1","Primary":1,"Active":1},
                    {"ID":"k2","PrivateKey":"PK2","Primary":0,"Active":0},
                    {"ID":"broken"}
                ]}}"""
            )
        )
        assertEquals(
            listOf(ProtonUserKey("k1", "PK1", true, true), ProtonUserKey("k2", "PK2", false, false)),
            keys
        )
    }

    @Test
    fun parsesAddressesSortedByOrder() {
        val addresses = ProtonKeysJson.addresses(
            json(
                """{"Addresses":[
                    {"ID":"a2","Email":"two@proton.me","Status":0,"Order":2,"Keys":[]},
                    {"ID":"a1","Email":"one@proton.me","Status":1,"Order":1,"Keys":[
                        {"ID":"ak1","PrivateKey":"APK1","Token":"TOKEN","Signature":"SIG","Primary":0,"Active":1,"Flags":1},
                        {"ID":"ak2","PrivateKey":"APK2","Token":null,"Primary":1,"Active":1,"Flags":3}
                    ]}
                ]}"""
            )
        )
        assertEquals(listOf("a1", "a2"), addresses.map { it.id })
        val first = addresses.first()
        assertTrue(first.enabled)
        assertFalse(addresses[1].enabled)
        assertEquals("TOKEN", first.keys[0].token)
        assertNull(first.keys[1].token)
        assertTrue(first.keys[0].canVerify)
        assertFalse(first.keys[0].canEncrypt)
        assertTrue(first.keys[1].canEncrypt)
        assertEquals("ak2", first.primaryKey?.id)
    }

    @Test
    fun parsesPublicKeysFromBothShapes() {
        val all = ProtonKeysJson.publicKeys(
            json(
                """{"Address":{"Keys":[{"Flags":3,"PublicKey":"P1"}]},
                    "Unverified":{"Keys":[{"Flags":3,"PublicKey":"P2"}]}}"""
            )
        )
        assertEquals(listOf("P1", "P2"), all)
        val legacy = ProtonKeysJson.publicKeys(json("""{"Keys":[{"Flags":3,"PublicKey":"P3"}]}"""))
        assertEquals(listOf("P3"), legacy)
    }
}
