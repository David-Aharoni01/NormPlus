"""The BLE controller has to actually power up.

For a long time the firmware looked like it simply never used its radio: a full
boot recorded *zero* BLEIF accesses, and task #25 was written up on that basis.
It was not true. ``am_hal_ble_power_control`` (0x0008A340) was running, getting
through the MCUCTRL.FEATUREENABLE BLEREQ/BLEACK/BLEAVAIL handshake, and then
failing one step later in ``am_hal_pwrctrl_periph_enable`` (0x0008D2C0).

That function polls PWRCTRL.DEVPWRSTATUS for a per-peripheral bit it looks up in
a table at 0x000CB1EC -- three words per peripheral, word0 the DEVPWREN mask and
word1 the DEVPWRSTATUS mask. **The two are different bits.** This model used to
answer DEVPWRSTATUS by mirroring DEVPWREN, which satisfies most peripherals by
luck (MSPI wants status 0x40 and gets it because IOM5's *enable* bit is 0x40),
but cannot ever satisfy the BLE controller: it wants status 0x100 from enable
0x2000, and 0x100 belongs to UART1, which this firmware never powers. So BLEL
enable timed out, ``am_hal_ble_power_control`` bailed at 0x0008A3AC, and the
whole radio was skipped.

The second half was the status register's address. Every site that reads the
controller's state uses **+0x30C** off the 0x5000C000 base, not the 0x308 this
model had guessed.

So the table below is not hand-written: it is read back out of the image and
compared, which is the only version of this test that cannot drift from the
firmware.

Run with:  uv run normtest test_ble_powerup
"""
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from normplus.watch.fw import image as image_mod
from normplus.watch.fw.devices import (attach_motion_sensor, attach_mspi_devices,
                                  attach_pmu, attach_touch_panel)
from normplus.watch.fw.machine import Apollo3Machine
from normplus.watch.fw.peripherals import Bleif, Pwrctrl

REPO = Path(__file__).resolve().parents[2]
IMAGE = REPO / "NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin"
RESOURCES = REPO / "NORM/assets/Picture_P03B_NORM2_0.4.bin"

#: ``am_hal_pwrctrl_periph``: 15 entries of 3 words, indexed by
#: ``am_hal_pwrctrl_periph_e``. Found from the literal pool at 0x0008D32C, which
#: is where ``am_hal_pwrctrl_periph_enable`` loads its masks from.
PWRCTRL_TABLE = 0x000CB1EC
PWRCTRL_TABLE_ENTRIES = 15
#: ``AM_HAL_PWRCTRL_PERIPH_BLEL`` -- the argument at 0x0008A3A4 and 0x0008A480.
PERIPH_BLEL = 14

BLEIF_BASE = 0x5000C000
#: Far enough to cover power-on, which lands at ~0.27s of watch time (~13M).
BUDGET = 20_000_000

_img = image_mod.load(IMAGE)

#: One boot is enough for every firmware-side assertion here and it is the
#: expensive part of this file, so it is shared.
_boot = None


def quiet(*a, **k):
    pass


def word(address):
    offset = address - _img.link_address
    return struct.unpack_from("<I", _img.payload, offset)[0]


def firmware_table():
    """{DEVPWREN mask: DEVPWRSTATUS mask} straight out of the image."""
    table = {}
    for periph in range(PWRCTRL_TABLE_ENTRIES):
        entry = PWRCTRL_TABLE + periph * 12
        enable, status = word(entry), word(entry + 4)
        if enable:  # entry 0 is AM_HAL_PWRCTRL_PERIPH_NONE
            table[enable] = status
    return table


def boot():
    global _boot
    if _boot is not None:
        return _boot
    m = Apollo3Machine(_img, log=quiet, trace=None, fast_hook=True)
    attach_mspi_devices(m, resource_blob=RESOURCES, log=quiet)
    attach_touch_panel(m, log=quiet)
    attach_motion_sensor(m, log=quiet)
    attach_pmu(m, log=quiet)
    bleif = m.bus.by_base[BLEIF_BASE]
    writes = []
    original = bleif.write

    def record(offset, size, value):
        writes.append((offset, value))
        return original(offset, size, value)

    bleif.write = record
    stats = m.run(max_instructions=BUDGET, slice_size=25_000)
    _boot = (m, writes, stats)
    return _boot


# -- the model against the firmware's own table -------------------------------

def test_the_mapping_is_the_firmwares_own_table():
    assert Pwrctrl.DEVPWREN_TO_STATUS == firmware_table(), (
        "DEVPWREN->DEVPWRSTATUS no longer matches the table at "
        f"0x{PWRCTRL_TABLE:08X}")


def test_the_ble_controller_is_the_one_a_mirror_cannot_fake():
    # Why this bug survived so long: for every other peripheral some *other*
    # peripheral's enable bit happens to equal the wanted status bit, so
    # mirroring DEVPWREN into DEVPWRSTATUS accidentally works.
    table = firmware_table()
    enable = 0x2000
    assert table[enable] == 0x0100, table[enable]
    assert enable not in set(table.values()), \
        "BLEL's enable bit doubles as some status bit; the mirror would have worked"


def test_devpwrstatus_reports_the_status_bit_not_the_enable_bit():
    pwrctrl = Pwrctrl(0x40021000, 0x1000)
    pwrctrl.write(0x008, 4, 0x2000)          # DEVPWREN = BLEL only
    status = pwrctrl.read(0x018, 4)
    assert status == 0x0100, f"DEVPWRSTATUS = 0x{status:08X}, wanted 0x00000100"


def test_every_enabled_peripheral_reports_up():
    pwrctrl = Pwrctrl(0x40021000, 0x1000)
    for enable, expected in firmware_table().items():
        pwrctrl.write(0x008, 4, enable)
        got = pwrctrl.read(0x018, 4)
        assert got & expected == expected, \
            f"enable 0x{enable:04X} -> 0x{got:08X}, wanted bit 0x{expected:04X}"


# -- the status register the firmware actually reads --------------------------

def test_bstatus_is_at_0x30c():
    assert Bleif.BSTATUS == 0x30C, (
        "0x0008A422, 0x00089590 and 0x0008923A all read +0x30C off 0x5000C000")


def test_bstatus_answers_the_power_on_poll_and_nothing_else():
    bleif = Bleif(BLEIF_BASE, 0x1000)
    value = bleif.read(Bleif.BSTATUS, 4)
    # 0x0008A422: ubfx r0, r0, #8, #3 ; cmp r0, #3
    assert (value >> 8) & 0x7 == 3, f"B2M_STATE = {(value >> 8) & 0x7}, wanted 3"
    # 0x00089590 (bit 3) and 0x0008923A (bit 7) ask whether the controller has
    # something to hand over. Until the FIFO is modelled the answer is no, and
    # claiming otherwise sends the transport after a packet that is not there.
    assert not value & (1 << 3), "bit 3 set with no controller behind it"
    assert not value & (1 << 7), "bit 7 set with no controller behind it"


# -- and the firmware agrees --------------------------------------------------

def test_the_firmware_completes_its_power_on_sequence():
    _, writes, _stats = boot()
    assert writes, "the firmware never wrote to BLEIF at all"
    # am_hal_ble_power_control's ON path, in order, from 0x0008A3B2 onwards.
    wanted = [
        (0x200, 0x00000800),   # CLKCFG
        (0x410, 0x00020000),
        (0x304, 0x00000001),   # PWRCMD.RESTART
        (0x300, 0x00200003),   # BLECFG
        (0x104, 0x00002020),   # FIFOTHR
        (0x110, 0x00000001),   # FIFOCTRL
        (0x200, 0x00000C01),   # CLKCFG again, controller clock running
        (0x304, 0x0000000D),   # PWRCMD: controller up, transport armed
    ]
    position = 0
    for offset, value in writes:
        if position < len(wanted) and (offset, value) == wanted[position]:
            position += 1
    assert position == len(wanted), (
        f"power-on stopped after {position}/{len(wanted)} steps; next wanted "
        f"0x{wanted[position][0]:03X}=0x{wanted[position][1]:08X}")


def test_power_on_is_never_followed_by_a_power_off():
    # The failing version powered the radio back down once the error propagated
    # (CLKCFG=0, then PWRCMD.RESTART cleared -- am_hal_ble_power_control's OFF
    # path at 0x0008A430). Seeing that again means bring-up regressed.
    _, writes, _stats = boot()
    started = False
    for offset, value in writes:
        if (offset, value) == (0x200, 0x00000800):
            started = True
        if started and (offset, value) == (0x200, 0x00000000):
            raise AssertionError("the radio was powered back off after bring-up")


def test_the_run_survives_bring_up():
    m, _, stats = boot()
    assert not m.faults, list(m.faults)
    assert stats.stop.kind == "budget", (stats.stop.kind, stats.stop.detail)


def test_bring_up_leaves_the_firmware_spinning_with_interrupts_masked():
    """Not a defect -- but it is why a boot got slower, so pin it.

    ``am_hal_ble_blocking_hci_*`` polls BSTATUS inside a critical section. On
    real silicon the controller answers in microseconds; here nothing answers,
    so the firmware sits in that masked retry, burning watch time through
    ``am_util_delay_us`` while the FreeRTOS tick cannot be delivered.

    At this budget the run is inside one of those spins, so the tick has all
    but stopped. The boot does recover and still reaches first-run setup -- a
    576M-instruction run keeps 82% of its ticks and still finishes all 134
    animation frames -- but every BLE-side measurement should be read knowing
    this is here. It goes away once the FIFO answers, in task #25.
    """
    m, _, _stats = boot()
    ticks = m.cortexm.exception_counts.get(39, 0)
    assert ticks < 100, (
        f"{ticks} ticks -- the masked spin is gone, so this test is stale and "
        f"the cost note in the report needs rewriting")


if __name__ == "__main__":
    failures = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print(f"ok   {name}")
            except AssertionError as exc:
                failures += 1
                print(f"FAIL {name}: {exc}")
    print("all passed" if not failures else f"{failures} failed")
    sys.exit(1 if failures else 0)
