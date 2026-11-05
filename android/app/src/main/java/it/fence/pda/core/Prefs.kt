package it.fence.pda.core

import android.content.Context
import java.util.UUID

/** Impostazioni private dell'app. L'identificativo PDA e' un UUID generato alla prima apertura (non il MAC). */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("fence_pda", Context.MODE_PRIVATE)

    val deviceId: String
        get() {
            var id = sp.getString("deviceId", null)
            // Pda.Mobile in Fence! e' varchar(10): l'identificativo deve stare in 10 caratteri ("PDA-" + 6 esadecimali).
            // Un vecchio formato (es. FENCEPDA-XXXXXXXX) viene rigenerato.
            if (id == null || !Regex("^PDA-[0-9A-F]{6}$").matches(id)) {
                id = "PDA-" + UUID.randomUUID().toString().replace("-", "").take(6).uppercase()
                sp.edit().putString("deviceId", id).apply()
            }
            return id
        }

    var baseUrl: String get() = sp.getString("baseUrl", "") ?: ""; set(v) = sp.edit().putString("baseUrl", v).apply()
    var fingerprint: String get() = sp.getString("fp", "") ?: ""; set(v) = sp.edit().putString("fp", v).apply()
    var token: String get() = sp.getString("token", "") ?: ""; set(v) = sp.edit().putString("token", v).apply()
    var lastRev: String get() = sp.getString("lastRev", "") ?: ""; set(v) = sp.edit().putString("lastRev", v).apply()
    var lastSyncAt: Long get() = sp.getLong("lastSyncAt", 0L); set(v) = sp.edit().putLong("lastSyncAt", v).apply()
    var requireSignature: Boolean get() = sp.getBoolean("reqSign", false); set(v) = sp.edit().putBoolean("reqSign", v).apply()
    var gridMode: Boolean get() = sp.getBoolean("gridMode", false); set(v) = sp.edit().putBoolean("gridMode", v).apply()
    var arbiterJson: String get() = sp.getString("arbiter", "") ?: ""; set(v) = sp.edit().putString("arbiter", v).apply()
    var pendingMessages: Int get() = sp.getInt("pendingMsgs", 0); set(v) = sp.edit().putInt("pendingMsgs", v).apply()
    /** Il gateway ha risposto 401: dispositivo revocato o token non piu' valido. */
    var authFailed: Boolean get() = sp.getBoolean("authFailed", false); set(v) = sp.edit().putBoolean("authFailed", v).apply()

    val paired: Boolean get() = token.isNotEmpty() && baseUrl.isNotEmpty()

    fun savePairing(url: String, fp: String, token: String) {
        sp.edit().putString("baseUrl", url).putString("fp", fp.lowercase()).putString("token", token)
            .putBoolean("authFailed", false).putString("lastRev", "").apply()
    }

    fun clearPairing() {
        sp.edit().remove("baseUrl").remove("fp").remove("token").remove("lastRev").remove("arbiter")
            .putBoolean("authFailed", false).apply()
    }
}
