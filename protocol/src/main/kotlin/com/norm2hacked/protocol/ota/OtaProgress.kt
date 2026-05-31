package com.norm2hacked.protocol.ota

enum class OtaStep { BT_PARAM, INIT, SET_HEADER, DATA_STREAM, CRC_VERIFY, REBOOT, DONE, FAILED }

data class OtaProgress(
    val step: OtaStep,
    val packetsTotal: Int = 0,
    val packetsCurrent: Int = 0,
    val errorMessage: String? = null,
) {
    val percentComplete: Int get() = if (packetsTotal > 0)
        ((packetsCurrent.toFloat() / packetsTotal) * 100).toInt() else 0
    val isDone: Boolean get() = step == OtaStep.DONE
    val isFailed: Boolean get() = step == OtaStep.FAILED
}
