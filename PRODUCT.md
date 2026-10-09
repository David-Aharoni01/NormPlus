# Product

<!-- impeccable:product-schema 1 -->

This record covers **Norm+**, the Android companion app in `app/` (with `:protocol` beneath
it). The Python tools in `tools/` (the watch emulator, the Android emulator launcher, the
firmware tools) are developer tooling, not the product.

## Platform

android

## Users

**Today: one owner, one watch.** The person who built Norm+ wears a Norm 2 every day and uses
Norm+ instead of the official NORM app (`com.normconnectappv2.watch`), which is disabled on
the phone. They also open it to check the watch after protocol or firmware work.

**Later: other Norm 2 owners**, installing it from the public repository. They will not know
the codebase, so first-run setup, permission requests and wording have to make sense to
someone who has never seen the repo. Their arrival is planned, not scheduled.

The job in both cases: keep the watch connected, get phone notifications and calls onto the
wrist, see today's activity, heart rate and sleep, and change the watch's settings, without
thinking about the link underneath.

## Product Purpose

Norm+ replaces the official companion app for the Norm 2 hybrid smartwatch. It does
everything the official app does for daily wear, and does it more reliably. The reverse
engineering it rests on stays out of sight.

Success: the owner never reaches for the official app; the watch stays connected for days
without anyone touching it; notifications and calls arrive on the wrist as they arrive on the
phone; a sync shows the day's numbers without a wait anyone notices.

## Positioning

Norm+ speaks the watch's own protocol, byte for byte, derived from the official app's code
and checked against the watch's own firmware, which can run on a PC and be tested there
before it reaches the hardware. That is why it can go further than the official app on the
same watch, with the same firmware:

- Hebrew and Arabic notifications are reordered into visual order before they are sent; the
  watch does no bidirectional text of its own.
- Notifications are filtered: a per-app whitelist, junk types (charging, media, progress,
  foreground-service chatter) dropped, bursts coalesced, exact repeats suppressed.
- The connection is held by a foreground service built to survive reboots, app updates,
  Bluetooth toggles and process death, and to reconnect on its own (on-device verification
  pending, #3, #4).
- Records are deleted from the watch after a complete read, so each sync reads only what is
  new, and the link is tuned for the duration of a record stream (952 sport records: 139 s
  before that tuning, 32 s after). No comparison with the official app's sync speed has been
  measured.

Planned beyond the same firmware: Norm+ ships its own build of the watch's main firmware,
which its users install from the app (#93). Today the app refuses to send it (see the hard
constraints); #93 holds what must be true first.

## Operating Context

- **The watch:** Norm 2. Physical minute and hour hands (no second hand) driven by steppers,
  over a round 360×360 AMOLED touch display; wireless charging; heart-rate sensor. It keeps
  **one** Bluetooth connection at a time, so Norm+, the official app and the PC tools cannot
  be connected at once.
- **Mostly in the background.** Norm+ runs as a foreground service with a persistent
  notification. People open the app for short visits: glance at today, pull to sync, change a
  setting, choose which apps may notify, re-align drifted hands, update the watch's
  firmware or re-send its original resources.
- **First run:** scan for or type in the watch's address, accept Android's "Pair with
  Norm2#…" consent dialog within 30 s, then the watch's own bind handshake.
- **Connecting is slow and sometimes stalls.** A cold connect takes about 8 s and can need
  more than one attempt before it goes through (the cause is under investigation, #48). The
  states people see are Scanning, Connecting, Setting up, Ready, Reconnecting and Disconnected.
- **Permissions:** Bluetooth (required), notifications, notification-listener access (for
  forwarding), phone state, call log, contacts and answering calls (for call support; optional,
  their denial disables only calls), and a battery-optimisation exemption for the always-on
  connection.

## Capabilities and Constraints

**Working, verified on the physical watch:** notification forwarding (with the filtering and
RTL above), phone calls (incoming, missed and ended, with the caller's name from contacts;
answer and reject from the watch), watch-hands calibration. Settings writes (brightness, do
not disturb, vibration, language and others) apply on the watch; reading settings back is not
yet verified there (#1).

**Working, verified against the emulated watch:** health sync of sport records, heart rate and
sleep sessions into a local database, with delete-after-sync on by default; resending the
watch's resource image over OTA (not yet run on the physical watch, #13).

**Screens:** pairing, dashboard (today: steps against goal, heart rate, sleep, calories,
battery, sync state), activity history with workout detail and sleep sessions, settings
(including connection health and notification apps), firmware, hands calibration.

**Hard constraints:**
- The app never crashes on a protocol surprise and never lets the connection die from one.
  Failures surface as typed states in the UI; the connection's own retry and reconnect logic
  recovers.
- Only the resource partition (update type 4) is sent from the app today. A main-MCU update
  is refused by `:protocol` and nothing in Norm+ allows it, because a watch that does not boot
  has no recovery path yet (#14). That changes only through #93, and CLAUDE.md's OTA rules
  stay binding until it does.
- A resource update erases the watch's live UI the moment it starts; an interrupted one leaves
  the watch without its screens until it is re-sent. It must not start within about 10 s of
  the watch booting.
- The watch has no command to delete or update a notification it has shown (planned through a
  firmware patch, #16–#20). The app must not promise it.
- The repository is public and the vendor's material is not: the app cannot ship vendor
  graphics, resources or code in tracked paths. The bundled resource image is copied in at
  build time from the private `NORM/` submodule; a build without it lacks that image.
- Kotlin, Jetpack Compose and Material 3; minSdk 30, compileSdk 35.

**Decided (2026-10-09, for the redesign, #92):**
- **Firmware updates are a feature for every user**, not a developer tool: Norm+ is to ship
  its own firmware for the watch (#93), and re-sending the original resources is the way back.
- **Technical details that ordinary users need not see live behind a separate Technical
  screen.** Connection health's fix-it warnings (Bluetooth off, battery restricted, a
  permission missing) are for everyone.
- **The app's text moves into string resources**, English only for now. Today it is written
  inline in Compose; only notification content handles RTL.

**Open decisions:**
- How other owners will get it (APK from GitHub releases, a store, or a build from source).
  An APK carries the vendor's images (the resource image now, a patched main firmware later),
  so this is a distribution question as well as a technical one.
- Which firmware build ships first (the notification-delete patch, #18, is the planned one).

## Brand Commitments

- **Name:** Norm+, package `com.normplus`. The launcher still says NormLink and the package
  is still `com.norm2hacked` until the rename (#94).
- **Existing asset, not confirmed as binding:** the launcher icon, a white watch body with a
  teal accent on deep navy (`app/src/main/res/drawable/ic_launcher_foreground.xml`,
  `launcher_background` #0D1B2A).
- Norm+ is not the vendor's product and must not present itself as one: no vendor logos, no
  vendor artwork.

## Evidence on Hand

- Measured: 952 sport records synced in 32 s, down from 139 s (#86); a cold connect about 8 s.
- Verified-on-device feature list: `docs/app.md`, "Verified on the watch".
- Realistic test data: the watch emulator writes real sport, heart-rate and sleep records with
  the watch's own firmware (`normwatch records`), so screens can be exercised with data the
  watch itself produced.
- **Absent, and not to be invented:** users other than the owner, testimonials, ratings,
  download counts, store listings, battery-life claims, and accuracy claims for heart rate,
  sleep or calories (those numbers come from the watch and Norm+ does not validate them).

## Product Principles

1. **The connection is the product.** Everything else depends on the link staying up quietly.
   Show its state honestly, recover on its own, and never make a person debug Bluetooth.
2. **Faithful to the watch.** Norm+ shows and promises only what the watch actually does.
   Where the watch cannot do something, the app says so rather than pretending.
3. **The reverse engineering stays out of sight.** Everyday tasks come first. Diagnostic and
   firmware tools are there for whoever needs them, and stay out of the way of whoever does not.
4. **Built for one, ready for strangers.** Nothing a person meets should need knowledge of the
   repo, the protocol or the emulator to understand.
5. **Careful with the watch.** Operations that can leave it without its UI say exactly what
   they will do and cannot be started by accident.
