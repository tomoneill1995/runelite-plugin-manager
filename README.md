# RuneLite Plugin Manager

A plugin manager for the regular RuneLite you download from runelite.net. It loads plugins into the
client while it's running, so you can install, enable, disable and reload them without restarting.
You don't need developer mode or a modified client.

![Installed plugins](docs/installed.png)

## Sources

You can browse plugins from a few different places:

| Source | Notes |
|---|---|
| Official Plugin Hub | All ~2,600 Hub plugins. Downloads the same prebuilt jars RuneLite does and checks the manifest signature and jar hashes the same way. |
| GitHub repo | `owner/repo` or any git URL. Works with single plugin repos and repos that contain lots of plugins. Builds from source with Gradle and rebuilds when you pull changes. |
| Local project | For plugins you're developing. Turn on auto-reload and it rebuilds and reloads every time you save a file. |
| Jar folder | A folder of already built plugin jars. |

![Browse](docs/browse.png)

The Installed tab has an on/off switch for each plugin, which loads or unloads it straight away, and a
reload button. Browse lets you search across every source and install from there. The Sources tab is
where you add GitHub repos, local projects and jar folders.

Launch RuneLite starts the official client with the manager attached. Plugins load when it gets to the
login screen.

If a plugin depends on another one (a `project(':Other')` dependency in Gradle, or
`@PluginDependency`), the dependency gets installed and loaded first. Reloading a dependency also
reloads the plugins that use it.

## How it works

There's a small Java agent that gets added to RuneLite's JVM arguments when it launches. After the
client starts, the agent loads plugin jars through RuneLite's own `PluginManager`, which is the same
thing RuneLite does when you install a plugin from the Plugin Hub. It listens for commands on
`127.0.0.1` and needs a random token that the manager reads from disk.

The manager is the app in the screenshots. It builds or downloads jars and tells the agent what to load.

Your RuneLite install isn't modified. The launcher still updates and verifies the client as normal.
The agent is just an extra JVM argument.

## Privacy

Everything runs locally. The manager connects to GitHub (through git), repo.runelite.net for Plugin
Hub data, and the agent on localhost. It doesn't talk to Jagex.

Here's what RuneLite itself sends, going by the RuneLite source:

| | |
|---|---|
| Telemetry, error reports, crash logs | Turned off by default here with `--disable-telemetry`. If you turn it back on, crash logs include the JVM arguments. |
| Plugin Hub install stats | Only includes plugins installed through RuneLite's own Plugin Hub. Sideloaded plugins aren't listed. |
| Profile sync | If you sync your profile to a RuneLite account, plugin settings are uploaded, including settings for sideloaded plugins. |

I don't know what Jagex's code running in the same JVM can see or report. Plugins can do anything
their code does, so only run plugins you trust. Plugins that break Jagex's third-party client rules can
still get your account banned, however they're loaded.

## Running it

Download `RuneLite-Plugin-Manager-<version>-windows.zip` from Releases, unzip it and run
`RuneLite Plugin Manager.exe`.

Java is bundled, so you don't need to install it. You do need Windows and RuneLite from runelite.net.

Plugin Hub plugins work with nothing else installed. GitHub sources need
[Git](https://git-scm.com/download/win). Building plugins from source needs a Java 11 JDK. If you
already have one installed the manager will use it, otherwise it downloads Eclipse Temurin 11
(about 190 MB, checksum verified) into `~/.rl-sideloader/jdks` the first time it needs to build
something.

Settings are saved to `~/.rl-sideloader/manager.json` and the log is at `~/.rl-sideloader/manager.log`.

## Building

You need JDK 17 for `jpackage`.

```
gradlew.bat build
gradlew.bat :manager:zipApp
```

The app is written to `manager/build/package/` and the zip to `manager/build/release/`. To run it
without packaging, use `tools\manager.bat`, which runs the jar with RuneLite's bundled Java.

## Known issues

- Jagex accounts: Launch RuneLite starts RuneLite directly, so you won't have a Jagex Launcher session.
  Adding the agent through the JVM arguments in "RuneLite (configure)" might work but I haven't tested it.
- Kotlin plugins need the Kotlin standard library bundled into their jar because RuneLite doesn't
  include it. The same goes for any other third-party library a plugin uses.
- Reloading is only as clean as the plugin's `shutDown`. If a plugin leaves threads or static state
  behind, memory use will creep up over time and a restart fixes it.

## Other files

- `test-plugin/` is a small plugin that adds a sidebar button. I used it to test reloading.
- `tools/launch.ps1` and `tools/rlctl.ps1` start RuneLite with the agent and control it from a
  terminal without the manager.

Not affiliated with RuneLite or Jagex.
