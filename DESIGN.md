---
name: Norm+
description: Dark Dial, the Android companion for the Norm 2 hybrid watch, with the watch drawn live as its hero
colors:
  ground: "#0B0C0F"
  card: "#17181C"
  card-hairline: "#24262C"
  raised: "#1F2126"
  ink: "#F4F5F7"
  ink-secondary: "#A3A7B0"
  signal-violet: "#A68BFF"
  on-violet: "#1A1033"
  violet-container: "#2A2147"
  on-violet-container: "#DCD2FF"
  glow-violet: "#7B5CFF"
  fine-green: "#5BD98A"
  fine-container: "#12301F"
  fix-amber: "#FFB547"
  fix-container: "#33260C"
  fail-red: "#FF6B6B"
  fail-container: "#3A1517"
  outline: "#6B6F78"
  display-black: "#000000"
  dial-bezel: "#3A3C42"
  dial-hand: "#E9EAEE"
  sleep-deep: "#7C5CF0"
  sleep-light: "#C3B3FF"
  pale-ground: "#F2F2F5"
  sheet-white: "#FFFFFF"
  pale-hairline: "#E3E3E8"
  ink-dark: "#14161B"
  ink-dark-secondary: "#5C606B"
  deep-violet: "#6246E0"
  pale-violet-container: "#ECE6FF"
  deep-green: "#16753E"
  deep-red: "#B3261E"
typography:
  screen-title:
    fontFamily: "Roboto Flex"
    fontSize: "34sp"
    fontWeight: 800
    lineHeight: "40sp"
    letterSpacing: "-0.5sp"
  hero-figure:
    fontFamily: "Roboto Flex"
    fontSize: "64sp"
    fontWeight: 800
    lineHeight: "64sp"
    letterSpacing: "-1sp"
    fontFeature: "tnum"
  tile-figure:
    fontFamily: "Roboto Flex"
    fontSize: "28sp"
    fontWeight: 800
    lineHeight: "32sp"
    letterSpacing: "-0.4sp"
    fontFeature: "tnum"
  section-title:
    fontFamily: "Roboto Flex"
    fontSize: "18sp"
    fontWeight: 700
    lineHeight: "24sp"
  title:
    fontFamily: "Roboto Flex"
    fontSize: "14sp"
    fontWeight: 600
    lineHeight: "20sp"
  body:
    fontFamily: "Roboto Flex"
    fontSize: "16sp"
    fontWeight: 400
    lineHeight: "24sp"
    letterSpacing: "0.2sp"
  label:
    fontFamily: "Roboto Flex"
    fontSize: "14sp"
    fontWeight: 600
    lineHeight: "20sp"
    letterSpacing: "0.1sp"
rounded:
  field: "8dp"
  tile: "20dp"
  card: "24dp"
  hero: "28dp"
  pill: "9999dp"
spacing:
  xxs: "2dp"
  xs: "4dp"
  s: "8dp"
  m: "12dp"
  l: "16dp"
  xl: "24dp"
  xxl: "32dp"
  gutter: "16dp"
  card-padding: "20dp"
  touch-target: "48dp"
components:
  card:
    backgroundColor: "{colors.card}"
    rounded: "{rounded.card}"
    padding: "20dp"
  hero-card:
    backgroundColor: "{colors.card}"
    textColor: "{colors.ink}"
    typography: "{typography.hero-figure}"
    rounded: "{rounded.hero}"
    padding: "24dp"
  metric-tile:
    backgroundColor: "{colors.card}"
    typography: "{typography.tile-figure}"
    rounded: "{rounded.tile}"
  button-primary:
    backgroundColor: "{colors.signal-violet}"
    textColor: "{colors.on-violet}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "8dp 20dp"
  button-tonal:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.signal-violet}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "8dp 20dp"
  status-pill-fine:
    backgroundColor: "{colors.fine-container}"
    textColor: "{colors.fine-green}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
  banner-fix:
    backgroundColor: "{colors.fix-amber}"
    textColor: "#2A1A00"
    rounded: "{rounded.tile}"
  navigation-capsule:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.ink-secondary}"
    rounded: "{rounded.pill}"
    height: "72dp"
  navigation-capsule-selected:
    backgroundColor: "{colors.violet-container}"
    textColor: "{colors.signal-violet}"
    rounded: "{rounded.pill}"
---

# Design System: Norm+

## Overview

**Dark Dial.** Norm+ is a dark room with the watch in it: a near-black ground, the day's numbers on rounded cards, and the Norm 2 itself drawn live as the hero of its own tab, its physical hands where they really are, inside a violet ring with one soft glow. It is an Operate surface: Material 3 behaviour throughout (navigation, system Back, switches, dialogs, sheets), with the world lending only type, palette, density and one signature move.

It refuses the grey Material template, and the health-app grid of rings and coloured metric tiles: fitness character and watch character appear in moderation only, never as rings, streaks, badges, leather or brushed metal, and never as a hacker tool (no terminal type, hex or protocol words on everyday screens; those live on Technical, set in the same face).

Dark first; a light mode designed to match, following the phone's setting. No Dynamic Color.

## Colors

One accent and a few state colours, each with a job.

- **Ground and cards.** Near-black ground `#0B0C0F`, cards `#17181C` edged by a `#24262C` hairline, raised controls `#1F2126`. Text `#F4F5F7`, secondary `#A3A7B0`.
- **Signal violet `#A68BFF`** is selection, actions, progress, the dial's ring and the selected tab. Its container `#2A2147` carries the selected-tab pill and quiet violet surfaces.
- **The glow `#7B5CFF`** sits behind one hero per screen, soft and low, never as a gradient fill on content.
- **Green `#5BD98A` means "fine" and "goal reached"**, and nothing else: the status pill, goal days in History, Sent marks.
- **Amber `#FFB547` is a fix-it, red `#FF6B6B` a failure.** Both always come as icon plus words in their container, never colour alone.
- **Light mode** uses `#F2F2F5` ground, white cards and deep violet `#6246E0`, so every text pair stays at 4.5:1 or better (the figures are noted in `Color.kt`).
- **The watch display** is pure black with a `#3A3C42` bezel and `#E9EAEE` hands, as the real AMOLED and steel are.

## Typography

One family, **Roboto Flex**, bundled (OFL). Big bold titles (34 sp, weight 800) head every screen, collapsing to 22 sp on scroll. Figures are large, bold and tabular: the hero figure is 64 sp and shrinks to fit, so 40,000 fits at font scale 1.3; tile figures are 28 sp with their unit set smaller beside them ("72 bpm"). Everything else follows the Material roles at weight 400-600. All sizes are sp, and every line of text keeps its own direction in right-to-left layouts.

## Layout

A 16 dp gutter, an 8 dp-based spacing scale (4, 8, 12, 16, 24, 32), cards padded 20 dp. Each tab is one scrolling column under a large-title header with its status pill; the floating navigation capsule (Today · History · Watch, 72 dp) sits over the content, which scrolls beneath it. Edge-to-edge throughout; every touch target is at least 48 dp. Phone only.

## Elevation & Depth

Flat and tonal: depth comes from the card on the ground and the hairline around it, not shadows. The two exceptions are the floating navigation capsule, which casts a soft shadow so it reads as floating, and the hero glow, which is light, not depth.

## Shapes

Round, like the watch. Cards 24 dp, hero cards 28 dp, tiles and banners 20 dp, fields 8 dp; buttons, chips, status pills and the navigation capsule are full pills. The dial and the watch-screen previews are true circles.

## Components

- **Large-title header with status pill.** The pill says the watch is fine ("Norm 2 · 82% · synced 14:32"); when something needs fixing it becomes a full-width rounded banner in its state colour with its one fix.
- **Steps hero card.** The day's figure, "of 7,000 · 1,588 to go", a violet progress bar that turns green with "Goal reached" at the goal. An absent figure says "Not reported yet" in words.
- **Metric tiles** in a two-column grid, and **day rows** (date badge, title, summary, the goal chip under the words).
- **The live dial (signature).** Norm+'s own drawing of the watch: hour and minute hands at the current time on the face, the watch's screens as round previews in a pager with an elongated violet page dot, the same previews as thumbnails in Watch screens. Every preview is our own drawing, never the watch's artwork. Hands calibration uses it to highlight the moving hand.
- **Settings rows** send each change at once and carry **one set of state marks** everywhere: Sending, Sent, Not sent (with Retry), Waiting.
- **Fix-it cards and notices** (amber and red), **pill buttons** (primary violet, tonal, quiet), and the **full-screen flow** for first run, calibration and the firmware update.
- **Charts** draw the records as they came: one bar per half hour or per day, goal days green, a dashed goal line, heart rate as daily ranges, sleep stacked by stage. Never smoothed; gaps stay empty.

## Do's and Don'ts

- Do keep green for "fine" and goals, amber for fix-its, red for failures, violet for everything you can act on.
- Do say an absent figure is absent, in words; never show it as zero.
- Do draw the watch as Norm+'s own drawing; don't use or imitate the watch's own screens.
- Don't smooth a chart, add rings, streaks or badges, or add a second glow to a screen.
- Don't put protocol words, hex or terminal type on an everyday screen; Technical holds them, in the same face.
- Don't break Material behaviour (Back, switches, dialogs) for the look; the world themes Material, it doesn't replace it.
