package pl.fanth.gitsync.git;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.scheduler.BukkitTask;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.RefNotAdvertisedException;
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
import java.io.InputStream;
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
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
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
    // Plugins whose jar on disk stopped matching what this server loaded - a replaced jar, or a
    // brand new one the server never loaded. Reloading them is guesswork, so they sit out every
    // reload until the restart that also clears this set. Everything else keeps reloading.
    private final Set<String> blockedPlugins = new LinkedHashSet<>();
    // The sync runs on a timer, so a missing variable is only worth saying when the set changes
    private Set<String> reportedMissingVariables = Set.of();

    private Git git;
    private BukkitTask task;
    private volatile boolean lastSyncFailed;
    // What the last render could not fill in, so the bootstrap can refuse to boot on it
    private volatile Map<String, Set<String>> unresolvedVariables = Map.of();

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

    /** Variable names the last render found in the pack that server.yml does not set, to the files using them. */
    public Map<String, Set<String>> unresolvedVariables() {
        return this.unresolvedVariables;
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
        return new PackRenderer(this.packDir, this.pluginsDir, server.role, server.instance, server.variables);
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

            // What the pack held before this pull, so a plugin dropped from it can still be named
            Set<String> pluginsBefore = Set.copyOf(readManifest().plugins.keySet());

            if (force) {
                if (!resetToRemote(config, feedback)) {
                    return Set.of();
                }
            } else {
                PullResult result;
                try {
                    result = this.git.pull()
                            .setRemote(REMOTE)
                            .setRemoteBranchName(config.branch)
                            .setCredentialsProvider(credentials())
                            .call();
                } catch (RefNotAdvertisedException exception) {
                    // Empty remote, there is no branch to pull yet. The first push creates it.
                    this.logger.info("Remote has no branch " + config.branch + " yet, nothing to pull.");
                    reply(feedback, "The remote repository is still empty.", NamedTextColor.YELLOW);
                    this.lastSyncFailed = false;
                    return Set.of();
                }

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
            this.unresolvedVariables = render.unresolved();
            reportUnresolvedVariables(render.unresolved());
            if (!render.unresolved().isEmpty()) {
                // Nothing was written, the pack asks for values this server does not have
                this.lastSyncFailed = true;
                reportMissingVariables(feedback, render.unresolved());
                return Set.of();
            }

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
            state.save(this.stateFile);

            // A jar written by this render no longer matches the code the server loaded - either a
            // replaced jar or a brand new plugin the server has never loaded at all
            Set<String> jarChanged = manifest.pluginsWithChangedJar(changed);
            this.blockedPlugins.addAll(jarChanged);
            for (String name : jarChanged) {
                if (!pluginsBefore.contains(name)) {
                    this.restartReasons.put(name, "new plugin, loads on the next start");
                }
            }
            // A plugin dropped from the pack loses its manifest entry too, so nothing below can
            // match the paths it left behind. Its files disappear from disk while the server
            // keeps it loaded, and only a restart settles that.
            for (String name : pluginsBefore) {
                if (!manifest.plugins.containsKey(name)) {
                    this.restartReasons.put(name, "removed from the pack");
                }
            }

            this.restartReasons.putAll(manifest.restartRequiredPaths(changed));
            List<String> commands = manifest.reloadCommandsFor(changed, this.blockedPlugins);

            if (changed.isEmpty() && !force) {
                reply(feedback, "Already up to date.", NamedTextColor.YELLOW);
                return Set.of();
            }

            this.logger.info("Synced " + describeHead() + " (" + changed.size() + " file(s) changed, " + commands.size() + " reload command(s)).");
            reply(feedback, "Synced " + describeHead()
                    + " (" + changed.size() + " file(s) changed, " + commands.size() + " reload command(s)).", NamedTextColor.GREEN);
            if (!jarChanged.isEmpty()) {
                this.logger.info("Plugin jar(s) changed on disk, these plugins will not be reloaded until the "
                        + "server restarts: " + String.join(", ", jarChanged));
                reply(feedback, jarChanged.size() + " plugin(s) will not be reloaded until the server restarts: "
                        + String.join(", ", jarChanged), NamedTextColor.YELLOW);
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

    /**
     * The name a plugin calls itself, taken from the descriptor inside its jar - which is also the
     * name of its data folder, so it is what a config path is built from. Falls back to the file
     * name for a jar carrying no descriptor this server can read.
     */
    public String pluginName(String jarName) {
        try (JarFile jar = new JarFile(this.pluginsDir.resolve(jarName).toFile())) {
            for (String descriptor : List.of("plugin.yml", "paper-plugin.yml")) {
                JarEntry entry = jar.getJarEntry(descriptor);
                if (entry == null) {
                    continue;
                }
                try (InputStream stream = jar.getInputStream(entry)) {
                    return new PluginDescriptionFile(stream).getName();
                }
            }
        } catch (Exception exception) {
            this.logger.log(Level.FINE, "Could not read the plugin name out of " + jarName, exception);
        }
        return PackRenderer.derivePluginName(jarName);
    }

    /** Jars in plugins/ that no pack entry claims and this server was not told to keep private. */
    public List<String> unknownJars() throws IOException {
        List<String> ignored = new ArrayList<>();
        if (this.plugin != null) {
            ignored.addAll(this.plugin.dataConfiguration().ignoredPluginWildcards);
            // Our own jar is not part of any pack, asking about it on every commit is noise
            ignored.add(this.plugin.getFile().getName());
        }
        List<String> jars = new ArrayList<>(renderer().unknownJars(readManifest(), ignored));
        if (this.plugin != null) {
            // An update of our own jar uploaded while the server runs carries a different file
            // name than the loaded one, so it is recognized by the plugin name inside it instead
            jars.removeIf(jar -> this.plugin.getName().equals(pluginName(jar)));
        }
        return jars;
    }

    /**
     * Publish the edits made on this server: every declared file that drifted goes back into the
     * layer it was rendered from, and a file deleted here is dropped from its layer - which
     * re-exposes the copy in the layer below it. Variables are put back on the way in, so this
     * server's own values stay here.
     *
     * @param confirmed publish even the variable lines that cannot be put back
     * @param newPlugins jars this server holds that the admin decided to put into the pack
     * @param fileLayers where a file the pack has never seen goes, keyed by logical path
     * @param skipped logical paths left out of this push only, nothing about them is written down
     * @return the variable lines that stopped the commit, empty when it went through
     */
    public synchronized List<String> commitAndPush(CommandSender sender, String message, boolean confirmed,
                                                   List<NewPlugin> newPlugins,
                                                   Map<String, String> fileLayers,
                                                   Set<String> skipped) throws Exception {
        PackRenderer renderer = renderer();
        PackRenderer.State state = PackRenderer.State.load(this.stateFile);

        // Declared before the local changes are worked out, so a jar joining the pack and the
        // config paths typed in for it are published by this very commit. Only in memory until
        // the variable check below has passed, so a refusal still leaves the pack untouched.
        PackManifest manifest = readManifest();
        Map<String, String> addedLayers = new LinkedHashMap<>();
        for (NewPlugin added : newPlugins) {
            String name = pluginName(added.jar());
            PackManifest.Entry entry = manifest.plugins.computeIfAbsent(name, key -> new PackManifest.Entry());
            entry.pluginJarWildcard = added.wildcard();
            entry.configPaths = new ArrayList<>(added.configPaths());
            entry.reloadCommands = new ArrayList<>(added.reloadCommands());
            addedLayers.put(name, added.layer());
        }

        List<PackRenderer.LocalChange> changes = new ArrayList<>(renderer.localChanges(manifest, state));
        // Nothing is staged and nothing goes into the state for these, so they turn up as new again
        changes.removeIf(change -> skipped.contains(change.logicalPath()));
        changes.replaceAll(change -> retarget(change, manifest, addedLayers, fileLayers));

        // Worked out before anything is written, so a refusal leaves the pack untouched
        Map<String, PackRenderer.Reversal> reversals = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (PackRenderer.LocalChange change : changes) {
            if (change.kind() == PackRenderer.Kind.DELETED) {
                continue;
            }
            PackRenderer.Reversal reversal = renderer.reverse(change.logicalPath(), change.targetLayer());
            reversals.put(change.logicalPath(), reversal);
            warnings.addAll(reversal.warnings());
        }

        if (!warnings.isEmpty() && !confirmed) {
            reportFlattenedVariables(sender, warnings);
            return warnings;
        }

        if (!newPlugins.isEmpty()) {
            Files.writeString(this.packDir.resolve("pack.json"), GSON.toJson(manifest), StandardCharsets.UTF_8);
        }

        for (PackRenderer.LocalChange change : changes) {
            if (change.kind() == PackRenderer.Kind.DELETED) {
                renderer.unstage(change.logicalPath(), change.targetLayer());
            } else {
                renderer.stage(change.logicalPath(), change.targetLayer(), reversals.get(change.logicalPath()));
            }
        }

        if (!commit(sender, message)) {
            return List.of();
        }
        if (!push(sender)) {
            return List.of();
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
        if (!newPlugins.isEmpty()) {
            reply(sender, newPlugins.size() + " new plugin(s) are now part of the pack.", NamedTextColor.GREEN);
        }
        if (!warnings.isEmpty()) {
            reply(sender, "Published " + warnings.size() + " line(s) with this server's own values in them.", NamedTextColor.YELLOW);
        }
        return List.of();
    }

    /**
     * Has pack.json been edited here since the last commit? A /gitsync pack edit only writes it,
     * so this is the only trace of it until a pushupdate carries it along.
     */
    public boolean packEdited() throws Exception {
        return !this.git.status().addPath("pack.json").call().isClean();
    }

    /**
     * Write an edited entry into the local pack.json, committing nothing: it rides along with the
     * next pushupdate, the same commit that publishes the files a new config path now claims.
     */
    public synchronized void saveEntry(CommandSender sender, String name, PackManifest.Entry entry) throws Exception {
        PackManifest manifest = readManifest();
        manifest.plugins.put(name, entry);
        Files.writeString(this.packDir.resolve("pack.json"), GSON.toJson(manifest), StandardCharsets.UTF_8);
        reply(sender, name + " updated in the local pack.json, nothing was pushed yet.", NamedTextColor.GREEN);
        reply(sender, "Run /gitsync pushupdate to publish it together with the files it claims.", NamedTextColor.YELLOW);
    }

    /**
     * Where a change really goes. Everything a plugin joining the pack owns follows the layer
     * picked for that plugin, and a file the pack has never seen follows the layer picked for it -
     * both of them beat the layer the renderer guessed at.
     */
    private PackRenderer.LocalChange retarget(PackRenderer.LocalChange change, PackManifest manifest,
                                              Map<String, String> addedLayers, Map<String, String> fileLayers) {
        String picked = fileLayers.get(change.logicalPath());
        if (picked != null) {
            return new PackRenderer.LocalChange(change.logicalPath(), change.kind(), picked);
        }
        // Nobody named this one, so a plugin joining the pack takes whatever it owns along with it
        for (Map.Entry<String, String> added : addedLayers.entrySet()) {
            if (manifest.plugins.get(added.getKey()).matches(change.logicalPath())) {
                return new PackRenderer.LocalChange(change.logicalPath(), change.kind(), added.getValue());
            }
        }
        return change;
    }

    /**
     * A jar found in plugins/ that the admin placed in a layer, with what it owns and how it
     * reloads. The wildcard is what the pack matches jars by, so a version bump keeps the entry.
     */
    public record NewPlugin(String jar, String wildcard, String layer,
                            List<String> configPaths, List<String> reloadCommands) {
    }

    /** Throw away the local edits: render the pack over them again, from what is already pulled. */
    public synchronized void resetRender(CommandSender sender) throws Exception {
        // A pushupdate that failed halfway can leave copies staged in the pack
        this.git.clean().setForce(true).setCleanDirectories(true).call();
        this.git.reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call();

        PackRenderer renderer = renderer();
        PackRenderer.State state = PackRenderer.State.load(this.stateFile);
        PackRenderer.Render render = renderer.apply(renderer.plan(readManifest()), state, true);
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
        // A repository that never got a commit has no ref to push, JGit only says so by throwing
        if (this.git.getRepository().resolve(Constants.HEAD) == null) {
            reply(sender, "Nothing to push, the repository has no commits yet.", NamedTextColor.YELLOW);
            return false;
        }

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

    /**
     * A packed file asks for a value this server never declared. The rendered file keeps the ${GITSYNC_NAME}
     * as it stands, which the plugin owning it will almost certainly not understand, so say it out
     * loud - most often the server was set up without a variable its role expects.
     */
    private void reportUnresolvedVariables(Map<String, Set<String>> unresolved) {
        if (unresolved.keySet().equals(this.reportedMissingVariables)) {
            return;
        }
        this.reportedMissingVariables = new LinkedHashSet<>(unresolved.keySet());

        unresolved.forEach((name, files) -> this.logger.warning(
                "Variable ${" + PackRenderer.VARIABLE_PREFIX + name + "} is used by "
                + String.join(", ", files) + " but server.yml does not set it."));
    }

    /** The sync stopped before writing anything, because the pack needs values server.yml does not set. */
    private void reportMissingVariables(CommandSender sender, Map<String, Set<String>> unresolved) {
        reply(sender, "Nothing was synced. server.yml is missing " + unresolved.size() + " variable(s):", NamedTextColor.RED);
        unresolved.forEach((name, files) -> reply(sender, "  " + PackRenderer.VARIABLE_PREFIX + name
                + " - used by " + String.join(", ", files), NamedTextColor.DARK_RED));
        reply(sender, "Add them under variables: in server.yml (without the "
                + PackRenderer.VARIABLE_PREFIX + " prefix) and sync again.", NamedTextColor.YELLOW);
    }

    /**
     * A line holding a variable was edited by hand, so there is no way to tell the value apart from
     * the edit and put the variable back. Committing it would hand this server's value to every
     * other server rendering that file, which is worth stopping for.
     */
    private void reportFlattenedVariables(CommandSender sender, List<String> warnings) {
        this.logger.warning("Commit stopped, " + warnings.size()
                + " line(s) hold a variable that was edited by hand: " + String.join("; ", warnings));

        reply(sender, "Nothing was committed. " + warnings.size() + " line(s) hold a variable that was", NamedTextColor.RED);
        reply(sender, "edited by hand, so this server's own values would go to every other server:", NamedTextColor.RED);
        for (String warning : warnings) {
            reply(sender, "  " + warning, NamedTextColor.DARK_RED);
        }
        reply(sender, "Put the ${GITSYNC_VARIABLE} back in the file to keep it shared, or publish anyway.", NamedTextColor.YELLOW);
    }

    /** The pack wants to change files that were edited on this server, so nothing was written. */
    private void reportLocalEdits(CommandSender feedback, List<String> conflicts) {
        this.logger.severe("Sync stopped, files edited on this server would be overwritten: " + String.join(", ", conflicts));
        reply(feedback, "Sync stopped, " + conflicts.size() + " file(s) were edited on this server:", NamedTextColor.RED);
        for (String path : conflicts) {
            reply(feedback, "  " + path, NamedTextColor.DARK_RED);
        }
        reply(feedback, "Publish them with /gitsync pushupdate <message>, "
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
        // What "git push --set-upstream origin <branch>" would write. Without it a plain git push
        // run by hand in the pack directory stops and asks for the upstream, and autoSetupRemote
        // covers whatever other branch someone checks out in there later.
        stored.setString("branch", config.branch, "remote", REMOTE);
        stored.setString("branch", config.branch, "merge", Constants.R_HEADS + config.branch);
        stored.setBoolean("push", null, "autoSetupRemote", true);
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
