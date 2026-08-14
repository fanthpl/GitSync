package pl.fanth.gitsync.git;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import pl.fanth.gitsync.GitSyncPlugin;
import pl.fanth.gitsync.config.PluginConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private final File repoDir;
    private final Logger logger;
    private final Supplier<PluginConfiguration> configSupplier;
    private final GitSyncPlugin plugin;

    private final AtomicBoolean syncing = new AtomicBoolean();
    // Cleared by the restart itself, so it never needs to be persisted
    private final Map<String, String> restartReasons = new LinkedHashMap<>();

    private Git git;
    private BukkitTask task;
    private volatile boolean lastSyncFailed;

    /** Bootstrap phase, no server available. */
    public GitSyncService(Path repoDir, Logger logger, Supplier<PluginConfiguration> configSupplier) {
        this(repoDir, logger, configSupplier, null);
    }

    /** Runtime, the server is up so reload commands and the periodic check can run. */
    public GitSyncService(GitSyncPlugin plugin) {
        // The repository lives in the plugins/ directory itself
        this(plugin.getDataFolder().getParentFile().toPath(), plugin.getLogger(), plugin::pluginConfiguration, plugin);
    }

    private GitSyncService(Path repoDir, Logger logger, Supplier<PluginConfiguration> configSupplier, GitSyncPlugin plugin) {
        this.repoDir = repoDir.toFile();
        this.logger = logger;
        this.configSupplier = configSupplier;
        this.plugin = plugin;
    }

    /** Open or create the repository. Returns false when GitSync cannot work at all. */
    public boolean open() {
        PluginConfiguration config = this.configSupplier.get();
        if (config.remote.isBlank()) {
            this.logger.warning("No remote configured in config.yml, GitSync is idle.");
            return false;
        }

        try {
            this.git = Git.open(this.repoDir);
        } catch (IOException exception) {
            try {
                this.git = Git.init().setDirectory(this.repoDir).setInitialBranch(config.branch).call();
                initRepositoryConfig();
            } catch (Exception initException) {
                this.logger.log(Level.SEVERE, "Could not initialize the git repository in " + this.repoDir, initException);
                return false;
            }
        }
        return true;
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

    /** The pack as it currently sits on disk, empty when there is no readable pack.json. */
    public PackManifest manifest() {
        try {
            return readManifest();
        } catch (IOException exception) {
            this.logger.log(Level.WARNING, "Could not read pack.json", exception);
            return new PackManifest();
        }
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
     * Pull the remote branch and run reload commands for whatever changed.
     * <p>
     * Force replaces the pull with a hard reset onto the remote branch, so local edits to tracked
     * files and anything that would conflict are discarded instead of aborting the sync. What the
     * reset restores from the working tree is invisible to a commit diff, so a force sync marks the
     * server as needing a restart instead of guessing. Untracked files stay untouched, so plugins
     * outside pack.json are never affected.
     *
     * @return the repository relative paths that changed, empty when nothing did or the sync failed
     */
    public synchronized Set<String> sync(CommandSender feedback, boolean force) {
        if (this.git == null) {
            reply(feedback, "GitSync is not initialized, check the console.", NamedTextColor.RED);
            return Set.of();
        }

        this.syncing.set(true);
        PluginConfiguration config = this.configSupplier.get();
        try {
            configureRemote(config);

            Repository repository = this.git.getRepository();
            ObjectId before = repository.resolve(Constants.HEAD);

            // Before anything touches the working tree or the index. Without a .gitignore nothing
            // in plugins/ is ignored, and every unrelated plugin becomes ours to add and delete.
            if (new File(this.repoDir, "pack.json").isFile()) {
                try {
                    writeGitignore(readManifest());
                } catch (Exception ex) {
                    this.logger.log(Level.SEVERE, "Failed to write .gitignore", ex);
                }
            }

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
                    this.logger.severe("Pull failed: " + describeFailure(result));
                    reply(feedback, "Sync failed: " + describeFailure(result), NamedTextColor.RED);
                    return Set.of();
                }
            }

            this.lastSyncFailed = false;

            ObjectId after = repository.resolve(Constants.HEAD);
            if (after == null) {
                reply(feedback, "The remote repository has no commits yet.", NamedTextColor.YELLOW);
                return Set.of();
            }

            boolean upToDate = after.equals(before);
            if (upToDate && !force) {
                reply(feedback, "Already up to date.", NamedTextColor.YELLOW);
                return Set.of();
            }

            // A first checkout touches every file, reloading everything then is pointless noise
            Set<String> changed = (before == null || upToDate) ? Set.of() : changedPaths(repository, before, after);

            PackManifest manifest = readManifest();
            writeGitignore(manifest);

            this.restartReasons.putAll(manifest.restartRequiredPaths(changed));
            if (changed.contains("pack.json")) {
                // A plugin dropped from the pack is gone from the manifest as well, so nothing
                // above can match the paths it left behind. Its files disappear from disk while
                // the server keeps it loaded, and only a restart settles that.
                this.restartReasons.put("pack.json", "the pack composition changed");
            }
            if (force) {
                // A hard reset also restores files that only drifted in the working tree, and a
                // commit to commit diff cannot see those. Reloading the whole pack to cover that
                // blind spot is expensive and still not a guarantee, so say what is actually true.
                this.restartReasons.put("force sync", "local changes to tracked files were discarded");
            }

            List<String> commands = manifest.reloadCommandsFor(changed);
            this.logger.info("Pulled " + after.name().substring(0, 7) + " (" + changed.size() + " file(s) changed, " + commands.size() + " reload command(s)).");
            reply(feedback, "Synced " + after.name().substring(0, 7)
                    + " (" + changed.size() + " file(s) changed, " + commands.size() + " reload command(s)).", NamedTextColor.GREEN);
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

    /** The file that keeps every plugin outside pack.json invisible to git. */
    public File gitignoreFile() {
        return new File(this.repoDir, ".gitignore");
    }

    private void writeGitignore(PackManifest manifest) throws IOException {
        Files.write(gitignoreFile().toPath(), manifest.gitignoreLines(), StandardCharsets.UTF_8);
    }

    /**
     * Take the remote branch as-is, discarding whatever the working tree says. A hard reset
     * overwrites conflicting tracked files instead of refusing like a merge would, and leaves
     * untracked files alone, so nothing outside the pack can be lost.
     */
    private boolean resetToRemote(PluginConfiguration config, CommandSender feedback) throws Exception {
        this.git.fetch().setRemote(REMOTE).setCredentialsProvider(credentials()).setRemoveDeletedRefs(true).call();

        ObjectId target = this.git.getRepository().resolve(Constants.R_REMOTES + REMOTE + "/" + config.branch);
        if (target == null) {
            reply(feedback, "Remote branch " + config.branch + " not found.", NamedTextColor.RED);
            return false;
        }

        this.logger.info("Force sync: resetting onto " + REMOTE + "/" + config.branch + ", local changes to tracked files are discarded.");
        this.git.reset().setMode(ResetCommand.ResetType.HARD).setRef(target.name()).call();
        return true;
    }

    /** Turn a failed PullResult into something readable in the console. */
    private String describeFailure(PullResult result) {
        MergeResult merge = result.getMergeResult();
        if (merge == null) {
            return String.valueOf(result);
        }
        if (merge.getConflicts() != null && !merge.getConflicts().isEmpty()) {
            return "merge conflict in " + String.join(", ", merge.getConflicts().keySet())
                    + " - resolve it manually in the plugins directory.";
        }
        if (merge.getCheckoutConflicts() != null && !merge.getCheckoutConflicts().isEmpty()) {
            return "local files would be overwritten: " + String.join(", ", merge.getCheckoutConflicts())
                    + " - move or delete them, or commit them first.";
        }
        return merge.getMergeStatus().toString();
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

    private void configureRemote(PluginConfiguration config) throws IOException {
        StoredConfig stored = this.git.getRepository().getConfig();
        stored.setString("remote", REMOTE, "url", config.remote);
        stored.setString("remote", REMOTE, "fetch", "+refs/heads/*:" + Constants.R_REMOTES + REMOTE + "/*");
        stored.save();
    }

    private PackManifest readManifest() throws IOException {
        File file = new File(this.repoDir, "pack.json");
        if (!file.isFile()) {
            this.logger.warning("pack.json not found in the repository root.");
            return new PackManifest();
        }
        return PackManifest.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    private Set<String> changedPaths(Repository repository, ObjectId from, ObjectId to) throws Exception {
        Set<String> paths = new LinkedHashSet<>();
        try (ObjectReader reader = repository.newObjectReader(); RevWalk walk = new RevWalk(repository)) {
            AbstractTreeIterator oldTree = treeOf(reader, walk, from);
            AbstractTreeIterator newTree = treeOf(reader, walk, to);
            for (DiffEntry entry : this.git.diff().setOldTree(oldTree).setNewTree(newTree).call()) {
                if (entry.getChangeType() != DiffEntry.ChangeType.ADD) {
                    paths.add(entry.getOldPath());
                }
                if (entry.getChangeType() != DiffEntry.ChangeType.DELETE) {
                    paths.add(entry.getNewPath());
                }
            }
        }
        return paths;
    }

    private AbstractTreeIterator treeOf(ObjectReader reader, RevWalk walk, ObjectId commit) throws IOException {
        if (commit == null) {
            return new EmptyTreeIterator();
        }
        CanonicalTreeParser parser = new CanonicalTreeParser();
        parser.reset(reader, walk.parseCommit(commit).getTree());
        return parser;
    }

    private void reply(CommandSender sender, String message, NamedTextColor color) {
        if (sender != null) {
            sender.sendMessage(Component.text(message).color(color));
        }
    }
}
