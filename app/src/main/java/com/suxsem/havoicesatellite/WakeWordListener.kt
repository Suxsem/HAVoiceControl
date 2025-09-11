package com.suxsem.havoicesatellite

fun interface WakeWordListener {
    fun onWakeWordDetected(score: Double)
}