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
  "showOptionalMods": true,
  "curseForgeApiKey": ""
}
```

`curseForgeApiKey` enables CurseForge as a fallback source when a required mod
cannot be resolved from Modrinth.

The same settings are available in-game from the title screen Conduit button.

## Server config

The first server start creates config/conduit/server.properties and
config/conduit/manifest.json. The server settings include endpoint enablement,
bind address, port, worker threads, optional request logging, and diagnostics.

## Loader support

Fabric 26.1 remains the client implementation. Forge server builds are set up
for 1.20.1, 1.21.1, 1.21.11, 26.1, and 26.2 in the forge directory. See
GUIDE.md for commands and the current client-port limitation.
