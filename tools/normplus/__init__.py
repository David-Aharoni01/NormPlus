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

Every command's ``--help`` lists its options in two groups (#76): ``options``, what a person
running the tool needs, and ``developer options`` (:func:`developer_options`), what the AI
developer working on it needs as well -- diagnosis, measurement, tuning, overriding a
default that is right for normal use.
"""

__version__ = "0.1.0"


def developer_options(parser):
    """The ``developer options`` group of *parser*'s help; the rest stay under ``options``.

    A flag goes here unless someone running the tool would reach for it: the README's and
    ``normhelp``'s recipes, troubleshooting the docs tell a person to do, and decisions that
    are the owner's to make (``normwatch ota --allow-mcu``).
    """
    return parser.add_argument_group(
        "developer options",
        "For the AI developer working on these tools: diagnosis, measurement, tuning,\n"
        "and overriding defaults that are right for normal use. Not needed to use them.")
