# Conduit — Architecture Design

## 1. Overview

**Conduit** is a Fabric mod (MC 1.21.1, Java 21, Loom 1.15) that synchronizes
required mods from a server to a connecting client. Security is the primary
design constraint: the server is treated as an *untrusted peer*, never as an
authority on what or where to download.

---

## 2. Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  SERVER                          │  CLIENT                       │
│                                  │                               │
│  ConduitServerMod                │  ConduitClient                │
│   └── ServerManifestProvider     │   └── ClientManifestHandler   │
│        └── ModManifest (GSON)    │        ├── ManifestVerifier   │
│                                  │        ├── InstalledModIndex  │
│  ServerPlayNetworking            │        ├── ModDownloadManager │
│   └── S2C: ManifestPacket ──────►│        │    └── ModrinthClient│
│                                  │        ├── HashVerifier       │
│                                  │        ├── DependencyResolver │
│                                  │        └── ReviewScreen (GUI) │
└─────────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Side | Role |
|---|---|---|
| `ServerManifestProvider` | Server | Reads `conduit-manifest.json`; builds and signs the `ManifestPacket` |
| `ManifestPacket` | Shared | S2C packet — contains only mod IDs, versions, hashes, and Modrinth IDs |
| `ClientManifestHandler` | Client | Receives packet; validates structure; dispatches to verifier |
| `InstalledModIndex` | Client | Scans the mods folder; builds a map of modId → version |
| `ManifestVerifier` | Client | Diffs manifest vs installed; produces lists of missing/mismatched |
| `ModDownloadManager` | Client | Queries Modrinth, downloads to staging, and verifies the hash |
| `HashVerifier` | Client | SHA-512 / SHA-256 verification before any file is moved to mods/ |
| `DependencyResolver` | Client | Resolves transitive deps via Modrinth dependency API |
| `ReviewScreen` | Client | Shows pending downloads to the user before anything is installed |

---

## 3. Network Protocol

### Packet Channel

```
Identifier: conduit:manifest_sync   (S2C, play phase)
```

### Sequence

```
Client connects
     │
     ▼
Server: ServerPlayConnectionEvents.JOIN fires
     │   server reads conduit-manifest.json
     │   server builds ManifestPayload
     │
     ▼
Server ──[ManifestPacket]──► Client
     │
     ▼ (client side)
ClientManifestHandler.receive()
  1. Validate payload schema (required fields present, no injected URLs)
  2. Validate all hashes are valid SHA-512 or SHA-256 hex strings
  3. Diff against InstalledModIndex
  4. If missing/outdated mods → open ReviewScreen
  5. User approves → ModDownloadManager runs
  6. Hash verified → move to managed folder
  7. Prompt restart
```

### ManifestPacket Wire Format (custom FriendlyByteBuf encoding)

```
[int]    schemaVersion          = 1
[int]    manifestVersion        (monotonic, for cache invalidation)
[String] serverName             (display only)
[int]    modCount
per mod:
  [String] modId
  [String] displayName
  [String] requiredVersion      (semver string)
  [boolean] required
  [String] modrinthProjectId    (may be empty string if absent)
  [String] hashAlgorithm        ("SHA-512" or "SHA-256")
  [String] expectedHash         (lowercase hex, 128 or 64 chars)
```

**What is NOT in the packet:**
- Download URLs
- File paths
- Executable content
- API keys

---

## 4. Manifest Format (server config file)

`config/conduit/manifest.json` on the server:

```json
{
  "schemaVersion": 1,
  "manifestVersion": 42,
  "serverName": "Jacob's Silly Server",
  "mods": [
    {
      "modId": "sodium",
      "displayName": "Sodium",
      "requiredVersion": "0.6.0",
      "required": true,
      "modrinthProjectId": "AANobbMI",
      "hashAlgorithm": "SHA-512",
      "expectedHash": "a3f1...e9b2"
    },
    {
      "modId": "iris",
      "displayName": "Iris Shaders",
      "requiredVersion": "1.8.0",
      "required": false,
      "modrinthProjectId": "YL57xq9U",
      "hashAlgorithm": "SHA-512",
      "expectedHash": "c8d4...1af3"
    }
  ]
}
```

### Schema Rules (enforced on both sides)

- `schemaVersion` must equal 1 (or the client rejects)
- `expectedHash` must match `^[0-9a-f]{64}$` (SHA-256) or `^[0-9a-f]{128}$` (SHA-512)
- `modrinthProjectId` must match `^[A-Za-z0-9]{8}$`
- No field may contain a URL or path separator
- All string fields are trimmed and length-limited (256 chars max)

---

## 5. Security Model

### Threat Model

| Threat | Mitigation |
|---|---|
| Malicious server sends download URL | Packet contains no URLs; client only calls hardcoded Modrinth/CF endpoints |
| Server sends forged hash | Client independently fetches hash from Modrinth/CF API; server hash is a secondary check |
| Man-in-the-middle intercepts API response | HTTPS only; Java TLS stack validates certificates |
| Server sends path traversal in mod ID | `modId` is validated against `^[a-z0-9_\-]{1,64}$` before any filesystem use |
| Downloaded file is tampered | SHA-512 verified against API-reported hash before file is moved out of staging |
| Dependency chain pulls in malware | Only Modrinth dependency IDs are followed; each dep is individually hash-verified |
| Unsigned / unknown file | Files that fail hash verification are deleted from staging; never installed |
| Arbitrary code execution via mod | Mods are JARs; standard Java sandbox applies; user sees review screen before install |

### Verification Chain

```
Modrinth API (HTTPS) ──► version object ──► files[].hashes.sha512
                                                    │
                                                    ▼
                              downloaded file ──► HashVerifier.verify()
                                                    │
                                              PASS ──► move to mods/conduit-managed/
                                              FAIL ──► delete, log error, skip
```

The server-provided hash serves as a **cross-check**: if the Modrinth API hash
and the server hash disagree, the file is rejected and the user is warned.

### No-URL Policy Enforcement

`ManifestPayload.readFrom()` rejects any string field containing
`http://`, `https://`, `/`, or `\`. The packet is discarded entirely if
any field fails this check.

---

## 6. Download Workflow

```
ManifestVerifier produces: List<ModEntry> toDownload

for each ModEntry:
  1. Try Modrinth:
       GET https://api.modrinth.com/v2/project/{modrinthProjectId}/version
           ?game_versions=["1.21.1"]&loaders=["fabric"]
       → pick version matching requiredVersion
       → extract files[0].url and files[0].hashes.sha512
  2. If Modrinth fails → mark as unavailable and warn the user

  3. Download file to:
       .minecraft/conduit-staging/{modId}-{version}.jar.tmp

  4. HashVerifier.verify(file, sha512):
       - compute SHA-512 of downloaded bytes
       - compare to API-reported hash
       - compare to server-reported hash (must match both)
       - on failure: delete .tmp, throw VerificationException

  6. Move .tmp → .minecraft/mods/conduit-managed/{modId}-{version}.jar

  7. DependencyResolver.resolveDeps(modrinthProjectId, version):
       - fetch dependency list from Modrinth
       - for each required dep: recurse from step 1
       - detect cycles; max depth = 10

  8. After all downloads: prompt user to restart
```

---

## 7. Dependency Resolution

```
DependencyResolver uses a BFS queue and a visited set of (modId, version).

Algorithm:
  queue = [root mods to download]
  visited = {}

  while queue not empty:
    entry = queue.dequeue()
    if entry in visited: continue
    visited.add(entry)

    deps = modrinth.getDependencies(entry.projectId, entry.version)
    for dep in deps:
      if dep.dependencyType == REQUIRED:
        if !InstalledModIndex.hasSatisfying(dep.projectId, dep.versionRange):
          queue.enqueue(dep)

  detect version conflict:
    if two entries in visited have same modId but different versions → ConflictException
```

---

## 8. Folder Structure

```
.minecraft/
├── mods/
│   └── conduit-managed/          ← only Conduit writes here
│       ├── sodium-0.6.0.jar
│       └── iris-1.8.0.jar
├── conduit-staging/              ← temp download dir; cleared on startup
│   └── *.jar.tmp
└── config/
    └── conduit/
        └── conduit-client.json   ← client settings (auto-install, trusted servers)

server/
└── config/
    └── conduit/
        └── manifest.json         ← server operator edits this
```

The client **never** writes directly to `mods/` root — only to `mods/conduit-managed/`.
This makes it easy to audit, disable, or clean up Conduit-managed files.

---

## 9. Project Source Layout

```
src/
├── main/
│   └── java/com/jacob0225/conduit/
│       ├── Conduit.java                        (server entrypoint)
│       ├── network/
│       │   ├── ManifestPayload.java            (shared packet data)
│       │   └── ConduitPackets.java             (channel IDs + registration)
│       ├── manifest/
│       │   ├── ModEntry.java                   (single mod descriptor)
│       │   ├── ServerManifest.java             (full manifest POJO)
│       │   └── ServerManifestProvider.java     (loads + validates manifest.json)
│       └── security/
│           └── InputValidator.java             (all regex checks in one place)
└── client/
    └── java/com/jacob0225/conduit/client/
        ├── ConduitClient.java                  (client entrypoint)
        ├── network/
        │   └── ClientManifestHandler.java      (receives + dispatches packet)
        ├── download/
        │   ├── InstalledModIndex.java          (scans mods folder)
        │   ├── ManifestVerifier.java           (diff logic)
        │   ├── ModDownloadManager.java         (orchestrates downloads)
        │   ├── ModrinthClient.java             (Modrinth API calls)
        │   ├── HashVerifier.java               (SHA-512/256 verification)
        │   └── DependencyResolver.java         (BFS dep resolution)
        └── ui/
            └── ModReviewScreen.java            (Minecraft GUI screen)
```
