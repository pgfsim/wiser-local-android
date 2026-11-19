package dev.wiserlocal.app

import android.content.Context

/** The app's own version, read from the installed package. */
object BuildConfigInfo {
    fun versionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
}
