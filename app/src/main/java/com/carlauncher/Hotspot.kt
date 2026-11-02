package com.carlauncher

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager

/**
 * Wi-Fi hotspot control. Android keeps these calls hidden, so they are reached by reflection; they work on
 * Android 5–7 head units that let apps manage the hotspot. Where the system refuses, [apply] returns false
 * and the caller offers the system hotspot screen instead.
 */
object Hotspot {
    private fun wifi(ctx: Context) = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun isOn(ctx: Context): Boolean = try {
        wifi(ctx).javaClass.getMethod("isWifiApEnabled").invoke(wifi(ctx)) as Boolean
    } catch (e: Throwable) {
        false
    }

    fun config(ctx: Context): WifiConfiguration? = try {
        wifi(ctx).javaClass.getMethod("getWifiApConfiguration").invoke(wifi(ctx)) as WifiConfiguration?
    } catch (e: Throwable) {
        null
    }

    fun name(ctx: Context): String = config(ctx)?.SSID?.trim('"') ?: "Car hotspot"
    fun password(ctx: Context): String = config(ctx)?.preSharedKey?.trim('"') ?: ""

    /** Saves name and password (WPA2, at least 8 characters) and turns the hotspot on or off. */
    @Suppress("DEPRECATION")
    fun apply(ctx: Context, name: String, pass: String, on: Boolean): Boolean = try {
        val w = wifi(ctx)
        val c = config(ctx) ?: WifiConfiguration()
        c.SSID = name
        c.allowedKeyManagement.clear()
        if (pass.length >= 8) {
            c.allowedKeyManagement.set(4) // WPA2_PSK
            c.preSharedKey = pass
        } else {
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            c.preSharedKey = null
        }
        // The hotspot and normal Wi-Fi share the radio on most units.
        if (on && w.isWifiEnabled) w.isWifiEnabled = false
        val set = w.javaClass.getMethod("setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
        if (isOn(ctx)) set.invoke(w, null, false)
        try { w.javaClass.getMethod("setWifiApConfiguration", WifiConfiguration::class.java).invoke(w, c) } catch (e: Throwable) { }
        if (on) set.invoke(w, c, true) as Boolean else true
    } catch (e: Throwable) {
        false
    }
}
