# GitSync

Keep plugin jars and configs identical across Minecraft servers, using a Git repository as the source of truth.

One repository holds the jars and config files you care about. Every server runs GitSync, renders that repository into its own `plugins/` directory, and runs the right reload command for whatever actually changed. Edit a config once, commit, and every server picks it up.

Servers are not all the same, so the pack is layered. `base/` is what every server gets, `role/<role>/` is what every server of one kind gets, and `instance/<instance>/` is one server alone. A higher layer replaces the whole file.

You decide what is in the pack. A plugin is synced only once you add it to `pack.json` - everything else in `plugins/` is left completely alone, keeping whatever jar and config that particular server has.

## How it works

The repository is cloned into `plugins/GitSync/pack/`, and `plugins/` is rendered from it. Rendering is a plain copy: for every path `pack.json` declares, the highest layer that holds it wins, and the file is written into `plugins/`.

```
plugins/
├── GitSync/
│   ├── config.yml           <- remote, branch, credentials
│   ├── server.yml           <- role and instance of this server
│   ├── render-state.json    <- what the last render wrote, do not edit
│   └── pack/                <- the repository
│       ├── pack.json        <- declares what is synced
│       ├── base/            <- every server
│       ├── role/city/       <- every city server
│       └── instance/city-1/ <- this one server
├── ItemsAdder_4.0.17.jar    <- rendered from the pack
├── ItemsAdder/              <- only the declared paths inside are rendered
└── SomeOtherPlugin/         <- never touched, not in pack.json
```

GitSync remembers what it wrote in `render-state.json`. A file whose content no longer matches that was edited on this server, so a sync that wants to change it stops instead, names the files, and waits - either publish those edits with `/gitsync git commitandpush`, or throw them away with `/gitsync sync --force`. Nothing outside `pack.json` is ever written or deleted.

## pack.json

Lives in the root of the remote repository and declares what belongs to the pack:

```json
{
    "plugins": {
        "ItemsAdder": {
            "pluginJarWildcard": "ItemsAdder_*.jar",
            "configPaths": [
              "ItemsAdder/contents",
              "ItemsAdder/storage/custom_fires_ids_cache.yml",
              "ItemsAdder/storage/font_images_unicode_cache.yml",
              "ItemsAdder/storage/items_ids_cache.yml",
              "ItemsAdder/storage/real_blocks_ids_cache.yml",
              "ItemsAdder/storage/real_blocks_note_ids_cache.yml",
              "ItemsAdder/storage/real_transparent_blocks_ids_cache.yml",
              "ItemsAdder/storage/real_wire_ids_cache.yml"
            ],
            "reloadCommands": [
                "iareload"
            ]
        },
        "EssentialsX": {
            "pluginJarWildcard": "EssentialsX-*.jar",
            "configPaths": [
                "Essentials/config.yml"
            ],
            "reloadCommands": [
                "ess reload"
            ]
        }
    }
}
```

| Field | Meaning |
| --- | --- |
| `pluginJarWildcard` | Glob for the plugin jar in the root of `plugins/`. The wildcard means you do not have to touch `pack.json` on every version bump. A jar in a higher layer hides the one below it, even under a different file name. |
| `configPaths` | Files or directories to sync, relative to `plugins/`. A directory syncs everything under it. |
| `reloadCommands` | Run from the console when any of the paths above change. |

All three are optional. Drop `reloadCommands` for a plugin that has to be restarted anyway, or `pluginJarWildcard` for a config-only entry.

Paths are the same in every layer: `base/Essentials/config.yml` and `role/city/Essentials/config.yml` both mean `plugins/Essentials/config.yml`.

## Configuration

`plugins/GitSync/config.yml` - how to reach the repository:

```yaml
# Remote repository holding pack.json, plugin jars and configs. Leave empty to disable syncing.
remote: "https://github.com/you/your-pack.git"
# Branch to track
branch: "main"
# Credentials for private repositories. On GitHub use a personal access token as the password.
username: ""
password: ""
# How often to check the remote for new commits (seconds)
checkIntervalSeconds: 300
# Sync during the bootstrap phase, before any plugin loads, so synced configs are read by their owners.
# New plugin jars cannot be loaded during a boot that already started, so the server stops to be restarted with them.
syncOnStartup: true
```

`plugins/GitSync/server.yml` - which layers this server renders:

```yaml
# Server type, rendered from role/<role>/ over base/. Shared by every server of this kind.
role: "city"
# This one server, rendered from instance/<instance>/ over the role. Empty for none.
instance: "city-1"
```

Both fields resolve `${VAR}` from the environment, so `role: "${GITSYNC_ROLE}"` picks up an egg variable on Pterodactyl instead of being typed into every server.

## Commands

All of them need the `gitsync.admin` permission.

| Command | What it does |
| --- | --- |
| `/gitsync sync [--force]` | Pull and render now. `--force` takes the pack as-is: edits made on this server are overwritten instead of stopping the sync. |
| `/gitsync status` | Remote, branch, commit, layers, what the pack contains - and whether the server needs a restart. |
| `/gitsync reload` | Reload `config.yml` and `server.yml`, and restart the periodic check. |

### Editing on a live server

Configs are meant to be tweaked in place. Do it, then publish:

| Command | What it does |
| --- | --- |
| `/gitsync git status` | Which synced files were edited here, and which layer each one would go back to. |
| `/gitsync git diff [path]` | What differs between the pack and the files on this server. |
| `/gitsync git commitandpush <message>` | Copy those edits back into the layer each file came from, commit and push. A file created here goes to the role layer; a file deleted here is dropped from its layer, which re-exposes the copy below it. |
| `/gitsync git showahead` | Commits that exist locally but not on the remote. |
| `/gitsync git resethead` | Throw away the local edits and render the pack over them again. |

A commit also looks for jars in `plugins/` that no `pack.json` entry claims. For each one it asks in chat where the plugin belongs - `[base]`, `[role/<role>]`, `[instance/<instance>]` or `[ignore]` - and the click does the rest. Only the jar joins the pack; add its `configPaths` by hand, because which of its files are config and which are player data is not something to guess at. `[ignore]` remembers the plugin in `data.yml` so the same jar is never asked about twice.

### Restart required

Not every change can be applied by a reload command, so `/gitsync status` tracks the ones that cannot and names them:

```
--- GitSync ---
Remote: https://github.com/you/your-pack.git
Branch: main
Commit: a1b2c3d Update ItemsAdder to 4.0.17
Layers: base -> role/city -> instance/city-1
Pack: 2 plugin(s): ItemsAdder, EssentialsX
Auto sync: every 300s
Restart required: YES
  ItemsAdder_4.0.17.jar (plugin jar changed)
  SomePlugin/config.yml (no reload commands declared)
```

A restart is flagged when a sync adds, removes or updates a plugin jar, or when it changes a config of a plugin whose entry declares no `reloadCommands`. The flag lives in memory only - the restart it asks for is what clears it.

## Startup behaviour

A config is useless if its owner already read the old one, so GitSync syncs from a Paper **plugin bootstrapper** - the phase that runs before any plugin is loaded. By the time ItemsAdder or EssentialsX starts, their configs are already the ones from the repository.

Jars are the exception, and no plugin can work around it: the server scans `plugins/` and builds its plugin list *before* the bootstrap phase runs. A jar rendered during that sync cannot be loaded into a boot that already began. So when a sync replaces a jar during startup, GitSync logs it and stops the server, expecting the wrapper (systemd, Pterodactyl, Docker, a start script loop) to bring it back up with the new jars in place. **Without an auto-restarting wrapper the server will simply stay down.** Set `syncOnStartup: false` if you would rather sync only on the timer and handle jars yourself.

## Migrating from the old layout

Earlier versions made `plugins/` itself the repository. To move over:

1. In the remote repository, move everything except `pack.json` under `base/` (`git mv`), and split off `role/<role>/` where servers differ.
2. Set `role` in `server.yml` on each server.
3. Delete `plugins/.git` and `plugins/.gitignore` by hand. GitSync warns in the console until you do.

Files already on disk that match the pack are adopted silently, so the first sync after the move only reports what genuinely differs.

## Building

```
./gradlew build
```

The shaded jar lands in `build/libs/`. Requires JDK 21.

Requires Paper 1.21+ - the plugin uses `paper-plugin.yml` and a bootstrapper, so it does not run on Spigot.

## Repository defaults

When GitSync creates the repository it sets a few things once, so commits from a server are recognisable and Windows/Linux servers can share one repo:

```
user.name         = MC Server
user.email        = minecraft@server.null
core.fileMode     = false
credential.helper = store
```
