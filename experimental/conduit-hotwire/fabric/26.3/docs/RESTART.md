# Restarting Minecraft

Fabric only reads `mods/` at startup, so after Conduit installs or swaps mods the game has to restart. Conduit can do that for you (Fabric 26.3 for now).

## How it works

1. Conduit shows what changes and asks. **Restart later** does nothing.
2. On **Restart Minecraft**, the game builds its own relaunch command:
   - Java: the same `java`/`javaw` the game is running on
   - JVM options: read from the JVM itself (works on Windows, Linux and macOS)
   - Classpath: `java.class.path`
   - Main class: Fabric's `KnotClient`, so launcher wrapper classes are skipped
   - Game arguments: exactly what Fabric received, so paths with spaces survive
   - Optionally `--quickPlayMultiplayer host:port` to rejoin the server
3. It starts `RestartHelper` (inside Conduit's jar, plain Java, 64 MB heap) and sends it the command over stdin.
4. Minecraft shuts down normally, saving options and any open world.
5. The helper waits for the old game to exit (up to 90 s), starts the new one once, and exits.

If Conduit can't do this safely it says **Close game**, and you reopen Minecraft from your launcher. That happens in a dev environment, when the JVM's options or Fabric's launcher class can't be found, or within 20 s of a Conduit restart (loop guard).

## Security and privacy

- Restarts only after you click. Servers can't trigger one.
- The helper only runs Java with Fabric's `KnotClient`, only for a request from the game that started it, and only once.
- The command carries your launcher's session arguments (`--accessToken` and so on), the same ones the launcher already put on the command line. They go to the helper over stdin and never touch disk. Logs show `<redacted>` instead.
- On Windows, if the command is longer than 30,000 characters, only the classpath moves into `.conduit/restart-classpath.args`. That file holds library paths and nothing secret.
- Before running the helper, Conduit checks that the helper classes in its jar on disk match the ones the game loaded.
- Nothing else runs: no scripts, no services, no admin rights, no downloads, no other processes inspected.
- The helper writes a short log to `logs/conduit-restart.log`.

## Launchers

Not yet tested by hand. What we expect:

| Launcher | Expected | Notes |
| --- | --- | --- |
| Official launcher | Works | The launcher shows the game as closed; the new game runs on its own. |
| Modrinth App | Works | Starts Knot directly instead of Modrinth's wrapper. Modrinth won't track play time or show logs for the new session. |
| Prism / MultiMC | Works | Prism's console won't show the new session. |
| ATLauncher | Works | |
| CurseForge | Works | Uses the official launcher underneath. |

Session tokens expire. If the launcher's token is old, the restarted game may not be able to join online servers. Restart from the launcher in that case.

## Compared with Reload Everything

| | Reload Everything | Conduit |
| --- | --- | --- |
| Relaunch | From a shutdown hook, 2 s after exit starts | Separate helper that waits for the process to exit |
| Windows arguments | Splits `sun.java.command` on spaces | Exact tokens from the JVM and Fabric |
| JVM options on Windows | Copies every system property | The JVM's real options |
| Logging | Full command, including the token | Redacted |
| Loop guard | Blocks every restart after the first | Blocks only within 20 s of a restart |
| Rejoin server | No | Optional Quick Play |

## Tests

`./gradlew test` covers command building, redaction and the spec format. It also runs a real end-to-end helper test: a fake game starts the helper and exits, and the test checks the new process starts only after the old one is gone and that the token never reaches the log.
