# Conduit

Conduit syncs required server mods before joining. See GUIDE.md for server
setup, client settings, port forwarding, and manifest examples.

## Client config

On client startup Conduit writes `config/conduit/client.json` if it does not
already exist.

```json
{
  "_comment": "theme: conduit, emerald, amethyst, amber, monochrome",
  "theme": "conduit",
  "compactDownloadUi": false,
  "autoStartDownloads": false,
  "showDownloadDetails": false,
  "dimBackground": true,
  "showOptionalMods": true
}
```

The same settings are available in-game from the title screen Conduit button.

## Server config

The first server start creates config/conduit/conduit-server.properties and
config/conduit/manifest.json. The server settings include endpoint enablement,
bind address, port, worker threads, optional request logging, and diagnostics.

## Loader support

The current implementation targets Fabric on Minecraft 1.20.1 and Java 17.
Install Fabric API alongside Conduit; Mod Menu is optional and exposes the
in-game settings screen.
