"""``normfw``: the firmware image tools behind one command.

    normfw verify NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin
    normfw seal patched.bin --in-place
    normfw patch-nand NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin -o patched.bin
    normfw query-ota [--json] [--enumerate] [--download DIR]
"""

from __future__ import annotations

import sys

from . import image_tool, nand_patch, query_ota

USAGE = __doc__.split("\n\n", 1)[1]


def main(argv=None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] in ("verify", "seal"):
        return image_tool.main(argv)
    if argv and argv[0] == "patch-nand":
        return nand_patch.main(argv[1:])
    if argv and argv[0] == "query-ota":
        return query_ota.main(argv[1:])
    print("normfw: firmware image tools\n\n" + USAGE.rstrip() + "\n\n"
          "verify / seal: the image header and both checksums (docs/firmware.md, section 7).\n"
          "patch-nand: the one-instruction patch that lets command 0xEE read the SPI NAND\n"
          "(#68). The result is vendor-derived -- never commit it.\n"
          "query-ota: what the vendor's update server offers. It serves Norm 1 (Telink)\n"
          "firmware -- never send that to a Norm 2.", file=sys.stderr)
    return 0 if argv[:1] in (["-h"], ["--help"]) else 2


if __name__ == "__main__":
    sys.exit(main())
