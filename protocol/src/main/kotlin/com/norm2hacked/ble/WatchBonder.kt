package com.norm2hacked.ble

/**
 * Establishes the BLE bond the Norm 2 requires before normal communication.
 *
 * The bonding *requirement* and *policy* are protocol-level facts shared by every
 * platform, so they live here in `:protocol`. The actual bonding APIs are
 * platform-specific and cannot be shared:
 *  - Android (`:app`) → [android.bluetooth.BluetoothDevice.createBond]
 *  - Windows (`:cli`) → WinRT custom pairing, performed inside the Python bridge
 *
 * Implementations honor [BondingPolicy].
 */
interface WatchBonder {
    /** Ensure [mac] is bonded, pairing if necessary. Must not throw — return [BondResult.Failed]. */
    suspend fun ensureBonded(mac: String): BondResult
}

sealed interface BondResult {
    /** Device was already bonded. */
    data object AlreadyBonded : BondResult
    /** Bonding was performed successfully during this call. */
    data object Bonded : BondResult
    /** This platform performs no explicit bonding (handled elsewhere / implicitly). */
    data object NotSupported : BondResult
    /** Bonding was attempted but failed. */
    data class Failed(val reason: String) : BondResult

    val isBonded: Boolean get() = this is AlreadyBonded || this is Bonded
}

/**
 * The Norm 2 bonding contract, reverse-engineered from the watch and the NORM
 * companion app (`bluetooth_bond/BluetoothUtils.smali`, which calls `createBond()`).
 *
 * The watch is a **headless "Just Works" BLE peripheral: no PIN, no MITM.** Once
 * bonded, the host caches the GATT table so service/characteristic discovery is
 * instant and the watch's connection watchdog never fires (an *unbonded* device
 * fails characteristic discovery as `Unreachable`).
 *
 * Platform recipes:
 *  - **Windows:** custom pairing — `DevicePairingKinds.ConfirmOnly` +
 *    `DevicePairingProtectionLevel.None`, auto-accepting the `PairingRequested` event.
 *    The default `PairAsync()` ceremony is rejected by the watch (status 19).
 *  - **Android:** `BluetoothDevice.createBond()` when `bondState != BOND_BONDED`.
 */
object BondingPolicy {
    const val DESCRIPTION =
        "Norm 2 is a headless Just Works BLE peripheral (no PIN, no MITM). " +
        "Windows: custom pairing ConfirmOnly + ProtectionLevel.None. " +
        "Android: BluetoothDevice.createBond()."
}
