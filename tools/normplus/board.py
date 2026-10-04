"""``normboard``: the project's task board -- GitHub issues on NormPlus, on the "NormPlus" project.

    normboard                       what is open: In Progress first, then Todo, by priority
    normboard all                   everything, Done included
    normboard show 56               one issue with its comments
    normboard start 56              move it to In Progress
    normboard todo 56               move it back to Todo
    normboard done 56 [-m TEXT]     close it as completed (with a comment); the board says Done
    normboard note 56 -m TEXT       add a comment: a finding, a result, a commit
    normboard new "Title" -a AREA [-p high|medium|low] [-l TAG ...] [-m BODY | -f BODY.md] [--start]
    normboard area 56 emulator      file an issue under another area
    normboard sync                  put every issue on the board, Done for closed ones

Every issue has exactly one area label -- app, protocol, firmware, emulator or tooling
(``normboard areas``) -- and one priority; anything else (``ble``, ``ota``, ...) is a tag.

It drives the GitHub CLI (``gh``), signed in with the ``project`` scope
(``gh auth refresh -s project``). The repository is public, so issues are too.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

OWNER = "David-Aharoni01"
REPO = f"{OWNER}/NormPlus"
PROJECT = 1
PRIORITIES = ("high", "medium", "low")
#: The category every issue is filed under (#78): its label's colour and description (GitHub
#: allows 100 characters). The four goals in CLAUDE.md, and the tooling around them. An issue
#: goes where it is done.
AREAS = {
    "app": ("0e8a16", "The Android app (:app): BLE connection, sync, notifications, calls, UI, "
                      "on-device checks"),
    "protocol": ("5319e7", "The watch's 0x6F protocol and :protocol: finding, decoding and "
                           "building commands"),
    "firmware": ("b60205", "The watch's own firmware and hardware: reverse-engineering, patches, "
                           "updating it"),
    "emulator": ("0052cc", "The emulators: the watch's firmware on the PC (normwatch), the "
                           "Android one (normphone)"),
    "tooling": ("6e7781", "The rest on the PC: normcmd, normfw, normtest, normboard, the repo, "
                          "the docs, the board"),
}


def _gh_path() -> str:
    found = shutil.which("gh")
    if found:
        return found
    default = r"C:\Program Files\GitHub CLI\gh.exe"
    if os.path.exists(default):
        return default
    raise SystemExit("normboard: the GitHub CLI is not installed (winget install GitHub.cli)")


def gh(*args: str) -> str:
    run = subprocess.run([_gh_path(), *args], capture_output=True, text=True, encoding="utf-8")
    if run.returncode != 0:
        raise SystemExit(f"normboard: gh {' '.join(args[:2])}: {run.stderr.strip()}")
    return run.stdout


def gh_json(*args: str):
    return json.loads(gh(*args))


class Board:
    """The project's id, its Status field and that field's options, looked up once."""

    def __init__(self) -> None:
        project = gh_json("project", "view", str(PROJECT), "--owner", OWNER, "--format", "json")
        self.id = project["id"]
        self.url = project["url"]
        fields = gh_json("project", "field-list", str(PROJECT), "--owner", OWNER, "--format", "json")
        status = next(f for f in fields["fields"] if f["name"] == "Status")
        self.status_field = status["id"]
        self.options = {o["name"]: o["id"] for o in status["options"]}

    def add(self, number: int) -> str:
        """The issue's item on the board, adding it if it is not there yet."""
        item = gh_json("project", "item-add", str(PROJECT), "--owner", OWNER, "--format", "json",
                       "--url", f"https://github.com/{REPO}/issues/{number}")
        return item["id"]

    def set_status(self, number: int, status: str) -> None:
        gh("project", "item-edit", "--project-id", self.id, "--id", self.add(number),
           "--field-id", self.status_field, "--single-select-option-id", self.options[status])


def _prefixed(labels, prefix: str) -> str:
    for label in labels or []:
        name = label if isinstance(label, str) else label.get("name", "")
        if name.startswith(prefix):
            return name[len(prefix):]
    return "-"


def _priority(labels) -> str:
    return _prefixed(labels, "priority: ")


def _area(labels) -> str:
    return _prefixed(labels, "area: ")


def cmd_list(args) -> int:
    board = Board()
    items = gh_json("project", "item-list", str(PROJECT), "--owner", OWNER, "--format", "json",
                    "--limit", "1000")["items"]
    on_board = {i["content"]["number"]: i for i in items if i.get("content", {}).get("number")}
    issues = gh_json("issue", "list", "--repo", REPO, "--state", "all" if args.all else "open",
                     "--limit", "1000", "--json", "number,title,labels,state")
    order = {"In Progress": 0, "Todo": 1, "Done": 2}
    rows = []
    for issue in issues:
        status = on_board.get(issue["number"], {}).get("status") or (
            "Done" if issue["state"] == "CLOSED" else "(not on board)")
        rows.append((order.get(status, 3), PRIORITIES.index(_priority(issue["labels"]))
                     if _priority(issue["labels"]) in PRIORITIES else 3, issue["number"],
                     status, _priority(issue["labels"]), _area(issue["labels"]), issue["title"]))
    current = None
    for _, _, number, status, priority, area, title in sorted(rows):
        if status != current:
            print(f"\n{status}")
            current = status
        print(f"  #{number:<4} {priority:<7} {area:<9} {title}")
    print(f"\n{board.url}")
    return 0


def cmd_show(args) -> int:
    # Not `gh issue view --comments`: with its output captured (not a terminal), gh prints
    # only the comments -- no title, state, labels or body -- so an issue without comments
    # came out empty. Ask for the fields and lay them out here.
    issue = gh_json("issue", "view", str(args.number), "--repo", REPO, "--json",
                    "number,title,state,labels,body,comments,projectItems,url")
    status = next((p["status"]["name"] for p in issue.get("projectItems") or []
                   if (p.get("status") or {}).get("name")), "not on the board")
    labels = ", ".join(label["name"] for label in issue["labels"]) or "no labels"
    print(f"#{issue['number']}  {issue['title']}")
    print(f"{issue['state']}, {status}  |  {labels}")
    print(issue["url"])
    print(f"\n{issue['body'].strip() or '(no body)'}")
    for comment in issue["comments"]:
        author = (comment.get("author") or {}).get("login", "?")
        when = comment["createdAt"][:16].replace("T", " ")
        print(f"\n--- {author}, {when}\n{comment['body'].strip()}")
    if not issue["comments"]:
        print("\n(no comments)")
    return 0


def _body(args) -> str | None:
    if getattr(args, "body_file", None):
        return args.body_file
    if getattr(args, "message", None):
        f = tempfile.NamedTemporaryFile("w", suffix=".md", delete=False, encoding="utf-8")
        f.write(args.message)
        f.close()
        return f.name
    return None


def cmd_status(status: str):
    def run(args) -> int:
        Board().set_status(args.number, status)
        print(f"#{args.number}: {status}")
        return 0
    return run


def cmd_done(args) -> int:
    state = gh_json("issue", "view", str(args.number), "--repo", REPO, "--json", "state")["state"]
    if state == "CLOSED":
        # Already closed -- by a "Fixes #N" commit, say; gh would drop the comment.
        if args.message:
            gh("issue", "comment", str(args.number), "--repo", REPO, "--body", args.message)
    else:
        extra = ["--comment", args.message] if args.message else []
        gh("issue", "close", str(args.number), "--repo", REPO, "--reason", "completed", *extra)
    Board().set_status(args.number, "Done")
    print(f"#{args.number}: closed, Done")
    return 0


def cmd_note(args) -> int:
    body = _body(args)
    if not body:
        raise SystemExit("normboard note: give the text with -m or -f")
    print(gh("issue", "comment", str(args.number), "--repo", REPO, "--body-file", body).strip())
    return 0


def cmd_new(args) -> int:
    labels = [f"priority: {args.priority}", f"area: {args.area}", *args.label]
    command = ["issue", "create", "--repo", REPO, "--title", args.title,
               "--body-file", _body(args) or _body(argparse.Namespace(message=" "))]
    for label in labels:
        command += ["--label", label]
    url = gh(*command).strip()
    number = int(url.rsplit("/", 1)[1])
    Board().set_status(number, "In Progress" if args.start else "Todo")
    print(url)
    return 0


def cmd_area(args) -> int:
    """File an issue under *area*: add its label and take off any other area's."""
    labels = gh_json("issue", "view", str(args.number), "--repo", REPO, "--json",
                     "labels")["labels"]
    edit = ["--add-label", f"area: {args.area}"]
    for label in labels:
        if label["name"].startswith("area: ") and label["name"] != f"area: {args.area}":
            edit += ["--remove-label", label["name"]]
    gh("issue", "edit", str(args.number), "--repo", REPO, *edit)
    print(f"#{args.number}: {args.area}")
    return 0


def cmd_areas(args) -> int:
    for name, (_, description) in AREAS.items():
        print(f"  {name:<9} {description}")
    return 0


def cmd_sync(args) -> int:
    board = Board()
    issues = gh_json("issue", "list", "--repo", REPO, "--state", "all", "--limit", "1000",
                     "--json", "number,state")
    items = gh_json("project", "item-list", str(PROJECT), "--owner", OWNER, "--format", "json",
                    "--limit", "1000")["items"]
    on_board = {i["content"]["number"]: i.get("status") for i in items
                if i.get("content", {}).get("number")}
    changed = 0
    for issue in sorted(issues, key=lambda i: i["number"]):
        want = "Done" if issue["state"] == "CLOSED" else (on_board.get(issue["number"]) or "Todo")
        if on_board.get(issue["number"]) != want:
            board.set_status(issue["number"], want)
            changed += 1
            print(f"#{issue['number']}: {want}", flush=True)
            time.sleep(0.8)  # well under GitHub's limits on rapid writes
    print(f"{changed} updated, {len(issues)} issues on the board")
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="normboard", description=__doc__.split("\n\n")[0].replace("``", ""),
                                 epilog=__doc__.split("\n\n", 1)[1].replace("``", ""),
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="command")
    sub.add_parser("all", help="every issue, Done included").set_defaults(func=cmd_list, all=True)
    p = sub.add_parser("show", help="one issue with its comments")
    p.add_argument("number", type=int)
    p.set_defaults(func=cmd_show)
    for name, status in (("start", "In Progress"), ("todo", "Todo")):
        p = sub.add_parser(name, help=f"move an issue to {status}")
        p.add_argument("number", type=int)
        p.set_defaults(func=cmd_status(status))
    p = sub.add_parser("done", help="close an issue as completed")
    p.add_argument("number", type=int)
    p.add_argument("-m", "--message", help="a closing comment")
    p.set_defaults(func=cmd_done)
    p = sub.add_parser("note", help="comment on an issue")
    p.add_argument("number", type=int)
    p.add_argument("-m", "--message")
    p.add_argument("-f", "--body-file")
    p.set_defaults(func=cmd_note)
    p = sub.add_parser("new", help="create an issue on the board")
    p.add_argument("title")
    p.add_argument("-a", "--area", choices=AREAS, required=True,
                   help="what it is about (normboard areas); every issue has one")
    p.add_argument("-p", "--priority", choices=PRIORITIES, default="medium")
    p.add_argument("-l", "--label", action="append", default=[], metavar="TAG",
                   help="a tag besides the area and the priority (ble, ota, ...); repeatable")
    p.add_argument("-m", "--message", help="the body")
    p.add_argument("-f", "--body-file", help="the body, from a file")
    p.add_argument("--start", action="store_true", help="put it straight into In Progress")
    p.set_defaults(func=cmd_new)
    p = sub.add_parser("area", help="file an issue under another area")
    p.add_argument("number", type=int)
    p.add_argument("area", choices=AREAS)
    p.set_defaults(func=cmd_area)
    sub.add_parser("areas", help="what each area covers").set_defaults(func=cmd_areas)
    sub.add_parser("sync", help="put every issue on the board").set_defaults(func=cmd_sync)
    args = ap.parse_args(argv)
    if not getattr(args, "func", None):
        args.func, args.all = cmd_list, False
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
