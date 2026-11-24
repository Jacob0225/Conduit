# Server switching — 2026-11-23

Implemented in the separate Fabric 26.3 experiment. Normal projects and the
older experimental version screens were not changed.

The join gate compares actual Hotwire runtime mod ids, versions, and on/off
state with the destination record. Managed mods absent from that destination
are turned off; matching disabled mods turn on; new or different versions go
through Hotwire's jar planner. All changes share one confirmed plan. Manually
installed mods outside Conduit's ownership records are not selected for removal.

Stash reconciliation now follows successful runtime switching. A refused plan
offers the restart fallback, which checks that destination jars are restored
and previous managed jars are stashed before exiting. Join uses a normal
connection with null transfer state. The review screen has an opaque background
so the server list no longer appears beneath its controls.

## Live verification

Same Java 25 / Fabric 0.19.5 / Minecraft 26.3 client process throughout:

1. Began with AppleSkin loaded and owned by an existing server record.
2. Connected to disposable server B (127.0.0.1:25675), requiring no managed mods.
3. Confirmed the plan `Turns off AppleSkin.` Hotwire's returned result and an
   independent runtime-inventory check succeeded; the jar was then stashed.
4. Used Conduit's Join button and entered B. Server logged login at 20:08:43.
5. Disconnected and connected to separate AppleSkin server C (127.0.0.1:25677).
6. Created C's install record and confirmed `Turns on AppleSkin.` The runtime
   check succeeded and the screen showed Ready to join.
7. Used Conduit's Join button and entered C. Server logged login at 20:11:37.

No restart or transfer was used between those steps. Input isolation was
validated on Xvfb :97. Servers were stopped afterward. Your modified server A
manifest (Decorative Blocks) was preserved; that mod was not part of this check.

Evidence is under `evidence/switching/`. This verifies one managed mod being
disabled and re-enabled between different servers, not every mod or version
update, shared dependency graph, or Hotwire refusal/recovery scenario. Blocked
mods still require the explicit restart fallback.
