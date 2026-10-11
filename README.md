# NeoForge Runtime On Fabric (NROF)

**Repository:** `nrof`  
**Developer:** cat1914  
**Target Minecraft version:** 1.21.1  
**Unofficial project**

> [!WARNING]
> This project is currently in **Alpha stage**. It is unstable, with many incomplete features and bugs. **NOT recommended for important survival worlds!**

> [!IMPORTANT]
> This project ports the full NeoForge runtime and contains source code owned by the NeoForge team.
> It is licensed under **LGPL-2.1-only**.
> This project is **NOT** official NeoForge software and is not affiliated with the NeoForge team.

## Introduction

NeoForge Runtime On Fabric (NROF) is a cross-loader runtime adaptation project for Minecraft 1.21.1.

This project ports the complete NeoForge runtime and adapts it to the Fabric loader. It allows native NeoForge mods to run under the Fabric loading environment without massive code modifications.

NROF aims to break the ecological barrier between Fabric and NeoForge, reuse existing NeoForge mod resources, and expand the compatibility of the Fabric mod ecosystem.

> [!WARNING]
> **Alpha Stage Notice:**  
> This project is in early development. Many NeoForge APIs are unimplemented. Mods may crash or have missing features. Use only for testing purposes. Do **NOT** use on valuable saves.

## Core Features

- 🚧 **NeoForge Runtime Port (Work in Progress):** The NeoForge runtime is being ported onto Fabric, reusing original NeoForge source code where possible.
- 🚧 **Low-intrusion Adaptation:** Some standard NeoForge mods may run without source code reconstruction.
- 🚧 **Partial API Compatibility:** Supports parts of the NeoForge event system, registration system, annotation system, config system, and resource loading logic.
- 🚧 **Lightweight Runtime:** Designed to provide a runtime adaptation layer without unnecessary bloat. Performance impact is still being evaluated.
- ✅ **Focused on 1.21.1:** The project is built specifically for Minecraft 1.21.1.

## Requirements

### Dependencies

1. Minecraft 1.21.1
2. Fabric Loader (for 1.21.1)
3. Fabric API (required dependency)
4. Java 21 (required for Minecraft 1.21.1)

### Compatibility Notes

- **Supported:** A subset of standard NeoForge 1.21.1 mods following official development conventions (limited coverage in Alpha).
- **Unsupported:** Mods relying on NeoForge internal undocumented APIs, platform hardcoding, or deep invasive modifications to the game core.

## Usage Guide (For Players)

NROF does not ship its own toolchain. Pre-built jar files are provided for end users.

1. Go to the repository Releases page and download the latest NROF Alpha build for Minecraft 1.21.1.
2. Place the downloaded NROF jar file into your `mods` folder.
3. Make sure Fabric Loader and Fabric API for 1.21.1 are already installed.
4. Launch the game for testing. **BACKUP YOUR WORLDS BEFORE USE!**

## FAQ

**1. Some NeoForge mods crash or fail to load?**  
This is expected in Alpha. Many NeoForge APIs are not yet implemented. You may open an issue with the mod name and crash log for future adaptation.

**2. Can Fabric mods and NeoForge mods run together?**  
Yes, generally. NROF is designed to be compatible with native Fabric mods, but conflicts may still occur. Both kinds of mods can work side by side in many cases.

**3. Does it support client and server?**  
NROF targets both client and dedicated server, though server-side functionality is also in Alpha testing and unstable.

## License

This project is licensed under the **GNU Lesser General Public License v2.1 only** (SPDX: `LGPL-2.1-only`). See [LICENSE](LICENSE) for the full license text.

Copyright (C) 2025–2026 cat1914  
Portions Copyright (C) NeoForged and contributors

This project contains code derived from [NeoForge](https://github.com/neoforged/NeoForge), which is licensed under LGPL-2.1-only. See [NOTICE](NOTICE) for third-party notices.

Corresponding source code: [https://github.com/cat1914/nrof](https://github.com/cat1914/nrof)  
*(Replace with the actual repository URL if different.)*

NROF is an unofficial project and is not affiliated with, endorsed by, or sponsored by NeoForged, FabricMC, Mojang, or Microsoft. Minecraft is a trademark of Mojang Synergies AB.

## Contribution

Issues and Pull Requests are welcome. Help us improve cross-loader compatibility for NROF.

When submitting issues, please attach crash logs and your mod list to help debug.