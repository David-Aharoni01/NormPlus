---
version: 1
slug: "app-src-main-kotlin-com-normplus-ui"
primary_target: "app/src/main/kotlin/com/normplus/ui"
related_targets: ["app/src/main/kotlin/com/normplus/ui/theme","app/src/main/kotlin/com/normplus/ui/components"]
---

# Norm+ app UI (the whole of `:app`)

**Scope and mode:** every screen and flow of Norm+, the Android companion for the Norm 2.
Operate. Material 3 behaviour throughout (navigation, system Back, switches, dialogs, sheets);
the world lends type, palette, density and one signature move, never the navigation model or
the controls. Phone only.

**Audience and job:** the owner daily, other Norm 2 owners later. Short visits: how is my
day, pull to sync, change a setting, choose apps, re-align the hands, update the watch.
Every number is the watch's own; an absent figure is absent, never zero.

**Chosen direction:** Dark Dial, pinned by the owner on 2026-10-09 from a reference image (a
dark watch-companion screen: near-black ground with a soft glow, rounded cards, pill
buttons, big bold titles, a round live preview of the watch), translated to Android, with
signal violet as the accent. It replaces Day Sheet (seed c2285a95), which the owner judged
too plain and monotone. The structure in the #95 brief stands; its Day Sheet visuals do not
(see #92's comment of 2026-10-09 for what changed per screen). Memorable moment: the watch,
drawn live.

## Direction contract

THESIS: Norm+ is a dark room with the watch in it: a near-black ground, the day's numbers on
rounded cards, and the watch itself drawn live as the hero of its own tab, its physical hands
where they really are. It refuses both the grey Material template and the health-app grid of
rings and coloured metric tiles.

OWN-WORLD: dark first. Ground near-black #0B0C0F with one soft violet glow behind each
screen's hero; cards #17181C with a #24262C hairline, raised #1F2126; text #F4F5F7, secondary
#A3A7B0. Signal violet #A68BFF for selection, actions, progress and the dial ring (on-violet
#1A1033, container #2A2147). Green #5BD98A on #12301F means "fine" and "goal reached"; amber
for fix-its, red for errors, each always icon plus words. Light mode designed, not inverted:
#F2F2F5 ground, white cards, violet deepened to pass 4.5:1. Roboto Flex: big bold titles,
large bold tabular figures, plain text for the rest. Radii 20-28 dp on cards, full pills on
buttons, chips and the navigation capsule.

STORY: a glance says how the day is going and that the watch is fine; the status pill grows
into a full-width banner in its state colour only when something needs fixing. Sending, sent,
not sent and waiting look the same everywhere. Charts show the records as they came, never
smoothed.

FIRST VIEWPORT: Today. A large bold "Today" title with Sync at its end, the green status pill
under it ("Norm 2 · 82% · synced 14:32"). The hero card: "Steps", 6,412 large and bold, "of
8,000 · 1,588 to go", a violet progress bar that fills and says "Goal reached" in green at the
goal. Below, a two-column grid of rounded tiles: sleep, heart rate, calories, distance with
active minutes. Then a Yesterday row that opens that day. The navigation bar floats as a
rounded capsule, Today · History · Watch, the selected tab violet on its tinted pill. Pull to
refresh syncs; no FAB.

FORM: Dark Dial, pinned by the owner from a reference image, so no direction roll (the Day
Sheet seed c2285a95 is retired); code-led, no comp, no image generation. Signature move: the
round live watch on the Watch tab, its face drawn by Norm+ with the physical hands at the
current time inside a violet ring and glow; swiping moves through the watch's own screens in
their order, and the same round previews are the thumbnails in Watch screens order. Every
preview is our own drawing, never the watch's artwork. Motion grammar: fade-through between
tabs, shared-axis between days, the dial's pager and the hands' sweep; a cut when animations
are removed.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

**Unresolved:** none at theme level. Per-screen decisions live on #97-#107; the finish
review and DESIGN.md run once over the whole app at the end of #92.
