# Conduit Velocity

Companion plugin that moves Conduit's optional manifest discovery to the
client-to-Velocity login phase. Keep the normal Conduit mod installed on Fabric
backend servers and on clients that want automatic mod recommendations.

Clients without Conduit are allowed to join normally.

## Installation

1. Build the plugin or use `build/libs/conduit-velocity-1.1.0.jar`.
2. Put the jar in Velocity's `plugins/` folder and restart Velocity.
3. Edit `plugins/conduit-velocity/conduit-velocity.properties`.
4. Keep the backend Conduit HTTP endpoint reachable from players.

## Configuration

```properties
enabled=true
httpPort=25566

# Client-facing HTTP(S) base URL. Conduit appends /manifest.json.
# Leave blank to use http://<hostname-the-player-joined>:<httpPort>.
publicBaseUrl=https://mods.example.com/conduit

# Optional override selected from the hostname entered in Minecraft.
host.modpack.example.com=https://cdn.example.com/modpack
```

`publicBaseUrl` is not the private backend address. It must be a URL that the
player's computer can reach.

The plugin uses the same `conduit:http_port` login protocol as the Fabric mod.
It answers the backend server's query after handling discovery with the real
client, preventing Velocity from treating the query as unknown.
