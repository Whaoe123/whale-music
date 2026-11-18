package com.whale.music.ui.qishui

import android.content.Context
import android.webkit.CookieManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 汽水（抖音）登录态。
 *
 * Cookie 额外存一份在本地：WebView 关掉后 CookieManager 可能被系统回收，
 * 存下来才能保证下次启动仍是登录态。
 */
@Singleton
class QishuiSession @Inject constructor(
    @param:ApplicationContext context: Context
) {
    private val sp = context.getSharedPreferences("qishui_session", Context.MODE_PRIVATE)

    var nickname: String
        get() = sp.getString("nickname", "").orEmpty()
        set(value) = sp.edit().putString("nickname", value).apply()

    /** 曾经登录成功过 */
    var wasLoggedIn: Boolean
        get() = sp.getBoolean("logged_in", false)
        set(value) = sp.edit().putBoolean("logged_in", value).apply()

    /** 登录 Cookie 原文 */
    var cookie: String
        get() = sp.getString("cookie", "").orEmpty()
        set(value) = sp.edit().putString("cookie", value).apply()

    /** 请求时带的 Cookie 头 */
    fun cookieHeader(): String = cookie

    /** 从 WebView 的 CookieManager 收一次 Cookie 并保存 */
    fun absorbFromWebView(): String {
        val raw = try {
            CookieManager.getInstance().getCookie("https://www.douyin.com").orEmpty()
        } catch (e: Exception) {
            ""
        }
        if (raw.isNotBlank()) cookie = raw
        return raw
    }

    fun isLoggedIn(): Boolean {
        val c = cookie.ifBlank { absorbFromWebView() }
        return c.contains("sessionid=") || c.contains("sessionid_ss=") || c.contains("sid_tt=")
    }

    fun clear() {
        nickname = ""
        wasLoggedIn = false
        cookie = ""
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
            // WebView 还没初始化时忽略
        }
    }
}
