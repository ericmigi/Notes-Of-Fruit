package com.example.applenotes.auth

import android.webkit.CookieManager

/**
 * Reads `*.icloud.com` cookies from the WebView's cookie jar after the user
 * signs in to iCloud in our in-app WebView. HttpOnly cookies are returned —
 * that flag only blocks JS-in-the-page, not native code.
 */
class CookieReader {
    fun cookieHeader(): String? {
        val mgr = CookieManager.getInstance()
        mgr.flush()
        return mgr.getCookie("https://www.icloud.com")?.takeIf { it.isNotBlank() }
    }

    fun cookieMap(): Map<String, String> {
        val header = cookieHeader() ?: return emptyMap()
        return header.split(";").mapNotNull { part ->
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) null else trimmed.substring(0, eq) to trimmed.substring(eq + 1)
        }.toMap()
    }

    fun clearAuthCookies() {
        val mgr = CookieManager.getInstance()
        mgr.removeAllCookies(null)
        mgr.flush()
    }
}
