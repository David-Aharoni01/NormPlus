package com.normplus.ui.screens.firstrun

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.normplus.ui.snapshot.Variant
import com.normplus.ui.snapshot.normPaparazzi
import com.normplus.ui.theme.NormPlusTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.LocalTime

/** The first run (#98), every step and its states, as whole Pixel 8 screens. */
@RunWith(Parameterized::class)
class FirstRunSnapshotTest(variant: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = Variant.entries
    }

    @get:Rule
    val paparazzi = normPaparazzi(variant, component = false)

    private val btOnly = Grants(bluetooth = true)
    private val norm = FoundWatch("Norm2#00000", "4C:59:80:12:44:F1")
    private val found = listOf(norm, FoundWatch("Norm2#01234", "4C:59:80:3A:91:0E"))

    private fun shot(state: FirstRunUiState, rtl: Boolean = false) = paparazzi.snapshot {
        NormPlusTheme(animationsRemoved = true) {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                FirstRunContent(state, LocalTime.of(10, 9), FirstRunActions())
            }
        }
    }

    // 1. Welcome
    @Test fun welcome() = shot(FirstRunUiState())

    // 2. Nearby devices
    @Test fun bluetooth() = shot(FirstRunUiState(step = FirstRunStep.Bluetooth))
    @Test fun bluetoothDenied() = shot(FirstRunUiState(step = FirstRunStep.Bluetooth, asked = setOf(Ask.Bluetooth)))

    // 3. Find your watch
    @Test fun findScanning() = shot(FirstRunUiState(step = FirstRunStep.Find, grants = btOnly, scan = ScanState.Scanning, found = found))
    @Test fun findScanningRtl() = shot(
        FirstRunUiState(step = FirstRunStep.Find, grants = btOnly, scan = ScanState.Scanning, found = found),
        rtl = true,
    )
    @Test fun findNoneInvalidAddress() = shot(
        FirstRunUiState(step = FirstRunStep.Find, grants = btOnly, scan = ScanState.Finished, address = "Norm 2", addressInvalid = true),
    )
    @Test fun findFailed() = shot(FirstRunUiState(step = FirstRunStep.Find, grants = btOnly, scan = ScanState.Failed))
    @Test fun findBluetoothOff() = shot(FirstRunUiState(step = FirstRunStep.Find, grants = btOnly, bluetoothOn = false))

    // 4-5. Connecting, and Android's pairing request
    private fun connect(phase: ConnectPhase, paired: Boolean = false) =
        FirstRunUiState(step = FirstRunStep.Connect, grants = btOnly, watch = norm, connect = phase, paired = paired)

    @Test fun connecting() = shot(connect(ConnectPhase.Connecting(0), paired = true))
    @Test fun connectingRetry() = shot(connect(ConnectPhase.Connecting(2), paired = true))
    @Test fun pairing() = shot(connect(ConnectPhase.Pairing(24)))
    @Test fun pairingClosed() = shot(connect(ConnectPhase.PairingClosed))
    @Test fun settingUp() = shot(connect(ConnectPhase.SettingUp(0), paired = true))
    @Test fun gaveUp() = shot(connect(ConnectPhase.GaveUp, paired = true))

    // 6. The watch's bind
    private fun bind(phase: BindPhase) = FirstRunUiState(step = FirstRunStep.Bind, grants = btOnly, watch = norm, bind = phase)

    @Test fun binding() = shot(bind(BindPhase.Binding))
    @Test fun bound() = shot(bind(BindPhase.Bound))
    @Test fun bindFailed() = shot(bind(BindPhase.Failed("bindEnd", "no reply within 10000ms")))

    // 7. Notifications
    private fun notifications(part: NotificationPart, asked: Set<Ask> = emptySet(), appsOpened: Boolean = false) =
        FirstRunUiState(
            step = FirstRunStep.Notifications, notificationPart = part, grants = btOnly, bind = BindPhase.Bound,
            asked = asked, appsOpened = appsOpened,
        )

    @Test fun notificationsPost() = shot(notifications(NotificationPart.Post))
    @Test fun notificationsPostDenied() = shot(notifications(NotificationPart.Post, setOf(Ask.Notifications)))
    @Test fun notificationAccess() = shot(notifications(NotificationPart.Access))
    @Test fun notificationAccessDenied() = shot(notifications(NotificationPart.Access, setOf(Ask.NotificationAccess)))
    @Test fun notificationApps() = shot(notifications(NotificationPart.Apps))
    @Test fun notificationAppsChosen() = shot(notifications(NotificationPart.Apps, appsOpened = true))

    // 8. Calls
    @Test fun calls() = shot(FirstRunUiState(step = FirstRunStep.Calls, grants = btOnly, bind = BindPhase.Bound))
    @Test fun callsDenied() = shot(FirstRunUiState(step = FirstRunStep.Calls, grants = btOnly, bind = BindPhase.Bound, asked = setOf(Ask.Calls)))

    // 9. Battery
    @Test fun battery() = shot(FirstRunUiState(step = FirstRunStep.Battery, grants = btOnly, bind = BindPhase.Bound))
    @Test fun batteryDenied() = shot(FirstRunUiState(step = FirstRunStep.Battery, grants = btOnly, bind = BindPhase.Bound, asked = setOf(Ask.Battery)))
}
