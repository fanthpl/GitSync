# Edge cases

What GitSync does in the situations that are not obvious. Everything here is how the code actually behaves, not how it should.

## Conflicts between the pack and the server

### The same file is modified both in the repo and on this server

The sync stops before writing anything. GitSync compares the file on disk against what the last render wrote (`render-state.json`); a mismatch means it was edited here, and overwriting it would throw that work away. The sync names the files and waits until you either publish the edits (`/gitsync pushupdate <message>`) or discard them (`/gitsync sync --force`). Note this compares against the *last render*, not the incoming commit - there is no line-level merge, it is all-or-nothing per file.

### A file was edited locally and the pack did NOT change it

Same thing. The renderer wants the file to match the pack, the disk says otherwise, so every sync (including the automatic one every `checkIntervalSeconds`) stops on it. **A single locally edited file blocks the whole sync for the entire server** - updates to unrelated plugins do not come through until the edit is published or discarded. This is deliberate: a half-applied pack is worse than a stale one.

### A synced file was deleted on this server

Also a conflict - the pack wants the file back, the state says you removed it. `pushupdate` interprets the deletion as intent: it drops the file from the layer it came from, which re-exposes the copy in the layer below (if any). `--force` just renders it back.

### `render-state.json` is deleted or corrupted

GitSync starts with an empty state. Files on disk that match the pack byte-for-byte are adopted silently. Files that differ are all reported as local edits, even if you never touched them (e.g. a plugin rewrote its own config on shutdown). Either `pushupdate` the ones you want or `--force` to re-render everything.

### A plugin rewrites its own config at runtime

Same as a manual edit - GitSync cannot tell them apart. Plugins that reformat or re-save their configs (strip comments, reorder keys) will permanently fight the sync. Do not put such files in `configPaths`, or accept that every sync stops on them.

## Variables

### A variable value is changed in `server.yml`

Run `/gitsync sync`. Even with no new commits, the render notices the desired content (new value substituted) no longer matches the disk and rewrites the file, then runs the reload commands for it. No conflict is raised, because the disk still matches what the last render wrote.

### A variable the pack uses is missing from `server.yml`

Nothing is written - the raw `${GITSYNC_NAME}` would land in the file and the owning plugin would read it literally (a broken DB password, a wrong server name).

- **During startup**: the server refuses to boot. It logs which variables are missing and which files use them, then `System.exit(1)`.
- **At runtime**: the sync is abandoned before writing anything and reports the missing names. The warning is repeated only when the set of missing variables changes, not on every timer tick.

`--force` does not help - there is still no value to write.

### A `${...}` placeholder without the `GITSYNC_` prefix

Ignored entirely. Only `${GITSYNC_*}` belongs to GitSync, so the `${placeholder}` and `${placeholder:default}` syntax other plugins use passes through untouched.

### The line holding a variable was edited by hand

`pushupdate` restores variables by matching whole lines: a line that still reads exactly as rendered is turned back into its `${GITSYNC_NAME}` form. An edited variable line matches nothing, so the value and the edit cannot be told apart. The commit stops, names the lines, and offers `[publish anyway]` (`--confirm`) - because publishing sends *this server's* value to every server rendering that file. Adding/removing/editing other lines in the file is fine; matching is by content, not line number.

### A variable value that itself contains `${...}`

Inserted literally and never rescanned - no recursive expansion, no injection.

### A variable value with YAML-special characters

When the placeholder is the whole YAML value (`key: ${GITSYNC_X}`), a value that would break plain YAML (leading `#`, `: ` inside, trailing space, empty string...) is automatically single-quoted. A placeholder embedded inside a longer string or already quoted by the template is inserted as-is - quoting there is your job in the template.

### Variables in binary files

Not replaced. Binary content passes through byte-for-byte.

## Installing the pack on a server that already has plugins

### The server already has files the pack declares

Files identical to what the pack would render are **adopted silently** - no write, no reload, no conflict. Files that differ show up as local edits on the first sync (see conflicts above): publish the local version or `--force` the pack's version. Nothing is lost without you choosing it.

### The server has plugins the pack knows nothing about

Untouched, forever. GitSync only writes and deletes paths declared in `pack.json`. A server-only plugin keeps its jar and configs.

### Unclaimed jars at `pushupdate` time

Every commit scans `plugins/` for jars no pack entry claims and asks, per jar, where it belongs: `[base]`, `[role/<role>]`, `[instance/<instance>]` or `[ignore]`. `[ignore]` is remembered in `data.yml` (as a wildcard), so the same plugin is never asked about again - even across version bumps. Only the jar joins the pack; `configPaths` must be added by hand, because GitSync will not guess which files are config and which are player data.

### First install with `syncOnStartup: true` and jars in the pack

The first startup sync pulls jars the server did not boot with, so it stops the server (see below). Expect one extra restart on first install.

## Jars

### The pack updates a plugin jar while the server is running

The new jar is written and the old one (tracked under the same wildcard) removed, but the running server keeps the old version loaded. `/gitsync status` shows `Restart required: YES` with the jar named. No reload command can fix a jar - only a restart.

### A jar arrives during the startup sync

The server scans `plugins/` and builds its plugin list *before* the bootstrap phase where GitSync syncs, so a jar pulled at that point cannot be loaded into the boot already in progress. GitSync logs it and stops the server (`System.exit(1)`), expecting the wrapper (Pterodactyl, systemd, a start loop) to bring it back up with the jar in place. **Without an auto-restarting wrapper the server stays down.** `syncOnStartup: false` avoids this entirely.

### The "restart required" flag survives... nothing

It lives in memory only. That is intentional: the restart it asks for is exactly what clears it. A crash also clears it, but the boot after a crash loads the new jars anyway, so the flag would be stale.

### A higher layer carries the same plugin under a different jar name

Handled. A jar override does not replace the lower file by name the way a config does - both would sit in `plugins/` and the server would load the plugin twice. So for each `pluginJarWildcard`, only the highest layer holding any matching jar is rendered; lower-layer jars for that wildcard are suppressed.

### A jar version bump

Covered by the wildcard (`ItemsAdder_*.jar`): commit the new jar, delete the old one from the layer, and every server swaps them on the next sync (plus a restart). `pack.json` needs no edit.

## The pack itself

### A plugin is removed from `pack.json`

On the next sync its files vanish from disk (they are tracked in the state, no longer in the plan) while the server still has the plugin loaded. That mismatch cannot be reloaded away, so the sync flags `pack.json - the pack composition changed` as a restart reason **and disables all reload commands until the restart** - reloading anything against a plugins directory that no longer matches what is loaded is guesswork.

### `pack.json` is deleted from the repository

**The nuclear version of the above.** An empty manifest means an empty plan, and everything the previous renders wrote gets deleted from `plugins/` on every server. Treat `pack.json` as the most protected file in the repo.

### `pack.json` has a JSON syntax error

The sync fails with an error in the console before rendering anything. Nothing is written or deleted - broken JSON is safe, missing JSON is not (see above).

### A pack path tries to escape `plugins/` (`../...`)

Refused. Every rendered path is normalized and must stay under `plugins/`, whatever the repository holds.

### A pack path points into `GitSync/`

Ignored. The pack cannot overwrite GitSync's own config, state, or the repository itself.

## Git

### Two servers push at (nearly) the same time

The second push is rejected as non-fast-forward. JGit does not throw for this - GitSync reads the per-ref status, reports "Push rejected", and tells you to `/gitsync sync` first (which pulls the other server's commit), then push again. The commit already exists locally; `/gitsync git showahead` lists it.

### The pull hits a merge conflict

Happens when the local repo has commits the remote does not (e.g. a push failed and the remote moved on). The sync fails and names the conflicting files. `/gitsync sync --force` fetches and hard-resets onto the remote branch, discarding the local commits and any local edits.

### The remote repository is empty

Not an error. There is no branch to pull yet, so the sync reports the remote as still empty and does nothing. The first `pushupdate` creates the branch.

### `remote` is left empty in `config.yml`

GitSync goes idle: no repo, no timer, commands answer that it is not initialized.

## Everything else

### `role` or `instance` is changed in `server.yml`

After `/gitsync reload` (or a restart) and a sync, the plan is rebuilt from the new layers: files owned by the old layer are rewritten from the new one, files only the old layers had are deleted, files only the new layers have appear. Jar swaps flag a restart as usual. Files rendered before the change are not conflicts - the state remembers what was written, not which role wrote it.

### `role`/`instance`/variables use `${ENV_VAR}` and the environment changed

Environment variables are read when `server.yml` is loaded, from the JVM's environment - which is fixed at process start. A changed egg variable on Pterodactyl needs a server restart, not just `/gitsync reload`.

### `/gitsync git resethead` and files outside the pack

Safe. It hard-resets the pack repository and re-renders **declared paths only**. Configs and plugins not in `pack.json` are never touched, same as during a normal sync.

### `forceNextStartupSync` in `data.yml`

A one-shot flag: the next startup sync runs as `--force`, discarding local edits. It is cleared *before* the sync runs, so a broken remote cannot leave the server force-syncing on every boot.

### A `pushupdate` dies halfway (crash, push failure)

Staged copies can be left inside the pack repository, but `plugins/` and the state are untouched - the state is only updated after a successful push. `/gitsync git resethead` cleans the repo up (it runs `git clean` + `reset --hard` first).

### Windows and Linux servers share one repo

Handled by repo config set on every sync: `core.autocrlf=input` stores and checks out everything as LF (so a CRLF-saving editor never produces a whole-file diff), `core.fileMode=false` ignores executable-bit differences. Variable line matching also strips `\r`, so a Windows-edited file still round-trips.

### Leftover `plugins/.git` from the pre-layer layout

Old GitSync made `plugins/` itself the repository. The new pack lives in `plugins/GitSync/pack/`; both can coexist, but only the new one syncs. GitSync warns loudly in the console until `plugins/.git` and `plugins/.gitignore` are deleted by hand.
