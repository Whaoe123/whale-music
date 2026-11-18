package com.whale.music.ncm

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网易云会话：Cookie 持久化 + 扫码登录 + 账号资料。
 * Cookie 存 SharedPreferences，纯文本域内自用（登录态仅用于访问网易云接口）。
 */
@Singleton
class NcmSession @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("ncm_prefs", Context.MODE_PRIVATE)

    private val cookieMap = mutableMapOf<String, String>()

    private val _profile = MutableStateFlow<NcmProfile?>(null)
    val profile: StateFlow<NcmProfile?> = _profile.asStateFlow()

    init {
        // 恢复上次登录态
        prefs.getString(KEY_COOKIES, null)?.let { raw ->
            raw.split(COOKIE_SEP).forEach { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) cookieMap[pair.substring(0, idx)] = pair.substring(idx + 1)
            }
        }
        // 基础 Cookie：网易云风控要求携带 os/appver，缺失时扫码接口大概率被 -462 拦截
        //（社区实测方案，与 NetEaseCloudMusicApi 一致）
        if (!cookieMap.containsKey("os")) cookieMap["os"] = "pc"
        if (!cookieMap.containsKey("appver")) cookieMap["appver"] = "8.9.70"
    }

    fun hasCookies(): Boolean = cookieMap.containsKey("MUSIC_U")

    fun cookieHeader(): String =
        cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /** 从响应头吸收 Set-Cookie（大小写不敏感，防漏） */
    fun absorb(conn: HttpURLConnection) {
        try {
            conn.headerFields?.forEach { (key, values) ->
                if (key != null && key.equals("Set-Cookie", ignoreCase = true)) {
                    values?.forEach { line ->
                        val pair = line.substringBefore(';')
                        val idx = pair.indexOf('=')
                        if (idx > 0) {
                            cookieMap[pair.substring(0, idx).trim()] = pair.substring(idx + 1)
                        }
                    }
                }
            }
            persist()
        } catch (e: Exception) {
            Log.w(TAG, "吸收 Cookie 失败", e)
        }
    }

    /** 已捕获的 Cookie 名列表（诊断用） */
    fun cookieNames(): String = cookieMap.keys.joinToString(",")

    /** 从外部 Cookie 串写入登录态（网页登录 / 手动粘贴），必须包含 MUSIC_U */
    fun setCookies(raw: String) {
        raw.split(";").forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val name = pair.substring(0, idx).trim()
                if (name.isNotEmpty() && name.lowercase() !in COOKIE_ATTRS) {
                    cookieMap[name] = pair.substring(idx + 1).trim()
                }
            }
        }
        if (!cookieMap.containsKey("os")) cookieMap["os"] = "pc"
        if (!cookieMap.containsKey("appver")) cookieMap["appver"] = "8.9.70"
        persist()
    }

    /** 从 JSON 正文的 cookie 字段吸收（扫码接口会带）；过滤 Expires/Path 等属性，避免污染 Cookie 头 */
    fun absorbCookieString(raw: String?) {
        if (raw.isNullOrBlank()) return
        raw.split(";").forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val name = pair.substring(0, idx).trim()
                if (name.lowercase() !in COOKIE_ATTRS) {
                    cookieMap[name] = pair.substring(idx + 1)
                }
            }
        }
        persist()
    }

    private fun persist() {
        prefs.edit()
            .putString(KEY_COOKIES, cookieMap.entries.joinToString(COOKIE_SEP) { "${it.key}=${it.value}" })
            .apply()
    }

    fun clear() {
        cookieMap.clear()
        // 清登录态时保留基础 Cookie，否则下次扫码又会被风控
        cookieMap["os"] = "pc"
        cookieMap["appver"] = "8.9.70"
        prefs.edit().remove(KEY_COOKIES).apply()
        _profile.value = null
    }

    /** 拉取账号资料；未登录返回 null 并清空资料 */
    suspend fun refreshProfile(): NcmProfile? = withContext(Dispatchers.IO) {
        try {
            val json = NcmHttp.get("$BASE/api/nuser/account/get", cookieHeader())
            val profile = if (json.optInt("code") == 200) json.optJSONObject("profile") else null
            _profile.value = profile?.let {
                NcmProfile(
                    userId = it.optLong("userId"),
                    nickname = it.optString("nickname"),
                    avatarUrl = it.optString("avatarUrl").replace("http://", "https://")
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "拉取账号资料失败", e)
            _profile.value = null
        }
        _profile.value
    }

    private companion object {
        const val TAG = "NcmSession"
        const val KEY_COOKIES = "cookies"
        const val COOKIE_SEP = "|"
        /** Set-Cookie 属性名（非 Cookie 本体，吸收时过滤） */
        val COOKIE_ATTRS = setOf("expires", "max-age", "path", "domain", "httponly", "secure", "samesite")
    }
}

internal const val BASE = "https://music.163.com"
internal const val NCM_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
