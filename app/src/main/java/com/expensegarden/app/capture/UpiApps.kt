package com.expensegarden.app.capture

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

data class UpiApp(val pkg: String, val label: String)

/** The Android half of UpiScanner: which UPI apps are installed, and opening one. */
object UpiApps {
    /** Apps that take `upi://pay` — the manifest's <queries> makes exactly these visible. */
    fun installed(context: Context): List<UpiApp> {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay")), 0)
            .map { UpiApp(it.activityInfo.packageName, pm.getApplicationLabel(it.activityInfo.applicationInfo).toString()) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    /** Tries [UpiScanner.attempts] in order; returns the launch that started, or null if none did.
     *  The scanner links are undocumented, so a refusal falls through to the app's plain launch. */
    fun open(context: Context, pkg: String): ScannerLaunch? {
        val pm = context.packageManager
        for (launch in UpiScanner.attempts(pkg)) {
            val intent = when (launch) {
                is ScannerLaunch.Component -> Intent(launch.action).addCategory(launch.category)
                    .setClassName(launch.pkg, launch.cls).putExtra(launch.extraKey, launch.extraValue)
                is ScannerLaunch.DeepLink -> Intent(Intent.ACTION_VIEW, Uri.parse(launch.uri)).setPackage(launch.pkg)
                is ScannerLaunch.AppHome -> pm.getLaunchIntentForPackage(launch.pkg)
            } ?: continue
            // An implicit link must match a DEFAULT filter for startActivity to accept it.
            val probe = if (launch is ScannerLaunch.DeepLink) PackageManager.MATCH_DEFAULT_ONLY else 0
            if (pm.resolveActivity(intent, probe) == null) continue
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return launch
            } catch (e: ActivityNotFoundException) {
                continue
            } catch (e: SecurityException) {
                continue
            }
        }
        return null
    }
}
