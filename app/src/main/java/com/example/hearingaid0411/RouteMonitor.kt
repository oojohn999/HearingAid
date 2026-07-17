package com.example.hearingaid0411

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * 監聽音訊輸出裝置：判斷目前是否接了耳機（有線/藍牙/LE Audio）。
 * 拔耳機時立即通知（擴音必須馬上停，防手機喇叭嘯叫）。
 */
class RouteMonitor(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 耳機消失時的回呼（主執行緒） */
    var onHeadsetLost: (() -> Unit)? = null

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
    }

    fun start() {
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun stop() {
        try { audioManager.unregisterAudioDeviceCallback(callback) } catch (_: Exception) {}
    }

    private fun refresh() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val wiredTypes = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        val newRoute = when {
            devices.any { it.type in wiredTypes } -> HeadsetRoute.WIRED
            Build.VERSION.SDK_INT >= 31 &&
                devices.any { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET } -> HeadsetRoute.BLE
            devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } -> HeadsetRoute.BLUETOOTH
            else -> HeadsetRoute.NONE
        }
        val lost = HearingState.headsetRoute != HeadsetRoute.NONE && newRoute == HeadsetRoute.NONE
        HearingState.headsetRoute = newRoute
        if (lost) onHeadsetLost?.invoke()
    }
}
