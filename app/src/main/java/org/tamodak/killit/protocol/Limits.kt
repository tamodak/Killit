package org.tamodak.killit.protocol

/*
 * Sizes and limits both sides enforce (SPEC.md §13), and the HPKE suite (SPEC.md §2.1).
 *
 * The server enforces the same numbers; a phone that sent more would only be refused, so these
 * are also what the outbox splits its batches by.
 */

/** DHKEM(X25519, HKDF-SHA256). */
const val HPKE_KEM_ID = 0x0020

/** HKDF-SHA256. */
const val HPKE_KDF_ID = 0x0001

/** AES-128-GCM. */
const val HPKE_AEAD_ID = 0x0001

/** Every plaintext pads to exactly this size (SPEC.md §10.1), so lengths reveal nothing. */
const val PLAINTEXT_BYTES = 8192

/** HPKE's encapsulated key: the sender's ephemeral X25519 public key. */
const val HPKE_ENC_BYTES = 32

/** The AES-128-GCM tag. */
const val HPKE_TAG_BYTES = 16

/** `enc ‖ ciphertext ‖ tag`: the only envelope size the server accepts. */
const val ENVELOPE_BYTES = HPKE_ENC_BYTES + PLAINTEXT_BYTES + HPKE_TAG_BYTES

/** Envelopes in one `POST /v1/messages`: one per guardian, and a phone has at most 50 links. */
const val MAX_ENVELOPES_PER_POST = 50

/** Messages in one `GET /v1/messages` answer. */
const val MAX_MESSAGES_PER_FETCH = 32

/** Ids in one `POST /v1/messages/ack`. */
const val MAX_ACK_IDS = 64

/** Links per phone (PLAN.md §3.1, the "high technical cap"). */
const val MAX_LINKS = 50

/** The longest the server holds a long poll. */
const val MAX_WAIT_SECONDS = 30
