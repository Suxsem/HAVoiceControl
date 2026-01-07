package com.suxsem.havoicecontrol

fun interface WakeWordListener {
    fun onWakeWordDetected(score: Double)
}