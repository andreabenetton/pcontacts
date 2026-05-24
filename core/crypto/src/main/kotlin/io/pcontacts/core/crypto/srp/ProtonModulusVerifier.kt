// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.srp

import io.pcontacts.core.crypto.openpgp.BouncyCastleOpenPgpService
import io.pcontacts.core.crypto.openpgp.OpenPgpService
import io.pcontacts.core.crypto.openpgp.PgpProvider
import io.pcontacts.core.crypto.openpgp.PgpPublicKeyHandle
import io.pcontacts.core.crypto.openpgp.VerificationStatus
import java.io.ByteArrayInputStream
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory

/**
 * ADR-0014 — verifies the OpenPGP detached signature on the SRP
 * `Modulus` field against a pinned Proton SRP signing public key.
 *
 * Wire contract:
 *   - `ProtonModulusEnvelope.decode(serverValue)` yields cleartext +
 *     armoredSignature.
 *   - This verifier consumes both and returns one of three outcomes:
 *
 *     VALID            — signature checks against the pinned key.
 *                        Caller proceeds with SRP arithmetic.
 *     INVALID          — signature present but verification failed.
 *                        Caller MUST abort login (treat as MITM).
 *     NO_SIGNER_KEY    — no pinned key configured (missing or
 *                        unparseable resource). Caller MUST abort
 *                        login — the key is shipped in
 *                        `proton_srp_signing_key.asc` and must load
 *                        successfully in production builds.
 *
 * The pinned key is loaded from
 * `core/crypto/src/main/resources/proton_srp_signing_key.asc`.
 * Tests construct the verifier with an explicit key via the
 * secondary constructor.
 */
interface ProtonModulusVerifier {
    fun verify(cleartext: String, armoredSignature: String): ProtonModulusVerification
}

enum class ProtonModulusVerification {
    VALID,
    INVALID,
    NO_SIGNER_KEY
}

class BouncyCastleProtonModulusVerifier(
    private val pinnedPublicKeyArmored: String?,
    private val openPgp: OpenPgpService = BouncyCastleOpenPgpService()
) : ProtonModulusVerifier {

    private val pinnedKey: PgpPublicKeyHandle? = pinnedPublicKeyArmored?.let { armored ->
        runCatching { parseFirstPublicKey(armored) }.getOrNull()
    }

    override fun verify(cleartext: String, armoredSignature: String): ProtonModulusVerification {
        val key = pinnedKey ?: return ProtonModulusVerification.NO_SIGNER_KEY
        return runCatching {
            val status = openPgp.verifyDetached(
                plaintext = cleartext.toByteArray(Charsets.US_ASCII),
                armoredSignature = armoredSignature,
                verificationKeys = listOf(key),
                canonicalText = true,
                stripTrailingSpaces = true
            )
            when (status) {
                VerificationStatus.SIGNED_AND_VALID -> ProtonModulusVerification.VALID
                else -> ProtonModulusVerification.INVALID
            }
        }.getOrElse { ProtonModulusVerification.INVALID }
    }

    private fun parseFirstPublicKey(armored: String): PgpPublicKeyHandle {
        PgpProvider.ensureProvider()
        val decoded = PGPUtil.getDecoderStream(
            ByteArrayInputStream(armored.toByteArray(Charsets.US_ASCII))
        )
        val factory = BcPGPObjectFactory(decoded)
        val ring = generateSequence { factory.nextObject() }
            .filterIsInstance<PGPPublicKeyRing>()
            .firstOrNull()
            ?: error("no PGPPublicKeyRing in pinned key armored block")
        // Prefer the master/signing key — for Proton's SRP signing key the
        // primary holds SIGN_DATA; if the layout changes we can revisit.
        val pub = ring.publicKey ?: error("PGPPublicKeyRing has no primary key")
        return PgpPublicKeyHandle(raw = pub)
    }

    companion object {
        const val RESOURCE_PATH = "/proton_srp_signing_key.asc"

        /**
         * Reads the pinned key resource from the classpath. Returns null
         * if absent or unparseable.
         */
        fun loadPinnedKeyFromClasspath(): String? {
            val stream = BouncyCastleProtonModulusVerifier::class.java.getResourceAsStream(RESOURCE_PATH)
                ?: return null
            val text = stream.use { it.readBytes() }.toString(Charsets.US_ASCII)
            // Tolerate a placeholder file with only comments; treat as "no key".
            return if (text.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----")) text else null
        }
    }
}

/** No-op verifier — useful for tests that don't care about signature plumbing. */
internal object NoOpProtonModulusVerifier : ProtonModulusVerifier {
    override fun verify(cleartext: String, armoredSignature: String): ProtonModulusVerification =
        ProtonModulusVerification.NO_SIGNER_KEY
}
