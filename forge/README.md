# Conduit Forge Builds

This project builds Forge server adapters for 1.20.1, 1.21.1, 1.21.11, 26.1,
and 26.2. Run a target from this directory:

    .\build-forge.ps1 forge-1.20.1

Replace the project name with the desired version. Pass a second argument such
as tasks to run a task other than build. Artifacts are written to
forge/<target>/build/libs/.

The shared server manifest, configuration, validation, and HTTP endpoint are
functional in every target. The client-side automatic join interception remains
Fabric-only for now; Forge clients can load this jar but do not yet receive the
Conduit pre-join download screen.
