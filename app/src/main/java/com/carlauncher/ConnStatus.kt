package com.carlauncher

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Connection status on the home screen: small pills for internet, Wi-Fi, Bluetooth, hotspot and USB, and a
 * glass pop-up when something connects or disconnects ("Bluetooth connected: Pixel 7").
 */
class ConnStatus(private val act: Activity, private val strip: LinearLayout, private val overlay: FrameLayout) {
    private val ui = Handler(Looper.getMainLooper())
    private val dn = act.resources.displayMetrics.density
    private fun px(v: Int) = (v * dn).toInt()
    private var btName: String? = null
    private var usbMounted = false
    private var lastWifi: String? = null
    private var lastAp = false
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    @Suppress("DEPRECATION")
                    val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    btName = d?.name ?: d?.address
                    banner(R.drawable.ic_bt, "Bluetooth connected", btName ?: "")
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    banner(R.drawable.ic_bt, "Bluetooth disconnected", btName ?: "")
                    btName = null
                }
                Intent.ACTION_MEDIA_MOUNTED -> {
                    usbMounted = true
                    banner(R.drawable.ic_music, "USB connected", i.data?.path ?: "Music and videos are being added")
                }
                Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_REMOVED, Intent.ACTION_MEDIA_EJECT -> {
                    if (usbMounted) banner(R.drawable.ic_music, "USB removed", "")
                    usbMounted = false
                }
                "android.hardware.usb.action.USB_DEVICE_ATTACHED" -> banner(R.drawable.ic_settings, "USB device connected", "")
                "android.net.wifi.WIFI_AP_STATE_CHANGED" -> {
                    val on = Hotspot.isOn(act)
                    if (on != lastAp) banner(R.drawable.ic_wifi, if (on) "Hotspot on" else "Hotspot off", if (on) Hotspot.name(act) else "")
                    lastAp = on
                }
                WifiManager.NETWORK_STATE_CHANGED_ACTION, ConnectivityManager.CONNECTIVITY_ACTION -> {
                    val w = wifiName()
                    if (w != lastWifi && started) {
                        if (w != null) banner(R.drawable.ic_wifi, "Wi-Fi connected", w)
                        else if (lastWifi != null) banner(R.drawable.ic_wifi, "Wi-Fi disconnected", lastWifi ?: "")
                    }
                    lastWifi = w
                }
            }
            refresh()
        }
    }

    fun start() {
        lastWifi = wifiName()
        lastAp = Hotspot.isOn(act)
        btName = connectedBt()
        act.registerReceiver(receiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            @Suppress("DEPRECATION") addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED")
        })
        act.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        })
        refresh()
        ui.postDelayed({ started = true }, 1500)
    }

    fun stop() {
        started = false
        try { act.unregisterReceiver(receiver) } catch (e: Exception) { }
    }

    @Suppress("DEPRECATION")
    private fun wifiName(): String? {
        val w = act.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = w.connectionInfo ?: return null
        if (info.networkId == -1) return null
        return info.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
    }

    private fun connectedBt(): String? = try {
        BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.firstOrNull { d ->
            try { d.javaClass.getMethod("isConnected").invoke(d) as Boolean } catch (e: Throwable) { false }
        }?.let { it.name ?: it.address }
    } catch (e: Exception) { null }

    /** What carries the internet right now: Wi-Fi, Ethernet (LAN), mobile data, USB/Bluetooth tethering, or nothing. */
    @Suppress("DEPRECATION")
    private fun internet(): String? {
        val cm = act.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = try { cm.activeNetworkInfo } catch (e: Exception) { null } ?: return null
        if (!n.isConnected) return null
        return when (n.type) {
            ConnectivityManager.TYPE_WIFI -> "Wi-Fi"
            ConnectivityManager.TYPE_ETHERNET -> "LAN"
            ConnectivityManager.TYPE_MOBILE -> "Mobile data"
            ConnectivityManager.TYPE_BLUETOOTH -> "Bluetooth tethering"
            else -> n.typeName.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    private fun pill(icon: Int, text: String) {
        val p = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), px(4), px(12), px(4))
            background = GradientDrawable().apply { cornerRadius = px(16).toFloat(); setColor(0x88000000.toInt()) }
        }
        p.addView(ImageView(act).apply { setImageResource(icon) }, LinearLayout.LayoutParams(px(22), px(22)))
        p.addView(TextView(act).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.WHITE)
            maxLines = 1
            setPadding(px(6), 0, 0, 0)
        })
        strip.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(32)).apply { rightMargin = px(6) })
    }

    fun refresh() {
        strip.removeAllViews()
        val net = internet()
        pill(R.drawable.ic_settings, if (net != null) "Internet: $net" else "No internet")
        wifiName()?.let { pill(R.drawable.ic_wifi, it) }
        if (Hotspot.isOn(act)) pill(R.drawable.ic_wifi, "Hotspot: ${Hotspot.name(act)}")
        (btName ?: connectedBt())?.let { pill(R.drawable.ic_bt, it) }
        if (usbMounted) pill(R.drawable.ic_music, "USB")
    }

    /** Glass pop-up that slides down from the top for a few seconds. */
    fun banner(icon: Int, title: String, sub: String) {
        val b = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(16), px(10), px(22), px(10))
            background = GradientDrawable().apply { cornerRadius = px(26).toFloat(); setColor(0xE60B1620.toInt()); setStroke(px(1), 0x66FFFFFF) }
            elevation = px(8).toFloat()
        }
        b.addView(ImageView(act).apply { setImageResource(icon) }, LinearLayout.LayoutParams(px(38), px(38)))
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), 0, 0, 0) }
        col.addView(TextView(act).apply { text = title; textSize = 17f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD })
        if (sub.isNotEmpty()) col.addView(TextView(act).apply { text = sub; textSize = 14f; setTextColor(0xCCFFFFFF.toInt()) })
        b.addView(col)
        overlay.addView(b, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = px(18) })
        b.translationY = -px(120).toFloat()
        b.alpha = 0f
        b.animate().translationY(0f).alpha(1f).setDuration(280).start()
        ui.postDelayed({
            b.animate().translationY(-px(120).toFloat()).alpha(0f).setDuration(260).withEndAction { overlay.removeView(b) }.start()
        }, 3500)
    }
}
