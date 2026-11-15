# Judgement Day

Each folder is a separate Gradle project for one loader and Minecraft version:

| Folder | Loader | Minecraft |
|---|---|---|
| `forge-1.20.1` | Forge 47 | 1.20.1 |
| `neoforge-1.21.1` | NeoForge 21.1 | 1.21.1 |
| `fabric-1.20.1` | Fabric | 1.20.1 |

Build one from its own folder, for example `cd forge-1.20.1 && ./gradlew build`.
Each project has its own self tests in `tools/selftest`.
