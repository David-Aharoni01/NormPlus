---
version: 1
slug: "app-src-main-kotlin-com-normplus-ui"
primary_target: "app/src/main/kotlin/com/normplus/ui"
related_targets: ["app/src/main/kotlin/com/normplus/ui/theme","app/src/main/kotlin/com/normplus/ui/components"]
---

# Norm+ app UI (the whole of `:app`)

**Scope and mode:** every screen and flow of Norm+, the Android companion for the Norm 2.
Operate. Material 3 navigation, components and system Back throughout; the world lends type,
palette, density and one signature move, never layout, navigation or controls. Phone only.

**Audience and job:** the owner daily, other Norm 2 owners later. Short visits: how is my
day, pull to sync, change a setting, choose apps, re-align the hands, update the watch.
Every number is the watch's own; an absent figure is absent, never zero.

**Chosen direction:** Day Sheet, the tear-off block calendar (owner's choice on the
decision page, 2026-10-09; brief on #95, theme decisions on #96). Memorable moment: the
red-letter day.

## Direction contract

THESIS: Today is the top sheet of a tear-off calendar: one day, its number set huge, its
facts printed underneath as almanac lines; yesterday is the sheet below. It refuses the
health-app default of a progress ring over a grid of coloured metric cards.

OWN-WORLD: ink-blue backing #1F2A44 (shell, one reversed plate per screen, filled
buttons), sheet white, print black #121212, newsprint grey ground #E9E9E6, calendar red
#D62828 only for the red-letter state; amber for needs-fixing and a separate error red,
both always icon plus words in a container. Dark: a dark sheet on a deeper ink-blue, a
lighter red. One family, Roboto Flex: a condensed heavy cut for date numerals and big
figures, the text cut for the rest, tabular figures, tracked capitals for date lines. One
hairline perforated edge; no texture, curl, shadow stack or tear.

STORY: a glance says how the day is going and whether the watch is fine; the quiet line
grows only when something needs fixing. Sending, sent, not sent and waiting look the same
in every place. Charts show the records as they came.

FIRST VIEWPORT: Today. Ink-blue top app bar with "Today" and the quiet status line, Sync
action at its end. Beneath it the backing continues as a plate; the sheet hangs from it
with a perforated hairline top: "THURSDAY · 9 OCTOBER" in tracked capitals, the steps
numeral condensed and roughly a third of the sheet's height, "of 8,000 steps" and one
thin progress line, then the almanac lines in small tabular type. The edge of yesterday's
sheet below it opens that day. Navigation bar: Today · History · Watch. Pull to refresh
syncs; no FAB.

FORM: Day Sheet, first on my ordered list (Impeccable's pick, chosen by the owner over the
rolled Tide Table); seed key c2285a95; code-led, no comp. Signature interaction: the
numeral turns calendar red the moment the goal is met, with the words "Goal reached" and a
TalkBack sentence. Motion grammar: Material shared-axis X between days, fade-through
between tabs, a cut when animations are removed; nothing else moves.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

**Unresolved:** none at theme level. Per-screen decisions live on #97-#107; the finish
review and DESIGN.md run once over the whole app at the end of #92.
