Conduit + Hotwire test client (Minecraft 26.3, Fabric)

1. In the Modrinth App, create an instance: Minecraft 26.3, loader Fabric.
2. Open the instance folder and copy every .jar in this folder into its "mods" folder:
     conduit-1.1.0-hotwire-experimental.jar   (this repo's build, fabric/26.3)
     hotwire-0.9.5.6-beta+mc26.1-26.3.jar     (required for Load live)
     fabric-api-0.166.2+26.3.jar              (required by Conduit and Hotwire)
     modmenu-21.0.1.jar                       (optional: opens Conduit's settings)
   Don't add AppleSkin; Conduit downloads it when you join.
3. Start the test server: testing\instances\hotwire-ui-26.3\start-server.bat
4. In game: Multiplayer > Direct Connection > 127.0.0.1:25673

Hotwire, Fabric API and Mod Menu were downloaded from Modrinth's CDN and checked against Modrinth's SHA-512 hashes.
