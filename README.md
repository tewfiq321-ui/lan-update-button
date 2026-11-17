# LAN Update Button

A small **NeoForge** mod for **Minecraft Java 26.3** that adds an **Update** button to the title screen, the Multiplayer screen and the pause menu. It helps friends playing on the same WiFi run the same mods.

- **Update from GitHub**: downloads the newest release of this mod for your Minecraft version.
- **Copy mods from a LAN host**: while a friend's world is open to LAN, copy their mods folder over your WiFi so everyone matches. Mods you're missing get downloaded, different versions get replaced, and you choose whether to keep mods only you have.
- **Copy the host's settings too**: the host's config files (mod settings) can be copied in the same step, so mods behave the same on every PC. You can switch this off on either side.

Restart Minecraft after updating. Minecraft can't load or unload mods while it's running.

## Install

1. Install **NeoForge 26.3** with the installer from [neoforged.net](https://neoforged.net/).
2. Download `lanupdate-26.3-<version>.jar` from [Releases](https://github.com/tewfiq321-ui/lan-update-button/releases).
3. Put the jar in your `.minecraft/mods` folder. On Windows that's `%APPDATA%\.minecraft\mods`.
4. Start Minecraft with the NeoForge profile.

Everyone in the group needs the mod: the host to share their mods, and friends to copy them.

## Copy a friend's mods (same WiFi)

**Host**
1. Open a world, press **Esc**, then click **Open to LAN** and **Start LAN World**.
2. That's it. Your mods are now shared. Click **Update** in the pause menu to see the address friends can type by hand, for example `192.168.1.23:25580`.

**Friend**
1. On the title screen (or the Multiplayer screen), click **Update** and then **Copy mods from a LAN host**.
2. Pick the host from the list. If nobody shows up, type the address from the host's Update menu.
3. Check the list of changes. The **Host's settings** button decides whether the host's config files are copied too (on by default).
4. Click **Copy mods now**.
5. Click **Quit Minecraft now** and start Minecraft again.

If the host runs a different NeoForge version, the mod warns you. The NeoForge version itself can only be changed with the NeoForge installer.

### About config files

- Only the host's config files that are **missing or different** on your PC get copied. Config files only you have are never deleted.
- Copied config files are put in place **when Minecraft closes**, not right away. Many mods save their settings on the way out, which would otherwise overwrite the copied ones.
- Only common text config formats are shared (`.toml`, `.json`, `.json5`, `.cfg`, `.conf`, `.ini`, `.properties`, `.txt`, `.yml`, `.yaml`, `.snbt`), at most 2 MB each. This mod's own settings and `fml.toml` are never shared.
- If one of your mods keeps private data in its config file, such as a login token, turn off `shareConfigsOnLan` before opening your world to LAN.

> Only copy mods from people you trust. Mods are programs that run on your PC.

## Update the mod itself

Click **Update** and then **Check GitHub for updates**. If a newer release exists for your Minecraft version, click **Download and install**, then restart. When the startup check finds a new version, the button turns yellow and reads **Update!**.

## Troubleshooting

| Problem | Fix |
| --- | --- |
| No hosts show up | Type the host's address by hand. Some routers block the multicast that automatic search uses. Guest, school and hotel WiFi often block devices from seeing each other; a phone hotspot works. |
| "Could not reach …" | On the host PC, allow **Java(TM) Platform SE binary** through Windows Firewall on **Private** networks (Windows Security → Firewall & network protection → Allow an app through firewall). Also set the WiFi network profile to **Private**. |
| Two versions of a mod after syncing | On Windows, mods in use can't be deleted until Minecraft closes. The mod deletes them automatically a few seconds after you quit. If one is still there, delete it from the mods folder. |
| The port 25580 is taken | Change `lanSharePort` under Mods → LAN Update Button → Config. Automatic search still works with any port. |

## How it works

- **Discovery**: while a world is open to LAN, the host sends a small UDP multicast message to `224.0.2.60:4446` every 1.5 seconds. Minecraft uses the same group for its own LAN games, one port lower.
- **Sharing**: the host runs a tiny read-only HTTP server on TCP port `25580`. It serves only the list of `.jar` files in the host's `mods` folder (plus shareable config files, if allowed) and those files, requested by SHA-256 hash, so nothing else on the PC can be reached. The friend's side checks every config path again and refuses anything outside the `config` folder.
- **Safety checks**: each download is checked against its SHA-256 hash and size before it's saved. Downloads are written as `.part` files first, so a broken download never ends up as a mod.
- **GitHub updates**: the mod reads `https://api.github.com/repos/<repo>/releases` and looks for a jar named `lanupdate-<minecraft version>-<mod version>.jar`. The repository is set in the config, so forks can point the button at their own releases.

## Config

`config/lanupdate-client.toml`, also editable in game under Mods → LAN Update Button → Config:

| Option | Default | Meaning |
| --- | --- | --- |
| `githubRepo` | `tewfiq321-ui/lan-update-button` | Where the Update button looks for new versions |
| `checkOnStartup` | `true` | Check GitHub when Minecraft starts |
| `shareModsOnLan` | `true` | Share your mods while your world is open to LAN |
| `shareConfigsOnLan` | `true` | Also share your config files (mod settings) |
| `lanSharePort` | `25580` | TCP port used for sharing |

## Building from source

You need JDK 25. Gradle is downloaded by the wrapper.

```bash
./gradlew build
```

The jar ends up in `build/libs/`. Run `./gradlew runClient` to start a development client.

### Releasing

Push a tag. GitHub Actions builds the jar and publishes a release that the in-game Update button picks up:

```bash
git tag v1.2.0
git push origin v1.2.0
```

## License

[MIT](LICENSE)
