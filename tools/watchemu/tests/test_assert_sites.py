"""Attributing firmware addresses to source files.

The literal-pool heuristic in SymbolMap only sees modules whose compiler output
loads __FILE__ from a pool. Most of this image uses a PC-relative ADR instead,
so those modules are invisible and their code gets attributed to an unrelated
neighbour. AssertMap decodes the call sites themselves, which is exact.

Run with:  PYTHONPATH=tools/watchemu py -3.11 tools/watchemu/tests/test_assert_sites.py
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from normwatch.fw import image as image_mod
from normwatch.fw.assertsites import AssertMap
from normwatch.fw.symbols import SymbolMap

REPO = Path(__file__).resolve().parents[3]
IMAGE = REPO / "NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin"

_img = image_mod.load(IMAGE)
ASSERTS = AssertMap(_img.payload, _img.link_address)
SYMBOLS = SymbolMap(_img.payload, _img.link_address)

#: The ctor of the notification dialog the UI builds once per UI cycle. Its own
#: assert names its file; the nearest literal pool is over a kilobyte away in a
#: different module, which is what made this hard to see the first time.
LOWPOWER_CTOR = 0x00073FC8


def test_the_low_power_dialog_is_named_by_its_own_assert():
    site = min((s for s in ASSERTS.sites if s.address >= LOWPOWER_CTOR),
               key=lambda s: s.address)
    assert ASSERTS.short(site.module) == "ui_notify_lowpower_dlg.c", site.module
    assert site.expression == "hParent", site.expression
    assert site.line == 0x99, site.line


def test_symbolmap_now_agrees():
    assert SYMBOLS.nearest(LOWPOWER_CTOR) == "ui_notify_lowpower_dlg.c"


def test_the_pool_heuristic_alone_got_it_wrong():
    # Guards the reason this class exists: without the assert sites, the answer
    # is a different module entirely.
    pool_only, _ = SYMBOLS._nearest_ref(LOWPOWER_CTOR, 0x800)
    assert pool_only != "ui_notify_lowpower_dlg.c", pool_only


def test_it_finds_modules_the_pools_cannot_see():
    invisible = {"system_power_manager_task.c", "ui_notify_lowpower_dlg.c",
                 "wireless_charge_p9027lp.c", "window_manager.c"}
    pooled = set(SYMBOLS.modules())
    found = set(ASSERTS.modules())
    for name in invisible:
        assert name in found, f"{name} missing from the assert map"
        assert name not in pooled, f"{name} unexpectedly visible to the pools"


def test_every_site_points_at_a_plausible_source_path():
    assert len(ASSERTS.sites) > 400, len(ASSERTS.sites)
    assert len(ASSERTS.modules()) > 100, len(ASSERTS.modules())
    for site in ASSERTS.sites:
        assert site.module.endswith(".c"), site.module
        assert "\\" in site.module or "/" in site.module, site.module


def test_sites_are_sorted_and_addresses_are_in_the_image():
    lo = _img.link_address
    hi = lo + len(_img.payload)
    addrs = [s.address for s in ASSERTS.sites]
    assert addrs == sorted(addrs)
    assert all(lo <= a < hi for a in addrs)


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
