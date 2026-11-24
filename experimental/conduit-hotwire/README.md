# Conduit + Hotwire experiment

Separate Fabric project copies live in `fabric/1.21.1`, `fabric/26.2`, and
`fabric/26.3`. Normal Fabric and NeoForge projects are untouched. These jars
have the `-hotwire-experimental` version suffix. Install one in place of normal
Conduit, never alongside it. Install the official matching Hotwire jar yourself;
Hotwire is optional and is neither copied into Conduit nor bundled.

The upstream repository was downloaded into `upstream/` at commit
`b65d1527b234dfff1234043692054cc87061cc69` (0.9.5.4-beta).
That checkout is ignored by Git. The integration calls the documented API via
an isolated reflection bridge in `shared/`. No Hotwire implementation is copied.
See upstream `API.md` and `LICENSE.md` for API and distribution terms.

## Flow

Conduit downloads mods through its existing installer. After installation,
choose **Load live**. Accept Hotwire's own opening prompt, then click Load live
again. Conduit submits the downloaded local jars in one tagged change request,
shows every plan line for confirmation, and finally offers Apply. Only a DONE
result with no work deferred to next launch enables Join. Futures are never
blocked on the game thread. Refusals, cancellation, exceptions, and missing
Hotwire fall back to an explicit Restart action.

The 26.3 experiment also compares the actual Hotwire runtime inventory before
joining a different server. It plans turning off previous Conduit-managed mods,
turning on matching disabled mods, and adding/updating missing target jars in a
single confirmation. Only after runtime success does it align the on-disk stash.
The 1.21.1 and 26.2 copies still retain their original server-switch restart path.
Hotwire's own blocked mods, dependency rules, and JVM limitations still apply.
There is no promise that every install or server switch can avoid restarting.

1.20.1 is excluded: this Hotwire checkout has no 1.20.1 target. The existing
folder named `Conduit Fabric/26.2` actually configured Minecraft 26.1 when this
experiment was created; the experimental 26.2 copy explicitly targets 26.2.

## Build

From one experimental version directory, run `./gradlew build`. Output is in
that directory's `build/libs`. The shared bridge is compiled into each client
source set. Use a disposable instance for testing. Full in-game integration
testing is required before distributing these experimental jars.
