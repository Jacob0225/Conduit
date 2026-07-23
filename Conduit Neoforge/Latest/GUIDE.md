# Conduit Guide

Conduit keeps every configuration file under config/conduit/.

## Client setup

Install Conduit in the client's mods folder. On first launch it creates:

~~~
config/conduit/
  client.json
  servers/
~~~

Use the gear button labeled CONDUIT on the title screen to change the UI theme
and download behavior. The same values can be edited while Minecraft is closed
in config/conduit/client.json.

compactDownloadUi makes the download dialog even shorter. autoStartDownloads
starts required downloads immediately after the manifest is checked. Keep it
off when players should review each server first.

curseForgeApiKey is optional. It lets Conduit fall back to CurseForge when a
mod is not available from Modrinth. Do not share this key publicly.

## Server setup

Install Conduit on the server and start it once. It creates:

~~~
config/conduit/
  conduit-server.properties
  manifest.json
~~~

Edit manifest.json, then restart the server. The generated template uses the
simple schema: each entry is a Modrinth slug and whether it is required.
Conduit resolves the matching version and hash while the server starts.

~~~json
{
  "schemaVersion": 2,
  "serverName": "Example Server",
  "mods": [
    { "slug": "sodium", "required": true },
    { "slug": "iris", "required": false }
  ]
}
~~~

conduit-server.properties controls the HTTP endpoint clients contact during login:

~~~properties
enabled=true
bindAddress=0.0.0.0
httpPort=25566
publicBaseUrl=
workerThreads=4
logRequests=false
diagnosticsEnabled=true
~~~

`httpPort` may be any free TCP port from 1 through 65535. Conduit advertises the
configured value to its client mod through the normal Minecraft login handshake,
so clients do not need to configure or guess it. Forward that chosen TCP port in
addition to the normal Minecraft server port. Leave
bindAddress=0.0.0.0 for direct public hosting. Use 127.0.0.1 only when a
reverse proxy on the same machine exposes the endpoint.

Leave `publicBaseUrl` blank to use the hostname the player joined and the
configured `httpPort`. Set it to a client-facing HTTP(S) base URL, such as
`https://mods.example.com/conduit`, when a proxy or different public address
serves the endpoint. Conduit appends `/manifest.json` to this URL.

With diagnostics enabled, opening http://your-server:25566/ returns a small
status response. The manifest is available at /manifest.json.

## Velocity proxy setup

Install the companion jar from `Conduit-Velocity/build/libs/` in Velocity's
`plugins/` folder. Configure its client-facing URL in
`plugins/conduit-velocity/conduit-velocity.properties`. The companion performs
discovery with the real client during proxy login and answers the backend mod's
login query. Players without the Conduit client mod are still allowed to join.

## Updating a mod list

1. Stop the server.
2. Update config/conduit/manifest.json.
3. Start the server and check the log for the number of served mods.
4. Have clients reconnect. Login pauses while Conduit validates the manifest;
   clients needing changes are disconnected into the compact download screen.

## Exact Conduit mod sets

Conduit keeps a history of every jar it installs for each server. When switching
servers, it moves every Conduit-managed jar that is absent from the destination
server's current record into that server's stash, then restores only the
destination jars. For example, joining a server with voice chat and then one
with Create stashes the voice-chat jar before restart.

Conduit does not move jars you installed manually. This keeps the swap safe,
but server manifests must include every Conduit-managed mod they require.

## Forge versions

The forge directory contains server-ready Forge projects for Minecraft 1.20.1,
1.21.1, 1.21.11, 26.1, and 26.2. From the forge directory, build one target:

~~~text
.\build-forge.ps1 forge-1.20.1
~~~

Replace forge-1.20.1 with the target name for the desired Minecraft version.
The resulting jar is written to that target's build/libs folder.

Each Forge jar uses the shared Conduit manifest, validation, server config, and
HTTP endpoint. Forge client-side pre-join download interception is not ported
yet; the current download UI and title-screen settings button remain Fabric
client features.
