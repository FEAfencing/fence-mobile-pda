package it.fence.pda.core

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    /** code 0 = errore di rete (offline, timeout, certificato diverso...). */
    data class Err(val code: Int, val message: String, val body: String? = null) : ApiResult<Nothing>()
}

/** Accetta solo il certificato autofirmato del gateway la cui impronta SHA-256 e' stata letta dal QR di associazione. */
class PinnedTrustManager(private val fingerprint: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val digest = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString("") { "%02x".format(it) }
        if (!digest.equals(fingerprint, ignoreCase = true)) throw CertificateException("Impronta del certificato diversa da quella associata")
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

class Api(private val prefs: Prefs) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun client(fp: String): OkHttpClient {
        val tm = PinnedTrustManager(fp)
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }   // la fiducia e' data dall'impronta, non dal nome host
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private fun errorText(body: String): String? = try {
        Json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
    } catch (e: Exception) { null }

    private suspend fun <T> call(
        method: String, path: String, body: String?, parse: (String) -> T,
        base: String = prefs.baseUrl, fp: String = prefs.fingerprint, auth: Boolean = true
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        try {
            val rb = Request.Builder().url(base.trimEnd('/') + path)
            if (auth) rb.header("Authorization", "Bearer ${prefs.token}").header("X-Pda-Id", prefs.deviceId)
            if (method == "POST") rb.post((body ?: "{}").toRequestBody(jsonType)) else rb.get()
            client(fp).newCall(rb.build()).execute().use { resp ->
                val txt = resp.body?.string() ?: ""
                if (resp.isSuccessful) ApiResult.Ok(parse(txt))
                else {
                    if (resp.code == 401 && auth) prefs.authFailed = true
                    ApiResult.Err(resp.code, errorText(txt) ?: "HTTP ${resp.code}", txt)
                }
            }
        } catch (e: Exception) {
            ApiResult.Err(0, e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun pair(url: String, fp: String, code: String): ApiResult<PairResp> {
        val req = PairReq(code, prefs.deviceId, "${Build.MANUFACTURER} ${Build.MODEL}")
        return call("POST", "/api/v1/pair", AppJson.encodeToString(req), { AppJson.decodeFromString<PairResp>(it) }, url, fp, auth = false)
    }

    suspend fun sync(rev: String): ApiResult<SyncResponse> =
        call("GET", "/api/v1/sync?rev=$rev", null, { AppJson.decodeFromString<SyncResponse>(it) })

    suspend fun postResult(r: ResultReq): ApiResult<ResultResp> =
        call("POST", "/api/v1/results", AppJson.encodeToString(r), { AppJson.decodeFromString<ResultResp>(it) })

    suspend fun postSignature(s: SignatureReq): ApiResult<ResultResp> =
        call("POST", "/api/v1/signatures", AppJson.encodeToString(s), { AppJson.decodeFromString<ResultResp>(it) })

    suspend fun heartbeat(h: HeartbeatReq): ApiResult<String> = call("POST", "/api/v1/heartbeat", AppJson.encodeToString(h), { it })
    suspend fun unpair(): ApiResult<String> = call("POST", "/api/v1/unpair", "{}", { it })

    suspend fun messages(): ApiResult<List<MsgDto>> = call("GET", "/api/v1/messages", null, { AppJson.decodeFromString<List<MsgDto>>(it) })
    suspend fun sendMessage(m: MessageReq): ApiResult<String> = call("POST", "/api/v1/messages", AppJson.encodeToString(m), { it })
    suspend fun ackMessage(prog: Int): ApiResult<String> = call("POST", "/api/v1/messages/ack", AppJson.encodeToString(AckReq(prog)), { it })
}
