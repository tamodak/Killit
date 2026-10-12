package org.tamodak.killit.protocol

import com.google.crypto.tink.hybrid.internal.HpkeContext
import com.google.crypto.tink.hybrid.internal.HpkeKemKeyFactory
import com.google.crypto.tink.hybrid.internal.HpkePrimitiveFactory
import java.security.GeneralSecurityException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Tink's HPKE against `hpke.json`: RFC 9180's own vector and envelopes the reference sealed. */
class HpkeVectorsTest {
    private val vectors = Vectors.load("hpke.json")

    /**
     * RFC 9180 A.1.1: Tink derives the same shared secret and opens the RFC's ciphertext.
     *
     * The RFC's example has an AAD, which Tink's public `HybridDecrypt` always leaves empty, so this
     * one test reaches into Tink's internal HPKE context. Killit's own envelopes use no AAD and go
     * through the public API, as [referenceEnvelopesOpen] checks.
     */
    @Test
    fun rfc9180VectorOpens() {
        val rfc = vectors.obj("rfc9180")
        val privateKey = Hpke.privateKey(rfc.hex("skRm"), rfc.hex("pkRm"))
        val context = HpkeContext.createRecipientContext(
            rfc.hex("pkEm"),
            HpkeKemKeyFactory.createPrivate(privateKey),
            HpkePrimitiveFactory.createKem(Hpke.PARAMETERS.kemId),
            HpkePrimitiveFactory.createKdf(Hpke.PARAMETERS.kdfId),
            HpkePrimitiveFactory.createAead(Hpke.PARAMETERS.aeadId),
            rfc.hex("info"),
        )
        assertArrayEquals(rfc.hex("plaintext"), context.open(rfc.hex("ciphertext"), rfc.hex("aad")))
        assertArrayEquals(rfc.hex("sealed"), rfc.hex("pkEm") + rfc.hex("ciphertext"))
    }

    /** What the reference sealed, Tink opens: the two agree on the suite, the layout and `info`. */
    @Test
    fun referenceEnvelopesOpen() {
        for (element in vectors.array("killit")) {
            val case = element.obj
            assertEquals("", case.str("aad"))
            val recipient = Vectors.phone(case.str("recipient"))
            assertArrayEquals(case.str("name"), case.hex("plaintext"), recipient.decrypter.decrypt(case.hex("sealed"), case.hex("info")))
        }
    }

    /** Tink's sealing opens again, and only under the same `info`. */
    @Test
    fun sealOpensUnderTheSameInfoOnly() {
        val recipient = Vectors.phone("B")
        val info = envelopeInfo(Vectors.phone("A").fp, recipient.fp)
        val sealed = Hpke.encrypter(recipient.encPk).encrypt(byteArrayOf(1, 2, 3), info)
        assertEquals(HPKE_ENC_BYTES + 3 + HPKE_TAG_BYTES, sealed.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), recipient.decrypter.decrypt(sealed, info))
        val otherInfo = envelopeInfo(Vectors.phone("C").fp, recipient.fp)
        assertThrows(GeneralSecurityException::class.java) { recipient.decrypter.decrypt(sealed, otherInfo) }
    }
}
