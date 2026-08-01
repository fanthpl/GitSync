# GitSync

Keep plugin jars and configs identical across Minecraft servers, using a Git repository as the source of truth.

One repository holds the jars and config files you care about. Every server runs GitSync, pulls that repository into its own `plugins/` directory, and runs the right reload command for whatever actually changed. Edit a config once, commit, and every server picks it up.

## How it works

The `plugins/` directory *is* the git repository. GitSync creates it on first start, then keeps a `.gitignore` there that ignores everything except the paths declared in `pack.json` - so the repo only ever tracks what you listed, and the rest of `plugins/` stays private to each server.

```
plugins/
├── .git/              <- created by GitSync
├── .gitignore         <- generated from pack.json, ignores itself too
├── pack.json          <- comes from the remote, declares what is synced
├── ItemsAdder-4.0.jar <- tracked
├── ItemsAdder/        <- only the declared paths inside are tracked
└── SomeOtherPlugin/   <- never touched, not in pack.json
```

On a normal sync GitSync runs `git pull`, diffs the old and new `HEAD`, and dispatches the reload commands of every plugin whose files appear in that diff. A merge conflict aborts the sync with an error instead of silently discarding anything.

## pack.json

Lives in the root of the remote repository and declares what belongs to the pack:

```json
{
    "plugins": {
        "ItemsAdder": {
            "pluginJarWildcard": "ItemsAdder-*.jar",
            "configPaths": [
                "ItemsAdder/storage",
                "ItemsAdder/config.yml"
            ],
            "reloadCommands": [
                "iareload"
            ]
        },
        "EssentialsX": {
            "pluginJarWildcard": "EssentialsX-*.jar",
            "configPaths": [
                "EssentialsX/config.yml"
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
| `pluginJarWildcard` | Glob for the plugin jar in the root of `plugins/`. The wildcard means you do not have to touch `pack.json` on every version bump. |
| `configPaths` | Files or directories to sync, relative to `plugins/`. A directory syncs everything under it. |
| `reloadCommands` | Run from the console when any of the paths above change. |

All three are optional. Drop `reloadCommands` for a plugin that has to be restarted anyway, or `pluginJarWildcard` for a config-only entry.

## Configuration

`plugins/GitSync/config.yml`:

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

## Commands

All of them need the `gitsync.admin` permission.

| Command | What it does |
| --- | --- |
| `/gitsync sync [force]` | Pull now. `force` runs every reload command in `pack.json` even when nothing changed. |
| `/gitsync status` | `git status` of the pack, colour coded per change type. |
| `/gitsync showahead` | Commits that exist locally but not on the remote. |
| `/gitsync commitandpush <message>` | Commit everything the pack tracks and push it - the way to publish a change made on one server to the others. |
| `/gitsync resethead` | `git clean -fd` + `git reset --hard HEAD`. Throws away local edits to tracked files; files outside `pack.json` are ignored, so `clean` leaves them alone. |
| `/gitsync reload` | Reload `config.yml` and restart the periodic check. |

## Startup behaviour

A config is useless if its owner already read the old one, so GitSync syncs from a Paper **plugin bootstrapper** - the phase that runs before any plugin is loaded. By the time ItemsAdder or EssentialsX starts, their configs are already the ones from the repository.

Jars are the exception, and no plugin can work around it: the server scans `plugins/` and builds its plugin list *before* the bootstrap phase runs. A jar pulled during that sync cannot be loaded into a boot that already began. So when a sync replaces a jar during startup, GitSync logs it and stops the server, expecting the wrapper (systemd, Pterodactyl, Docker, a start script loop) to bring it back up with the new jars in place. **Without an auto-restarting wrapper the server will simply stay down.** Set `syncOnStartup: false` if you would rather sync only on the timer and handle jars yourself.

## First run

`git pull` refuses to overwrite untracked local files, and on a fresh server every jar and config in `plugins/` is untracked. So the very first sync usually fails with:

```
Sync failed: local files would be overwritten: ItemsAdder-4.0.jar, ItemsAdder/config.yml ...
```

That is ordinary git behaviour, not a bug. Delete or move the listed files and sync again - the repository has its own copies.

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
