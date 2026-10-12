package org.tamodak.killit.protocol

import com.google.crypto.tink.HybridDecrypt
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The shared protocol test vectors (`src/test/resources/protocol-vectors`, copied from the website
 * repository), and the test phones they are built from.
 */
internal object Vectors {
    /** Parses one vector file. */
    fun load(name: String): JsonObject {
        val stream = Vectors::class.java.classLoader!!.getResourceAsStream("protocol-vectors/$name")
            ?: error("missing vector file $name")
        return Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    /** Phones A, B and C, whose keys every vector file shares. */
    val phones: Map<String, TestPhone> by lazy {
        load("identities.json").array("phones").associate { element ->
            val phone = element.jsonObject
            phone.str("name") to TestPhone(
                name = phone.str("name"),
                signPrivate = phone.hex("signPrivate"),
                signPk = phone.hex("signPk"),
                encPrivate = phone.hex("encPrivate"),
                encPk = phone.hex("encPk"),
                bundle = phone.hex("bundle"),
            )
        }
    }

    /** A phone by its vector name. */
    fun phone(name: String): TestPhone = phones[name] ?: error("no test phone $name")
}

/** A test phone: keys from the vectors, so its signatures verify against what they list. */
internal class TestPhone(
    val name: String,
    val signPrivate: ByteArray,
    val signPk: ByteArray,
    val encPrivate: ByteArray,
    val encPk: ByteArray,
    /** The encoded bundle, with the reference's `encSig`. */
    val bundle: ByteArray,
) {
    val fp: ByteArray = fingerprint(signPk)

    /** Signs like a phone's Keystore key would, from the vector's private scalar. */
    val signer: Signer = scalarSigner(signPrivate)

    /** Opens envelopes sealed to this phone. */
    val decrypter: HybridDecrypt by lazy { Hpke.decrypter(Hpke.privateKey(encPrivate, encPk)) }
}

/** A [Signer] for a raw P-256 scalar: the JCA's ECDSA, converted to the protocol's `r ‖ s`. */
internal fun scalarSigner(scalar: ByteArray): Signer {
    val parameters = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1"))
        getParameterSpec(ECParameterSpec::class.java)
    }
    val key = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), parameters))
    return Signer { data ->
        P256.derToRaw(
            Signature.getInstance("SHA256withECDSA").run {
                initSign(key)
                update(data)
                sign()
            },
        )
    }
}

internal fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

internal fun JsonObject.hex(key: String): ByteArray = str(key).fromHex()

internal fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

internal fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

internal fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean

internal fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

internal fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray

internal val JsonElement.obj: JsonObject get() = jsonObject

/** The value of an accepted decode, or a test failure naming why it was refused. */
internal fun <T> Decoded<T>.ok(context: String = ""): T = when (this) {
    is Decoded.Ok -> value
    is Decoded.Rejected -> throw AssertionError("$context: refused ($reason)")
}
