package com.carlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * Storage access for Music, Videos and Files. Android 11 and newer hide other folders and USB drives from
 * apps unless "All files access" is turned on for them, which only the user can do in system settings.
 */
object Storage {
    fun hasAccess(act: Activity): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= 23 -> act.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        else -> true
    }

    /** Asks for access once per screen open if it is missing; [why] says what the screen needs it for. */
    fun ensure(act: Activity, why: String) {
        if (hasAccess(act)) return
        if (Build.VERSION.SDK_INT >= 30) {
            AlertDialog.Builder(act).setTitle("Allow access to your files")
                .setMessage("To show $why on this unit and on USB drives, turn on \"Allow access to manage all files\" for Car Launcher on the next screen, then come back.")
                .setPositiveButton("Open setting") { _, _ ->
                    try {
                        act.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${act.packageName}")))
                    } catch (e: Exception) {
                        act.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
                .setNegativeButton("Not now", null).show()
        } else if (Build.VERSION.SDK_INT >= 23) {
            act.requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 7)
        }
    }
}
