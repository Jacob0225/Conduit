# Verification

Update (2026-11-23): the 26.3 experiment has since been tested in-game for live
installation and switching off/on between separate local servers. See
`testing/hotwire-26.3/RESULT.md` and `testing/hotwire-26.3/SWITCHING.md` at the
repository root. The original build-only results below remain historical;
1.21.1 and 26.2 have not received the new 26.3 switching-screen implementation.

All three isolated projects completed `./gradlew build` successfully using
Java 25 and their copied Gradle 9.3 wrapper.

| Minecraft target | Fabric Loader | Build | Game launch / live loading |
| --- | --- | --- | --- |
| 1.21.1 | 0.18.5 | Passed (including remapJar) | Not tested |
| 26.2 | 0.19.5 | Passed | Not tested |
| 26.3 | 0.19.5 | Passed | Not tested |
| 1.20.1 | — | Excluded | Unsupported by this Hotwire checkout |

Each output jar was inspected for the review screen, shared Hotwire bridge,
and Fabric metadata. No `dev/moon/hotwire` implementation classes are bundled.
The bridge also compiles independently without Hotwire on the classpath.

The Mineagent skill's documented build/instance/testing tools were not exposed
in this session, so local Gradle checks were used. No game launch, gameplay,
Hotwire plan application, crash recovery, or performance result is claimed.

Before release, use a disposable instance with the matching official Hotwire
jar to verify: opening-prompt cancellation, plan cancellation, successful live
addition followed by reconnect, a blocked mod's restart fallback, missing
Hotwire's restart fallback, and switching between existing server mod sets.
