# Conduit server test matrix

This harness builds Conduit, downloads the official Minecraft server jars and
dedicated-server loaders, installs each matching Conduit jar, starts every server, checks
the diagnostic and manifest HTTP endpoints, and stops each server cleanly.

The first run downloads a substantial amount of data. Downloads are cached in
`testing/cache/`, and installed servers are reused from `testing/instances/`.

## Requirements

- Python 3.10 or newer.
- Java 17, 21, and 25 for the current matrix. The harness automatically finds
  JDKs in the normal Linux, macOS, and Windows installation folders.
- Internet access for the first run.
- Enough disk space for eight modded server installations.

If a JDK is installed somewhere unusual, set its location explicitly:

```bash
export JAVA_17_HOME=/path/to/jdk-17
export JAVA_21_HOME=/path/to/jdk-21
export JAVA_25_HOME=/path/to/jdk-25
```

On Windows, running this from WSL is recommended because the repository only
contains Unix Gradle wrappers.

Running the harness writes `eula=true` into every test server. By running it,
you confirm that you have read and accepted the Minecraft EULA.

## Commands

Show the matrix without downloading anything:

```bash
python3 testing/test_matrix.py list
```

Download and install the official server software without building or starting
Conduit:

```bash
python3 testing/test_matrix.py download --jobs 2
```

Build, provision, start, check, and stop everything:

```bash
python3 testing/test_matrix.py all --jobs 2
```

Test one server while developing:

```bash
python3 testing/test_matrix.py all --target fabric-1.21.1
```

Reuse existing Conduit jars:

```bash
python3 testing/test_matrix.py smoke --skip-build --jobs 2
```

Logs are written to `testing/logs/`. The server installations remain available
for manual client testing in `testing/instances/`; their ports are listed by
the `list` command.

## Expanding to 13 cases

Edit `testing/servers.json` and duplicate the closest entry. Every entry needs
a unique `id`, `minecraftPort`, and `conduitPort`. This lets multiple cases use
the same project/version with different manifests or network settings without
creating another client installation.

The script currently creates an empty schema-2 manifest to test startup and
transport. Before a manual download-flow test, edit that instance's
`config/conduit/manifest.json`, restart it, and join with the matching client.

Minecraft server jars come from Mojang's version manifest. Fabric server
launchers come from Fabric Meta and Fabric API comes from the official Fabric
Maven. Forge and NeoForge installers come from their official Maven repositories
and are run with `--installServer`.
