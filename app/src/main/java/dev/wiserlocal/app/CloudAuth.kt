package dev.wiserlocal.app

import android.content.Context

/**
 * The Wiser cloud session shared by the app, its widget and its tiles. The access token
 * stays in memory only; the refresh token is kept encrypted by [CloudStore].
 */
object CloudAuth {
    @Volatile var session: CloudSession? = null
        private set

    /** A current access token, renewed with the saved refresh token when it has run out. Call off the main thread. */
    @Synchronized
    fun accessToken(context: Context): String {
        session?.takeIf { System.currentTimeMillis() < it.expiresAtMs }?.let { return it.accessToken }
        val store = CloudStore(context)
        val refresh = store.refreshToken ?: throw CloudAuthException("Not signed in to Wiser")
        return WiserCloud(store.base).refresh(refresh).also { keep(context, it) }.accessToken
    }

    /** Forgets the access token so the next call renews it (after the server refused it). */
    fun expire() {
        session = session?.copy(expiresAtMs = 0)
    }

    fun keep(context: Context, s: CloudSession) {
        session = s
        val store = CloudStore(context)
        store.refreshToken = s.refreshToken
        store.account = s.account
    }

    fun forget() {
        session = null
    }
}
