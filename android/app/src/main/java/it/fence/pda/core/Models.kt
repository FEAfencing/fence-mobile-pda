package it.fence.pda.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val AppJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

@Serializable data class Member(val numFis: Int = 0, val nome: String = "")

@Serializable data class Athlete(
    val pos: Int = 0, val numFis: Int = 0, val nome: String = "", val cognome: String = "", val sigla: String = "",
    val codSoc: Int = 0, val ris: List<String?> = emptyList(), val hasFirma: Boolean = false, val members: List<Member> = emptyList()
) {
    val display: String get() = "$cognome $nome".trim().ifEmpty { sigla }
}

@Serializable data class UnitDto(
    val id: String, val kind: String, val title: String, val flags: Int, val mf: Int, val cat: Int,
    val catName: String = "", val weapon: String = "", val girone: Int = 0, val ed: Int = 0, val ordine: Int = 0,
    val pedana: Int = 0, val data: String? = null, val maxTouches: Int = 5, val fenceMax: Int = 5,
    val status: String = "todo", val rev: String = "", val athletes: List<Athlete> = emptyList()
) {
    val isTeam: Boolean get() = kind.startsWith("team")
    val isPool: Boolean get() = kind == "pool"
    val genderLabel: String get() = if (mf == 0) "M" else "F"
    val subtitle: String get() = "$weapon $genderLabel · $catName"
}

@Serializable data class Arbiter(val numFis: Int = 0, val cognome: String = "", val nome: String = "", val pedana: Int = 0) {
    val display: String get() = "$cognome $nome".trim()
}

@Serializable data class SyncResponse(
    val changed: Boolean = false, val rev: String = "", val serverTime: String = "", val arbiter: Arbiter? = null,
    val pendingMessages: Int = 0, val units: List<UnitDto> = emptyList()
)

@Serializable data class PairCell(val a: Int, val b: Int, val ca: String? = null, val cb: String? = null)

@Serializable data class ResultReq(
    val txId: String, val unitId: String, @SerialName("final") val isFinal: Boolean, val expectedRev: String? = null,
    val overwrite: Boolean = false, val reason: String? = null, val supervisorPin: String? = null,
    val notes: String? = null, val extra: String? = null, val cells: List<PairCell>
)

@Serializable data class SignatureReq(val txId: String, val unitId: String, val athleteIndex: Int, val jpegBase64: String)
@Serializable data class MessageReq(val txId: String, val testo: String)
@Serializable data class AckReq(val prog: Int)
@Serializable data class HeartbeatReq(val appVersion: String, val battery: Int, val pending: Int)
@Serializable data class PairReq(val code: String, val deviceId: String, val model: String)

@Serializable data class PairResp(val ok: Boolean = false, val token: String = "", val deviceId: String = "", val fingerprint: String = "", val error: String? = null)
@Serializable data class ResultResp(val ok: Boolean = false, val error: String? = null, val unit: UnitDto? = null)
@Serializable data class MsgDto(val prog: Int = 0, val testo: String = "", val oraInvio: String = "", val letto: Boolean = false)
@Serializable data class QrPayload(val u: String, val fp: String, val c: String)
