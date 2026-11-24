# Fabric 26.3 Hotwire integration test — 2026-11-23

Overall: partial pass. Live installation worked without restarting, but
Conduit's automatic Join button has a reconnect bug.

Environment: Java 25, Fabric Loader 0.19.5, Fabric API 0.160.5+26.3,
Conduit 1.1.0-hotwire-experimental, official separately installed
Hotwire 0.9.5.6-beta+mc26.1-26.3. A disposable offline server bound to
127.0.0.1:25673 exposed its Conduit endpoint on 127.0.0.1:25674.
Client ran on isolated Xvfb :97; Mineagent's underlying Xvfb acceptance
check passed, with host pointer/focus unchanged. Its newer named MCP
testing tools were unavailable, so local Gradle/server/display tools were used.

## Verified

- Dedicated server starts and exposes a valid manifest.
- Client starts with Conduit and the separate official Hotwire jar.
- Login discovers the HTTP port and interrupts joining for missing AppleSkin.
- Download installs AppleSkin 3.0.10+mc26.3 with its pinned SHA-512 hash.
- Hotwire's timed opening prompt appears and requires acceptance.
- Conduit displays the plan, then a separate Apply confirmation.
- Hotwire logs `AppleSkin is in.` and reloads resources including AppleSkin.
- Conduit displays Ready to join without a client restart.
- Manual Direct Connection subsequently enters the server in the same JVM;
  server logs `ConduitTest joined the game` at 18:48:29.
- No client crash report was generated during this sequence. The client
  process subsequently ended with exit code 143 (termination), after world
  entry; this short test does not establish long-term stability.

## Findings

Conduit's Join action failed with `This server does not accept transfers`.
`ModReviewScreen.skipAndJoin()` passes a newly created, non-null TransferState
to ConnectScreen.startConnecting. The normal Direct Connection path succeeds.
After the test, the requested 26.3 correction replaced that TransferState with
null, selecting an ordinary join. The corrected experimental jar rebuilt
successfully; this corrected button has not yet been retested in-game.

Hotwire also logged a nonfatal warning that AppleSkin could not be listed in
Mod Menu because `cloth-config` was absent. Live loading and world entry still
succeeded. AppleSkin-specific HUD/food tooltip behavior was not exercised.

This test covers one newly added mod, not arbitrary mods, mod updates,
unloading/server-set swaps, or crash recovery. Server and client were stopped
after testing.

## Evidence

- `evidence/client.log`
- `evidence/server.log`
- `evidence/live-loaded.png`
- `evidence/join.png` (automatic Join failure)
- `evidence/manual-reconnect.png` (successful world entry)
- `evidence/isolation.png`
