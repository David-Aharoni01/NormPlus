"""NormPlus tools: everything in the Norm 2 project that is not the Android app.

One package, seven commands (``pyproject.toml``, ``[project.scripts]``):

- ``normwatch`` -- the watch emulator (``normplus.watch``): the watch's own firmware on the PC.
- ``normcmd``   -- ask a watch one 0x6F question: the emulated one, or the real one (``--mac``).
- ``normphone`` -- the Android emulator that runs ``:app``, and its Bluetooth bridge.
- ``normfw``    -- firmware image tools: verify and re-seal images, query the vendor's server.
- ``normtest``  -- the Python test suite (``tools/tests``).
- ``normboard`` -- the task board: GitHub issues on the NormPlus project.
- ``normhelp``  -- what every command is for, and how to run it.

Install them once with ``uv tool install --editable .`` at the repository root; the
docs are in ``docs/``.
"""

__version__ = "0.1.0"
