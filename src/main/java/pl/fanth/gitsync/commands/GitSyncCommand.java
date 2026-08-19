package pl.fanth.gitsync.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.CommandHelp;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Default;
import co.aikar.commands.annotation.Description;
import co.aikar.commands.annotation.HelpCommand;
import co.aikar.commands.annotation.Optional;
import co.aikar.commands.annotation.Subcommand;
import co.aikar.commands.annotation.Syntax;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import pl.fanth.gitsync.GitSyncPlugin;
import pl.fanth.gitsync.config.PluginConfiguration;
import pl.fanth.gitsync.git.GitSyncService;
import pl.fanth.gitsync.git.PackManifest;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

@CommandAlias("gitsync")
@CommandPermission("gitsync.admin")
public class GitSyncCommand extends BaseCommand {
    /** Chat scrollback is short, a full config rewrite would push everything else out of it. */
    private static final int DIFF_LINE_LIMIT = 120;

    @HelpCommand
    public void doHelp(CommandSender sender, CommandHelp help) {
        help.showHelp();
    }

    @Subcommand("sync")
    @Description("Sync the pack with the remote repository. force discards local changes and reloads everything")
    @Syntax("[force]")
    public void sync(CommandSender sender, @Default("false") boolean force) {
        GitSyncService service = GitSyncPlugin.instance().gitSyncService();
        if (service.isSyncing()) {
            send(sender, "A sync is already in progress!", NamedTextColor.RED);
            return;
        }

        send(sender, "Syncing...", NamedTextColor.GREEN);
        Bukkit.getScheduler().runTaskAsynchronously(GitSyncPlugin.instance(), () -> service.sync(sender, force));
    }

    @Subcommand("reload")
    @Description("Reload the plugin configuration")
    public void reload(CommandSender sender) {
        GitSyncPlugin plugin = GitSyncPlugin.instance();
        plugin.reloadConfiguration();
        plugin.gitSyncService().stop();
        plugin.gitSyncService().start();
        send(sender, "Reloaded!", NamedTextColor.GREEN);
    }

    @Subcommand("git resethead")
    @Description("Reset the repository to HEAD (git reset --hard HEAD)")
    public void resetHead(CommandSender sender) {
        send(sender, "Resetting the repository to HEAD...", NamedTextColor.GREEN);

        runAsync(sender, "resetting the repository", git -> {
            if (!hasGitignore(sender, git)) {
                return;
            }

            // Delete untracked files too
            git.clean()
                .setForce(true)
                .setCleanDirectories(true)
                .call();

            git.reset()
                .setMode(ResetCommand.ResetType.HARD)
                .setRef(Constants.HEAD)
                .call();

            send(sender, "Successfully reset the repository to HEAD!", NamedTextColor.GREEN);
        });
    }

    @Subcommand("status")
    @Description("Show the pack state and whether the server needs a restart")
    public void status(CommandSender sender) {
        GitSyncService service = GitSyncPlugin.instance().gitSyncService();
        PluginConfiguration config = GitSyncPlugin.instance().pluginConfiguration();

        runAsync(sender, "reading the pack state", git -> {
            Repository repo = git.getRepository();
            PackManifest manifest = service.manifest();

            send(sender, "--- GitSync ---", NamedTextColor.GOLD);
            field(sender, "Remote", config.remote.isBlank() ? "<not configured>" : config.remote);
            field(sender, "Branch", repo.getBranch());
            field(sender, "Commit", describeHead(repo));
            field(sender, "Pack", manifest.plugins.size() + " plugin(s): " + String.join(", ", manifest.plugins.keySet()));
            field(sender, "Auto sync", "every " + config.checkIntervalSeconds + "s" + (service.isSyncing() ? " (running now)" : ""));

            Map<String, String> reasons = service.restartReasons();
            if (reasons.isEmpty()) {
                field(sender, "Restart required", "no");
                return;
            }

            sender.sendMessage(Component.text("Restart required: ").color(NamedTextColor.GRAY)
                .append(Component.text("YES").color(NamedTextColor.RED)));
            for (Map.Entry<String, String> reason : reasons.entrySet()) {
                sender.sendMessage(Component.text("  " + reason.getKey() + " ").color(NamedTextColor.WHITE)
                    .append(Component.text("(" + reason.getValue() + ")").color(NamedTextColor.GRAY)));
            }
        });
    }

    /** Short hash and subject of HEAD, or a note when the repository has no commits yet. */
    private String describeHead(Repository repo) throws Exception {
        ObjectId head = repo.resolve(Constants.HEAD);
        if (head == null) {
            return "<nothing pulled yet>";
        }
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit commit = walk.parseCommit(head);
            return head.name().substring(0, 7) + " " + commit.getShortMessage();
        }
    }

    private void field(CommandSender sender, String name, String value) {
        sender.sendMessage(Component.text(name + ": ").color(NamedTextColor.GRAY)
            .append(Component.text(value).color(NamedTextColor.WHITE)));
    }

    @Subcommand("git status")
    @Description("Show the working tree state (git status)")
    public void gitStatus(CommandSender sender) {
        send(sender, "Checking the repository status...", NamedTextColor.GREEN);

        runAsync(sender, "checking the repository status", git -> {
            Status status = git.status().call();

            if (status.isClean()) {
                send(sender, "No changes in the repository.", NamedTextColor.GREEN);
                return;
            }

            send(sender, "--- Repository status ---", NamedTextColor.GOLD);
            listFiles(sender, status.getChanged(), "[M] ", NamedTextColor.YELLOW);
            listFiles(sender, status.getModified(), "[M] ", NamedTextColor.YELLOW);
            listFiles(sender, status.getAdded(), "[A] ", NamedTextColor.GREEN);
            listFiles(sender, status.getRemoved(), "[D] ", NamedTextColor.RED);
            listFiles(sender, status.getMissing(), "[D] ", NamedTextColor.RED);
            listFiles(sender, status.getConflicting(), "[C] ", NamedTextColor.DARK_RED);
            listFiles(sender, status.getUntracked(), "[?] ", NamedTextColor.GRAY);
        });
    }

    @Subcommand("git diff")
    @Description("Show what differs between the last commit and the files on disk (git diff HEAD)")
    @Syntax("[path]")
    public void gitDiff(CommandSender sender, @Optional String path) {
        send(sender, "Building the diff...", NamedTextColor.GREEN);

        runAsync(sender, "building the diff", git -> {
            Repository repo = git.getRepository();
            ObjectId head = repo.resolve(Constants.HEAD);
            if (head == null) {
                send(sender, "Nothing has been committed yet.", NamedTextColor.RED);
                return;
            }

            // Status is the only thing here that honors .gitignore, so it decides which paths the
            // formatter may look at. Handed the working tree directly it reports every unrelated
            // plugin on the server as newly added.
            Set<String> paths = changedPaths(git.status().call());
            if (path != null) {
                String prefix = path.endsWith("/") ? path : path + "/";
                paths.removeIf(file -> !file.equals(path) && !file.startsWith(prefix));
            }

            if (paths.isEmpty()) {
                send(sender, path == null ? "No changes." : "No changes under " + path + ".", NamedTextColor.GREEN);
                return;
            }

            try (ObjectReader reader = repo.newObjectReader();
                 RevWalk walk = new RevWalk(repo);
                 ByteArrayOutputStream out = new ByteArrayOutputStream();
                 DiffFormatter formatter = new DiffFormatter(out)) {
                CanonicalTreeParser headTree = new CanonicalTreeParser();
                headTree.reset(reader, walk.parseCommit(head).getTree());

                formatter.setRepository(repo);
                // Kills the line ending churn: a file that only swapped CRLF for LF produces no hunk
                formatter.setDiffComparator(RawTextComparator.WS_IGNORE_TRAILING);
                formatter.setPathFilter(PathFilterGroup.createFromStrings(paths));
                formatter.setContext(2);

                send(sender, "--- Diff against " + head.name().substring(0, 7) + " ---", NamedTextColor.GOLD);

                int budget = DIFF_LINE_LIMIT;
                int truncated = 0;
                int unchanged = 0;
                for (DiffEntry entry : formatter.scan(headTree, new FileTreeIterator(repo))) {
                    out.reset();
                    formatter.format(entry);
                    formatter.flush();

                    String raw = out.toString(StandardCharsets.UTF_8);
                    List<String> hunks = hunkLines(raw);
                    String file = entry.getChangeType() == DiffEntry.ChangeType.DELETE ? entry.getOldPath() : entry.getNewPath();

                    if (hunks.isEmpty()) {
                        // A jar has no lines to show, but it very much did change
                        if (raw.contains("Binary files") || raw.contains("GIT binary patch")) {
                            send(sender, entry.getChangeType() + " " + file + " (binary)", NamedTextColor.AQUA);
                        } else {
                            unchanged++;
                        }
                        continue;
                    }

                    send(sender, entry.getChangeType() + " " + file, NamedTextColor.AQUA);

                    for (String line : hunks) {
                        if (budget-- <= 0) {
                            truncated++;
                            continue;
                        }
                        sender.sendMessage(Component.text(shorten(line)).color(diffColor(line)));
                    }
                }

                if (unchanged > 0) {
                    send(sender, unchanged + " file(s) differ only in line endings or whitespace.", NamedTextColor.GRAY);
                }
                if (truncated > 0) {
                    send(sender, "... " + truncated + " more line(s), narrow it down with /gitsync git diff <path>", NamedTextColor.GRAY);
                }
            }
        });
    }

    /** Everything git considers changed, minus whatever .gitignore hides. */
    private Set<String> changedPaths(Status status) {
        Set<String> paths = new LinkedHashSet<>();
        paths.addAll(status.getChanged());
        paths.addAll(status.getModified());
        paths.addAll(status.getAdded());
        paths.addAll(status.getRemoved());
        paths.addAll(status.getMissing());
        paths.addAll(status.getUntracked());
        paths.addAll(status.getConflicting());
        return paths;
    }

    /** The hunks only, the git headers above them say nothing a chat reader needs. */
    private List<String> hunkLines(String diff) {
        List<String> lines = new ArrayList<>();
        for (String line : diff.split("\n")) {
            if (!lines.isEmpty() || line.startsWith("@@")) {
                lines.add(line.stripTrailing());
            }
        }
        return lines;
    }

    private NamedTextColor diffColor(String line) {
        if (line.startsWith("@@")) {
            return NamedTextColor.GOLD;
        }
        if (line.startsWith("+")) {
            return NamedTextColor.GREEN;
        }
        return line.startsWith("-") ? NamedTextColor.RED : NamedTextColor.GRAY;
    }

    /** A minified json or a long permission list would otherwise flood the chat with one line. */
    private String shorten(String line) {
        return line.length() > 160 ? line.substring(0, 157) + "..." : line;
    }

    @Subcommand("git showahead")
    @Description("Show commits present locally but missing on the remote (git log origin/<branch>..HEAD)")
    public void showAhead(CommandSender sender) {
        send(sender, "Fetching commits...", NamedTextColor.GREEN);

        runAsync(sender, "listing the local commits", git -> {
            Repository repo = git.getRepository();
            String branch = repo.getBranch();

            ObjectId localHead = repo.resolve(Constants.HEAD);
            ObjectId remoteHead = repo.resolve(Constants.R_REMOTES + "origin/" + branch);

            if (remoteHead == null) {
                send(sender, "Remote branch origin/" + branch + " not found.", NamedTextColor.RED);
                return;
            }

            send(sender, "--- Commits ahead of the remote (origin/" + branch + ") ---", NamedTextColor.GOLD);

            boolean hasCommits = false;
            for (RevCommit commit : git.log().addRange(remoteHead, localHead).call()) {
                hasCommits = true;
                sender.sendMessage(
                    Component.text(commit.getName().substring(0, 7) + " ").color(NamedTextColor.YELLOW)
                        .append(Component.text("(" + commit.getAuthorIdent().getName() + ") ").color(NamedTextColor.GRAY))
                        .append(Component.text(commit.getShortMessage()).color(NamedTextColor.WHITE))
                );
            }

            if (!hasCommits) {
                send(sender, "No commits ahead of the remote repository.", NamedTextColor.GREEN);
            }
        });
    }

    @Subcommand("git commitandpush")
    @Description("Commit the current changes and push them (git commit -m <message> & git push)")
    @Syntax("<message>")
    public void commitAndPush(CommandSender sender, String message) {
        send(sender, "Creating a commit...", NamedTextColor.GREEN);

        runAsync(sender, "committing and pushing", git -> {
            if (!hasGitignore(sender, git)) {
                return;
            }

            git.add()
                .addFilepattern(".")
                .call();

            if (!git.status().call().isClean()) {
                git.commit()
                    .setMessage(message)
                    // Whoever ran the command owns the commit, the repository identity stays the committer
                    .setAuthor(sender.getName(), sender.getName().toLowerCase() + "@minecraft.server.null")
                    .call();
                send(sender, "Commit created! Pushing...", NamedTextColor.GREEN);
            } else {
                send(sender, "Nothing to commit, pushing...", NamedTextColor.YELLOW);
            }

            Iterable<PushResult> results = git.push()
                .setRemote("origin")
                .setCredentialsProvider(GitSyncPlugin.instance().gitSyncService().credentials())
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
                    send(sender, "Push rejected for " + update.getRemoteName() + ": " + reason, NamedTextColor.RED);
                }
            }

            if (rejected) {
                send(sender, "Push failed. The remote most likely has commits you do not have, run /gitsync sync first.", NamedTextColor.RED);
                return;
            }

            send(sender, "Successfully pushed to the repository!", NamedTextColor.GREEN);
        });
    }

    /**
     * The .gitignore is the only thing separating the pack from every other plugin in the folder.
     * Without it "add ." tracks the whole server and a later reset deletes it again, and clean
     * takes everything untracked. No command that stages or destroys files may run without it.
     */
    private boolean hasGitignore(CommandSender sender, Git git) {
        if (new File(git.getRepository().getWorkTree(), ".gitignore").isFile()) {
            return true;
        }

        send(sender, "Refusing to run: .gitignore is missing from the plugins directory.", NamedTextColor.RED);
        send(sender, "Without it git would treat every plugin on this server as part of the pack. "
            + "Run /gitsync sync to regenerate it first.", NamedTextColor.RED);
        return false;
    }

    /** Run a git action off the main thread on the shared repository handle. */
    private void runAsync(CommandSender sender, String what, GitAction action) {
        GitSyncPlugin plugin = GitSyncPlugin.instance();
        Git git = plugin.gitSyncService().git();
        if (git == null) {
            send(sender, "GitSync is not initialized, check the console.", NamedTextColor.RED);
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                action.run(git);
            } catch (Exception exception) {
                send(sender, "An error occurred! See the console for details.", NamedTextColor.RED);
                plugin.getLogger().log(Level.SEVERE, "An error occurred while " + what + "!", exception);
            }
        });
    }

    private void listFiles(CommandSender sender, Set<String> files, String prefix, NamedTextColor color) {
        for (String file : files) {
            sender.sendMessage(Component.text(prefix).color(color).append(Component.text(file).color(NamedTextColor.WHITE)));
        }
    }

    private void send(CommandSender sender, String message, NamedTextColor color) {
        sender.sendMessage(Component.text(message).color(color));
    }

    @FunctionalInterface
    private interface GitAction {
        void run(Git git) throws Exception;
    }
}
