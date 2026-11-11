# Install T3 Craft

You need **Minecraft: Java Edition** and [T3 Code](https://t3.codes). You do not need to know what Fabric is, and you do not need to install Java.

There are three ways in. Pick the first one unless you have a reason not to.

| | For | Time |
| --- | --- | --- |
| [One command](#one-command-official-minecraft-launcher) | The official Minecraft Launcher on macOS, Windows or Linux | About a minute |
| [The `.mrpack`](#other-launchers-the-mrpack) | Modrinth App, Prism Launcher and other launchers that import Modrinth packs | About a minute |
| [By hand](#by-hand) | An existing Fabric setup, or a dedicated server | A few minutes |

Every file below is attached to the [latest release](https://github.com/alexandros-lekkas/t3craft/releases/latest):

| File | What it is |
| --- | --- |
| `install.sh` | Installer for the official Minecraft Launcher on macOS and Linux |
| `install.ps1` | The same installer for Windows |
| `t3craft-<version>.mrpack` | The T3 Craft pack in Modrinth format |
| `t3craft-<version>.jar` | The mod alone |
| `SHA256SUMS.txt` | SHA-256 checksums of the four files above |

## One command (official Minecraft Launcher)

1. Open the [Minecraft Launcher](https://www.minecraft.net/download) once and sign in, if you never have. Then **quit it** (the launcher rewrites its installation list when it closes, so it has to be closed while the installer runs).
2. Run the installer.

   **macOS or Linux**, in Terminal:

   ```sh
   curl -fsSL https://github.com/alexandros-lekkas/t3craft/releases/latest/download/install.sh | bash
   ```

   **Windows**, in PowerShell:

   ```powershell
   irm https://github.com/alexandros-lekkas/t3craft/releases/latest/download/install.ps1 | iex
   ```

3. Open T3 Code. Open the Minecraft Launcher, choose **T3 Craft** next to the Play button and press **Play**. On the title screen click **T3 World**.

The first Play downloads Minecraft 26.3, Fabric and the Java runtime Minecraft ships with. The launcher does that by itself.

### What the installer changes

It adds one installation and leaves your other installations, worlds and mods alone.

| Where | What |
| --- | --- |
| `versions/fabric-loader-0.20.1-26.3/` in the launcher folder | The Fabric Loader profile for Minecraft 26.3, the same file the Fabric installer writes |
| A new game folder beside the launcher folder (below) | `mods/` with T3 Craft, Fabric API, Sodium, Lithium, AppleSkin and Mouse Tweaks |
| `launcher_profiles.json` in the launcher folder | One added installation named *T3 Craft*. The previous file is kept as `launcher_profiles.json.t3craft-backup` |

| | Launcher folder | T3 Craft game folder |
| --- | --- | --- |
| macOS | `~/Library/Application Support/minecraft` | `~/Library/Application Support/minecraft-t3craft` |
| Windows | `%APPDATA%\.minecraft` | `%APPDATA%\.minecraft-t3craft` |
| Linux | `~/.minecraft` | `~/.minecraft-t3craft` |

Every download is checked against a checksum pinned in the installer before anything is written. If a download fails, the launcher is open, or the launcher's installation list cannot be read, the installer stops and changes nothing.

### Update, slim down, remove

- **Update:** run the same command again. It replaces the mods it installed and keeps your worlds, your settings, any mods you added yourself, and any changes you made to the installation (name, memory, game folder).
- **Only T3 Craft and Fabric API**, without the four extra mods: `curl -fsSL https://github.com/alexandros-lekkas/t3craft/releases/latest/download/install.sh | bash -s -- --minimal`, or on Windows run `$env:T3CRAFT_MINIMAL = 1` before the install command.
- **Remove:** delete the *T3 Craft* installation in the launcher's **Installations** tab, then delete the T3 Craft game folder. Your worlds made in T3 Craft are in its `saves/` folder; move them out first if you want to keep them.

### If it stops

| Message | Do this |
| --- | --- |
| *the Minecraft Launcher was not found* | Install the launcher, open it once, sign in, quit it, and run the command again. On Linux the installer looks in `~/.minecraft` and the Flatpak folder; for any other place, set `T3CRAFT_MINECRAFT_DIR` to the launcher folder first. |
| *the Minecraft Launcher is open* | Quit the launcher and run the command again. |
| *could not download* | Check your connection and run it again. Nothing was installed. |
| *does not match its pinned checksum* | Run it again. If it repeats, [open an issue](https://github.com/alexandros-lekkas/t3craft/issues/new/choose). Nothing was installed. |

## Other launchers: the `.mrpack`

Download `t3craft-<version>.mrpack` from the [latest release](https://github.com/alexandros-lekkas/t3craft/releases/latest) and import it:

- **Modrinth App:** the **+** button, then **From file**.
- **Prism Launcher:** **Add Instance**, then **Import**.

The pack carries T3 Craft itself and asks the launcher for Minecraft 26.3, Fabric Loader 0.20.1 and the mods in the table below, which it downloads from Modrinth. These launchers bring their own Java; if yours asks, Minecraft 26.3 needs Java 25.

T3 Craft is not listed on Modrinth yet, so the pack is not in the Modrinth App's search. Import the file.

## By hand

For a Fabric setup you already have:

1. Minecraft: Java Edition **26.3**.
2. **Fabric Loader 0.19.5 or newer** for 26.3, from the [Fabric installer](https://fabricmc.net/use/installer/). The pack uses 0.20.1.
3. **Java 25 or newer.** The official launcher supplies it. With another launcher, select a Java 25 runtime such as [Temurin 25](https://adoptium.net/temurin/releases/?version=25).
4. In the game folder's `mods/`:
   - `t3craft-<version>.jar` from the [latest release](https://github.com/alexandros-lekkas/t3craft/releases/latest)
   - [Fabric API](https://modrinth.com/mod/fabric-api/versions?g=26.3&l=fabric) for 26.3, version **0.161.0+26.3 or newer**. The pack uses 0.165.2+26.3.

A dedicated server takes the same two jars in its `mods/` folder; see [the reference](REFERENCE.md#shared-office-dedicated-server).

## What is in the pack

| Mod | Version | In `--minimal` |
| --- | --- | --- |
| T3 Craft | the release you downloaded | yes |
| [Fabric API](https://modrinth.com/mod/fabric-api) | 0.165.2+26.3 | yes |
| [Sodium](https://modrinth.com/mod/sodium) | 0.9.2 for 26.3 | no |
| [Lithium](https://modrinth.com/mod/lithium) | 0.26.3 for 26.3 | no |
| [AppleSkin](https://modrinth.com/mod/appleskin) | 3.0.10 for 26.3 | no |
| [Mouse Tweaks](https://modrinth.com/mod/mouse-tweaks) | 2.31 for 26.3 | no |

Minecraft 26.3 and Fabric Loader 0.20.1. No shaders, no resource packs and no `options.txt`: your settings are yours. The other mods are downloaded from Modrinth and are never re-hosted; they are credited in [CREDITS.md](../CREDITS.md).

## Check a download

```sh
shasum -a 256 -c SHA256SUMS.txt        # macOS; on Linux: sha256sum -c SHA256SUMS.txt
```

## For maintainers

`modpack/pack.json` pins the pack: the Minecraft and Fabric Loader versions and, for every mod, its Modrinth file, size and hashes. `modpack/fabric-loader-<loader>-<minecraft>.json` is the launcher profile from Fabric's meta server.

```sh
python3 scripts/build-dist.py --refresh   # after editing versions in pack.json: re-pin from the Modrinth API and Fabric meta
./gradlew build
python3 scripts/build-dist.py             # → build/dist/: the five release files
python3 scripts/check-dist.py             # checks them and runs the installers against a throwaway launcher folder
```

`scripts/install.sh` and `scripts/install.ps1` are templates; `build-dist.py` stamps the version, the jar's checksum and the pinned pack into them. `scripts/verify-ci.sh` runs both steps, CI also runs `install.ps1` on Windows, and pushing a `vX.Y.Z` tag publishes the five files.
