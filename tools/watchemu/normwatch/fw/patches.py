"""Deliberate, opt-in deviations from the shipped firmware.

Nothing in here runs unless the caller asks for it. Each patch exists because
some firmware behaviour, correct on the watch, is unreachable in the emulator;
each one is checked against the bytes it expects to find, so a different image
fails loudly instead of being silently corrupted.
"""

from __future__ import annotations


#: ``ui_delete_object`` (0x00084284) cancels any in-progress gesture after it
#: destroys an object::
#:
#:     0x000842A4  bic r1, r1, #0x40      ; gesture_ctx.flags &= ~IN_PROGRESS
#:     0x000842A8  strb r1, [r0, #2]      ; r0 = 0x10001BF8
#:
#: That cancel fires ~16 times a second. The gesture recogniser at 0x00084B78
#: only measures the travel since the last cancel, which is the few pixels the
#: finger moves between two consecutive LVGL indev reads -- never the 30 px it
#: wants at 0x00084E38 -- so a swipe is always discarded at the release.
#:
#: What destroys an object that often is NOT the watch face redrawing itself.
#: It is the notification-screen manager creating and tearing down the
#: low-power dialog (notify id 3, ``ui_notify_lowpower_dlg.c``) once per UI
#: cycle -- forcing the suppression gate at 0x000843DC leaves this cancel with
#: zero callers in a whole run. See the emulator README, "The swipe killer is
#: the low-power dialog, not the watch face". Why that dialog is raised at all
#: is still open; suppressing the cancel is how you drive the emulated UI in
#: the meantime, and it treats the symptom rather than the cause.
GESTURE_CANCEL_ADDRESS = 0x000842A4
GESTURE_CANCEL_EXPECTED = bytes.fromhex("21f04001")   # bic.w r1, r1, #0x40
THUMB_NOP2 = b"\x00\xbf\x00\xbf"


def force_gestures(machine, *, log=print) -> bool:
    """Stop object deletion from cancelling an in-progress gesture.

    Returns True if the patch was applied. Refuses to write anything if the
    instruction at the expected address is not the one described above.
    """
    found = bytes(machine.uc.mem_read(GESTURE_CANCEL_ADDRESS, 4))
    if found != GESTURE_CANCEL_EXPECTED:
        log(f"  [patch] refusing --force-gestures: 0x{GESTURE_CANCEL_ADDRESS:08X} holds "
            f"{found.hex(' ')}, expected {GESTURE_CANCEL_EXPECTED.hex(' ')}")
        return False
    # persist=False: a patch is not the watch writing its flash, and must not
    # end up in a --flash-state file that a later, unpatched run loads.
    machine.write_flash(GESTURE_CANCEL_ADDRESS, THUMB_NOP2, persist=False)
    log(f"  [patch] gesture cancel at 0x{GESTURE_CANCEL_ADDRESS:08X} replaced with NOPs — "
        f"swipes now reach the UI (this is NOT what the shipped firmware does)")
    return True
