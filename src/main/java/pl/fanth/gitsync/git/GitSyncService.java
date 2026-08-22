package pl.fanth.gitsync.git;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import pl.fanth.gitsync.GitSyncPlugin;
import pl.fanth.gitsync.config.PluginConfiguration;
import pl.fanth.gitsync.config.ServerConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs both during the plugin bootstrap, where Bukkit does not exist yet, and at runtime.
 * Everything that needs a running server goes through the nullable plugin field.
 */
public class GitSyncService {
    private static final String REMOTE = "origin";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path packDir;
    private final Path pluginsDir;
    private final Path stateFile;
    private final Logger logger;
    private final Supplier<PluginConfiguration> configSupplier;
    private final Supplier<ServerConfiguration> serverSupplier;
    private final GitSyncPlugin plugin;

    private final AtomicBoolean syncing = new AtomicBoolean();
    // Cleared by the restart itself, so it never needs to be persisted
    private final Map<String, String> restartReasons = new LinkedHashMap<>();

    private Git git;
    private BukkitTask task;
    private volatile boolean lastSyncFailed;

    /** Bootstrap phase, no server available. */
    public GitSyncService(Path dataDirectory, Logger logger, Supplier<PluginConfiguration> configSupplier,
                          Supplier<ServerConfiguration> serverSupplier) {
        this(dataDirectory, logger, configSupplier, serverSupplier, null);
    }

    /** Runtime, the server is up so reload commands and the periodic check can run. */
    public GitSyncService(GitSyncPlugin plugin) {
        this(plugin.getDataFolder().toPath(), plugin.getLogger(), plugin::pluginConfiguration,
                plugin::serverConfiguration, plugin);
    }

    private GitSyncService(Path dataDirectory, Logger logger, Supplier<PluginConfiguration> configSupplier,
                           Supplier<ServerConfiguration> serverSupplier, GitSyncPlugin plugin) {
        this.packDir = dataDirectory.resolve("pack");
        this.pluginsDir = dataDirectory.getParent();
        this.stateFile = dataDirectory.resolve("render-state.json");
        this.logger = logger;
        this.configSupplier = configSupplier;
        this.serverSupplier = serverSupplier;
        this.plugin = plugin;
    }

    /** Open or create the repository. Returns false when GitSync cannot work at all. */
    public boolean open() {
        PluginConfiguration config = this.configSupplier.get();
        if (config.remote.isBlank()) {
            this.logger.warning("No remote configured in config.yml, GitSync is idle.");
            return false;
        }

        warnAboutOldRepository();

        try {
            this.git = Git.open(this.packDir.toFile());
        } catch (IOException exception) {
            try {
                Files.createDirectories(this.packDir);
                this.git = Git.init().setDirectory(this.packDir.toFile()).setInitialBranch(config.branch).call();
                initRepositoryConfig();
            } catch (Exception initException) {
                this.logger.log(Level.SEVERE, "Could not initialize the git repository in " + this.packDir, initException);
                return false;
            }
        }
        return true;
    }

    /**
     * Older versions made plugins/ itself the repository. Both can sit on disk at once without
     * breaking anything, but only one of them is being synced, and that is worth saying out loud.
     */
    private void warnAboutOldRepository() {
        if (!Files.isDirectory(this.pluginsDir.resolve(".git"))) {
            return;
        }
        this.logger.severe("=".repeat(70));
        this.logger.severe("plugins/.git is left over from an older GitSync. The pack now lives in");
        this.logger.severe(this.packDir + " and plugins/ is rendered from it.");
        this.logger.severe("Move the files in the remote repository under base/ (or role/<role>/),");
        this.logger.severe("then delete plugins/.git and plugins/.gitignore by hand.");
        this.logger.severe("=".repeat(70));
    }

    public void start() {
        if (!open()) {
            return;
        }

        long interval = Math.max(1, this.configSupplier.get().checkIntervalSeconds) * 20L;
        this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(this.plugin, () -> sync(null, false), interval, interval);
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
        if (this.git != null) {
            this.git.close();
            this.git = null;
        }
    }

    public boolean isSyncing() {
        return this.syncing.get();
    }

    /** True when the last sync did not get past the pull, the console holds the reason. */
    public boolean lastSyncFailed() {
        return this.lastSyncFailed;
    }

    /** Paths pulled since this boot that a reload command cannot apply, mapped to the reason. */
    public synchronized Map<String, String> restartReasons() {
        return new LinkedHashMap<>(this.restartReasons);
    }

    /** The pack as it currently sits in the repository, empty when there is no readable pack.json. */
    public PackManifest manifest() {
        try {
            return readManifest();
        } catch (IOException exception) {
            this.logger.log(Level.WARNING, "Could not read pack.json", exception);
            return new PackManifest();
        }
    }

    /** Renders the layers this server subscribes to. */
    public PackRenderer renderer() {
        ServerConfiguration server = this.serverSupplier.get();
        return new PackRenderer(this.packDir, this.pluginsDir, server.role, server.instance);
    }

    /** The shared repository handle, null until open() succeeds. Do not close it. */
    public Git git() {
        return this.git;
    }

    public CredentialsProvider credentials() {
        PluginConfiguration config = this.configSupplier.get();
        return config.username.isBlank() ? null : new UsernamePasswordCredentialsProvider(config.username, config.password);
    }

    /**
     * Pull the remote branch, render the layers into plugins/ and run reload commands for whatever
     * changed. A file edited on this server since the last render blocks the whole sync instead of
     * being overwritten, unless force says the pack wins.
     *
     * @return the logical paths that changed, empty when nothing did or the sync failed
     */
    public synchronized Set<String> sync(CommandSender feedback, boolean force) {
        if (this.git == null) {
            reply(feedback, "GitSync is not initialized, check the console.", NamedTextColor.RED);
            return Set.of();
        }

        this.syncing.set(true);
        PluginConfiguration config = this.configSupplier.get();
        try {
            configureRepository(config);

            if (force) {
                if (!resetToRemote(config, feedback)) {
                    return Set.of();
                }
            } else {
                PullResult result = this.git.pull()
                        .setRemote(REMOTE)
                        .setRemoteBranchName(config.branch)
                        .setCredentialsProvider(credentials())
                        .call();

                if (!result.isSuccessful()) {
                    this.lastSyncFailed = true;
                    reportConflicts(feedback, conflictsOf(result.getMergeResult()), mergeStatusOf(result));
                    return Set.of();
                }
            }

            this.lastSyncFailed = false;

            PackManifest manifest = readManifest();
            PackRenderer renderer = renderer();
            PackRenderer.State state = PackRenderer.State.load(this.stateFile);
            PackRenderer.Render render = renderer.apply(renderer.plan(manifest), state, force);

            if (!render.conflicts().isEmpty()) {
                // Under force the render already ran, the conflicts only say what was overwritten
                if (!force) {
                    this.lastSyncFailed = true;
                    reportLocalEdits(feedback, render.conflicts());
                    return Set.of();
                }
                this.logger.warning("Force sync overwrote " + render.conflicts().size()
                        + " file(s) edited on this server: " + String.join(", ", render.conflicts()));
                reply(feedback, "Overwrote " + render.conflicts().size() + " file(s) edited on this server.", NamedTextColor.YELLOW);
            }

            Set<String> changed = new LinkedHashSet<>(render.changed());
            String packHash = packHash();
            if (state.packHash != null && !state.packHash.equals(packHash)) {
                // A plugin dropped from the pack is gone from the manifest as well, so nothing
                // below can match the paths it left behind. Its files disappear from disk while
                // the server keeps it loaded, and only a restart settles that.
                changed.add("pack.json");
                this.restartReasons.put("pack.json", "the pack composition changed");
            }
            state.packHash = packHash;
            state.save(this.stateFile);

            this.restartReasons.putAll(manifest.restartRequiredPaths(changed));

            // Once the pack composition changed, the loaded plugins no longer match what is on disk,
            // so reloading anything is guesswork. Stays off for every later sync too, until a restart.
            boolean packChanged = this.restartReasons.containsKey("pack.json");
            List<String> commands = packChanged ? List.of() : manifest.reloadCommandsFor(changed);

            if (changed.isEmpty() && !force) {
                reply(feedback, "Already up to date.", NamedTextColor.YELLOW);
                return Set.of();
            }

            this.logger.info("Synced " + describeHead() + " (" + changed.size() + " file(s) changed, " + commands.size() + " reload command(s)).");
            reply(feedback, "Synced " + describeHead()
                    + " (" + changed.size() + " file(s) changed, " + commands.size() + " reload command(s)).", NamedTextColor.GREEN);
            if (packChanged) {
                this.logger.info("pack.json changed, reload commands are disabled until the server restarts.");
                reply(feedback, "pack.json changed, no plugin will be reloaded until the server restarts.", NamedTextColor.YELLOW);
            }
            if (!this.restartReasons.isEmpty()) {
                reply(feedback, "A server restart is required, run /gitsync status for the details.", NamedTextColor.YELLOW);
            }

            runReloadCommands(commands);
            return changed;
        } catch (Exception exception) {
            this.lastSyncFailed = true;
            this.logger.log(Level.SEVERE, "Sync failed", exception);
            reply(feedback, "An error occurred while syncing. See the console for details.", NamedTextColor.RED);
            return Set.of();
        } finally {
            this.syncing.set(false);
        }
    }

    /** Files declared by the pack that differ on this server from what the last sync rendered. */
    public List<PackRenderer.LocalChange> localChanges() throws IOException {
        return renderer().localChanges(readManifest(), PackRenderer.State.load(this.stateFile));
    }

    /** Jars in plugins/ that no pack entry claims and this server was not told to keep private. */
    public List<String> unknownJars() throws IOException {
        List<String> ignored = new ArrayList<>();
        if (this.plugin != null) {
            ignored.addAll(this.plugin.dataConfiguration().ignoredPluginWildcards);
            // Our own jar is not part of any pack, asking about it on every commit is noise
            ignored.add(this.plugin.getFile().getName());
        }
        return renderer().unknownJars(readManifest(), ignored);
    }

    /**
     * Publish the edits made on this server: every declared file that drifted goes back into the
     * layer it was rendered from, a file created here goes to the role layer, and a file deleted
     * here is dropped from its layer - which re-exposes the copy in the layer below it.
     */
    public synchronized void commitAndPush(CommandSender sender, String message) throws Exception {
        PackRenderer renderer = renderer();
        PackRenderer.State state = PackRenderer.State.load(this.stateFile);
        List<PackRenderer.LocalChange> changes = renderer.localChanges(readManifest(), state);

        for (PackRenderer.LocalChange change : changes) {
            if (change.kind() == PackRenderer.Kind.DELETED) {
                renderer.unstage(change.logicalPath(), change.targetLayer());
            } else {
                renderer.stage(change.logicalPath(), change.targetLayer());
            }
        }

        if (!commit(sender, message)) {
            return;
        }
        if (!push(sender)) {
            return;
        }

        for (PackRenderer.LocalChange change : changes) {
            if (change.kind() == PackRenderer.Kind.DELETED) {
                state.files.remove(change.logicalPath());
            } else {
                state.files.put(change.logicalPath(),
                        new PackRenderer.State.Entry(change.targetLayer(), renderer.diskHash(change.logicalPath())));
            }
        }
        state.save(this.stateFile);
        reply(sender, "Successfully pushed! " + changes.size() + " local change(s) are now in the pack.", NamedTextColor.GREEN);
    }

    /** Add a jar found on this server to the pack, in the layer the admin picked. */
    public synchronized void addPluginToPack(CommandSender sender, String jarName, String layer) throws Exception {
        PackRenderer renderer = renderer();
        PackRenderer.State state = PackRenderer.State.load(this.stateFile);

        String name = PackRenderer.derivePluginName(jarName);
        PackManifest manifest = readManifest();
        PackManifest.Entry entry = manifest.plugins.computeIfAbsent(name, key -> new PackManifest.Entry());
        entry.pluginJarWildcard = PackRenderer.deriveWildcard(jarName);

        // Only the jar joins the pack. Which of its files are config and which are player data is
        // not something to guess at, so configPaths stays empty until someone fills it in.
        Files.writeString(this.packDir.resolve("pack.json"), GSON.toJson(manifest), StandardCharsets.UTF_8);
        renderer.stage(jarName, layer);

        if (!commit(sender, "Add " + name + " to " + layer)) {
            return;
        }
        if (!push(sender)) {
            return;
        }

        state.files.put(jarName, new PackRenderer.State.Entry(layer, renderer.diskHash(jarName)));
        state.packHash = packHash();
        state.save(this.stateFile);

        reply(sender, name + " is now part of the pack in " + layer
                + ". Add its config paths to pack.json to sync those too.", NamedTextColor.GREEN);
    }

    /** Throw away the local edits: render the pack over them again, from what is already pulled. */
    public synchronized void resetRender(CommandSender sender) throws Exception {
        // A commitandpush that failed halfway can leave copies staged in the pack
        this.git.clean().setForce(true).setCleanDirectories(true).call();
        this.git.reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call();

        PackRenderer renderer = renderer();
        PackRenderer.State state = PackRenderer.State.load(this.stateFile);
        PackRenderer.Render render = renderer.apply(renderer.plan(readManifest()), state, true);
        state.packHash = packHash();
        state.save(this.stateFile);

        reply(sender, "Restored " + render.changed().size() + " file(s) from the pack.", NamedTextColor.GREEN);
    }

    private boolean commit(CommandSender sender, String message) throws Exception {
        this.git.add().addFilepattern(".").call();
        this.git.add().addFilepattern(".").setUpdate(true).call();

        if (this.git.status().call().isClean()) {
            reply(sender, "Nothing to commit, pushing...", NamedTextColor.YELLOW);
            return true;
        }

        this.git.commit()
                .setMessage(message)
                // Whoever ran the command owns the commit, the repository identity stays the committer
                .setAuthor(sender.getName(), sender.getName().toLowerCase() + "@minecraft.server.null")
                .call();
        reply(sender, "Commit created! Pushing...", NamedTextColor.GREEN);
        return true;
    }

    private boolean push(CommandSender sender) throws Exception {
        Iterable<PushResult> results = this.git.push()
                .setRemote(REMOTE)
                .setCredentialsProvider(credentials())
                .call();

        // JGit does not throw when the remote rejects the push, the status sits on each ref update
        boolean rejected = false;
        for (PushResult result : results) {
            for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                if (update.getStatus() == RemoteRefUpdate.Status.OK
                        || update.getStatus() == RemoteRefUpdate.Status.UP_TO_DATE) {
                    continue;
                }

                rejected = true;
                String reason = update.getMessage() != null ? update.getMessage() : update.getStatus().name();
                reply(sender, "Push rejected for " + update.getRemoteName() + ": " + reason, NamedTextColor.RED);
            }
        }

        if (rejected) {
            reply(sender, "Push failed. The remote most likely has commits you do not have, run /gitsync sync first.", NamedTextColor.RED);
        }
        return !rejected;
    }

    /** During bootstrap nothing is loaded yet, so there is nothing to reload either. */
    private void runReloadCommands(List<String> commands) {
        if (this.plugin == null) {
            if (!commands.isEmpty()) {
                this.logger.info("Server is not running yet, skipping reload commands: " + String.join(", ", commands));
            }
            return;
        }

        for (String command : commands) {
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                this.logger.info("Running reload command: " + command);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            });
        }
    }

    /**
     * Take the remote branch as-is, discarding whatever the local repository says. A hard reset
     * overwrites conflicting tracked files instead of refusing like a merge would.
     */
    private boolean resetToRemote(PluginConfiguration config, CommandSender feedback) throws Exception {
        this.git.fetch().setRemote(REMOTE).setCredentialsProvider(credentials()).setRemoveDeletedRefs(true).call();

        ObjectId target = this.git.getRepository().resolve(Constants.R_REMOTES + REMOTE + "/" + config.branch);
        if (target == null) {
            reply(feedback, "Remote branch " + config.branch + " not found.", NamedTextColor.RED);
            return false;
        }

        this.logger.info("Force sync: resetting onto " + REMOTE + "/" + config.branch + ", local changes are discarded.");
        this.git.reset().setMode(ResetCommand.ResetType.HARD).setRef(target.name()).call();
        return true;
    }

    /** Both the merged-with-conflict paths and the ones a checkout refused to overwrite. */
    private Set<String> conflictsOf(MergeResult merge) {
        Set<String> paths = new LinkedHashSet<>();
        if (merge == null) {
            return paths;
        }
        if (merge.getConflicts() != null) {
            paths.addAll(merge.getConflicts().keySet());
        }
        if (merge.getCheckoutConflicts() != null) {
            paths.addAll(merge.getCheckoutConflicts());
        }
        return paths;
    }

    private String mergeStatusOf(PullResult result) {
        MergeResult merge = result.getMergeResult();
        return merge != null ? merge.getMergeStatus().toString() : String.valueOf(result);
    }

    /** Name the files that blocked the pull, one per line so chat stays readable. */
    private void reportConflicts(CommandSender feedback, Set<String> conflicts, String status) {
        if (conflicts.isEmpty()) {
            this.logger.severe("Pull failed: " + status);
            reply(feedback, "Sync failed: " + status, NamedTextColor.RED);
            return;
        }

        this.logger.severe("Pull failed (" + status + "), conflicting files: " + String.join(", ", conflicts));
        reply(feedback, "Sync failed, " + conflicts.size() + " conflicting file(s) in the pack:", NamedTextColor.RED);
        for (String path : conflicts) {
            reply(feedback, "  " + path, NamedTextColor.DARK_RED);
        }
        reply(feedback, "Run /gitsync sync --force to take the remote as it is.", NamedTextColor.YELLOW);
    }

    /** The pack wants to change files that were edited on this server, so nothing was written. */
    private void reportLocalEdits(CommandSender feedback, List<String> conflicts) {
        this.logger.severe("Sync stopped, files edited on this server would be overwritten: " + String.join(", ", conflicts));
        reply(feedback, "Sync stopped, " + conflicts.size() + " file(s) were edited on this server:", NamedTextColor.RED);
        for (String path : conflicts) {
            reply(feedback, "  " + path, NamedTextColor.DARK_RED);
        }
        reply(feedback, "Publish them with /gitsync git commitandpush <message>, "
                + "or throw them away with /gitsync sync --force.", NamedTextColor.YELLOW);
    }

    /** Defaults applied once, when the repository is created. */
    private void initRepositoryConfig() throws IOException {
        StoredConfig stored = this.git.getRepository().getConfig();
        stored.setString("user", null, "name", "MC Server");
        stored.setString("user", null, "email", "minecraft@server.null");
        // Executable bits differ between the Windows and Linux servers sharing the repository
        stored.setBoolean("core", null, "fileMode", false);
        stored.setString("credential", null, "helper", "store");

        // Disable gpg signing
        stored.setBoolean("commit", null, "gpgsign", false);
        stored.setBoolean("tag", null, "gpgsign", false);

        stored.save();
    }

    /** Applied on every sync, so repositories created before a setting existed pick it up too. */
    private void configureRepository(PluginConfiguration config) throws IOException {
        StoredConfig stored = this.git.getRepository().getConfig();
        stored.setString("remote", REMOTE, "url", config.remote);
        stored.setString("remote", REMOTE, "fetch", "+refs/heads/*:" + Constants.R_REMOTES + REMOTE + "/*");
        // Everything is stored and checked out as LF, on Windows too. A config saved with CRLF by
        // an editor is normalized back on staging, so it never shows up as a whole file diff, and
        // the Windows and Linux servers sharing this repository can never disagree about it.
        stored.setString("core", null, "autocrlf", "input");
        stored.save();
    }

    private PackManifest readManifest() throws IOException {
        File file = this.packDir.resolve("pack.json").toFile();
        if (!file.isFile()) {
            this.logger.warning("pack.json not found in the repository root.");
            return new PackManifest();
        }
        return PackManifest.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    /** Null when the repository has no pack.json yet. */
    private String packHash() throws IOException {
        Path file = this.packDir.resolve("pack.json");
        return Files.isRegularFile(file) ? PackRenderer.hash(Files.readAllBytes(file)) : null;
    }

    private String describeHead() throws IOException {
        Repository repository = this.git.getRepository();
        ObjectId head = repository.resolve(Constants.HEAD);
        return head == null ? "<nothing pulled yet>" : head.name().substring(0, 7);
    }

    private void reply(CommandSender sender, String message, NamedTextColor color) {
        if (sender != null) {
            sender.sendMessage(Component.text(message).color(color));
        }
    }
}
