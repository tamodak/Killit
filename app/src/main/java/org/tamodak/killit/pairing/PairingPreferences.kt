package org.tamodak.killit.pairing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.tamodak.killit.protocol.fromHex
import org.tamodak.killit.protocol.toHex

/** One DataStore per file per process, as the DataStore documentation requires. */
private val Context.pairingStore: DataStore<Preferences> by preferencesDataStore(name = "killit_pairing")

/**
 * Small local facts about this phone's part in pairing: whether the server knows its identity,
 * the name it last called itself, the push token the server has, and whether the guardian
 * explanation (PLAN.md §15.11) was acknowledged.
 *
 * Nothing here needs to survive Clear data: the identity keys don't either, so after Clear data
 * every fact here would describe a phone that no longer exists.
 *
 * @param context any context; only its application context is retained.
 */
class PairingPreferences(context: Context) {
    private val store = context.applicationContext.pairingStore

    /** The fingerprint the server registered, or null before registration succeeds. */
    suspend fun registeredFp(): ByteArray? = store.data.first()[KEY_REGISTERED_FP]?.let { runCatching { it.fromHex() }.getOrNull() }

    /** Records that the server knows [fp]. */
    suspend fun setRegistered(fp: ByteArray?) {
        store.edit { if (fp == null) it.remove(KEY_REGISTERED_FP) else it[KEY_REGISTERED_FP] = fp.toHex() }
    }

    /** The name this phone last called itself in a pairing, offered as the default next time. */
    suspend fun selfName(): String? = store.data.first()[KEY_SELF_NAME]

    suspend fun setSelfName(name: String) {
        store.edit { it[KEY_SELF_NAME] = name }
    }

    /** The push token last sent to the server, so an unchanged token isn't sent again. */
    suspend fun pushToken(): String? = store.data.first()[KEY_PUSH_TOKEN]

    suspend fun setPushToken(token: String?) {
        store.edit { if (token == null) it.remove(KEY_PUSH_TOKEN) else it[KEY_PUSH_TOKEN] = token }
    }

    /** True once the user tapped "I understand" on the guardian password explanation. */
    val guardianNoticeAcknowledged: Flow<Boolean> = store.data.map { it[KEY_GUARDIAN_NOTICE] ?: false }

    suspend fun acknowledgeGuardianNotice() {
        store.edit { it[KEY_GUARDIAN_NOTICE] = true }
    }

    private companion object {
        val KEY_REGISTERED_FP = stringPreferencesKey("registered_fp")
        val KEY_SELF_NAME = stringPreferencesKey("self_name")
        val KEY_PUSH_TOKEN = stringPreferencesKey("push_token")
        val KEY_GUARDIAN_NOTICE = booleanPreferencesKey("guardian_notice_acknowledged")
    }
}
