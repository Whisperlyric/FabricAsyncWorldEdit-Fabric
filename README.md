# FAWE Fabric

Fabric platform layer for **FastAsyncWorldEdit 2.16.1** on Minecraft **26.1.2 / 26.2 / 26.3**.

This is an independent Fabric port of the FAWE platform layer, not a fork of the
upstream Bukkit/Paper plugin. It provides the Fabric entrypoint, world/player
adapters, chunk access, queue/task integration and the command bridge.

## Build

Java 25 is required for the compiler toolchain; Gradle itself can run on Java 21.

```powershell
.\gradlew.bat :26.1.2:build --console=plain
.\gradlew.bat :26.2:build --console=plain
.\gradlew.bat :26.3:build --console=plain
```

To build all nodes and collect the jars under `build/libs`:

```powershell
.\gradlew.bat buildAndGather
```

## Versions

| Node | Loader | Fabric API |
|---|---|---|
| 26.1.2 | 0.19.3 | 0.145.4+26.1.2 |
| 26.2 | 0.19.3 | 0.152.1+26.2 |
| 26.3 | 0.19.5 | 0.160.6+26.3 |

## Layout

```text
gradlew / gradlew.bat      # Gradle 9.5.1 wrapper
gradle/wrapper/            # wrapper jar + properties
build.gradle               # Replay preprocess nodes
settings.gradle            # version subprojects
common.gradle              # Loom/Java/Fabric API
versions/26.1.2/...
versions/26.2/...
versions/26.3/...
src/main/java/...          # platform sources
src/main/resources/...     # fabric.mod.json / mixins
```

## License

GPLv3; see `LICENSE` and `NOTICE.md`.
