package pl.fanth.gitsync.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.CommandHelp;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandCompletion;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Description;
import co.aikar.commands.annotation.HelpCommand;
import co.aikar.commands.annotation.Optional;
import co.aikar.commands.annotation.Private;
import co.aikar.commands.annotation.Subcommand;
import co.aikar.commands.annotation.Syntax;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import pl.fanth.gitsync.GitSyncPlugin;
import pl.fanth.gitsync.config.PluginConfiguration;
import pl.fanth.gitsync.config.ServerConfiguration;
import pl.fanth.gitsync.git.GitSyncService;
import pl.fanth.gitsync.git.PackManifest;
import pl.fanth.gitsync.git.PackRenderer;
import pl.fanth.gitsync.prompt.PushUpdatePrompt;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

@CommandAlias("gitsync")
@CommandPermission("gitsync.admin")
public class GitSyncCommand extends BaseCommand {
    /** Chat scrollback is short, a full config rewrite would push everything else out of it. */
    private static final int DIFF_LINE_LIMIT = 120;
    /** Tacked onto the commit message to publish variable lines that cannot be put back. */
    private static final String CONFIRM_FLAG = " --confirm";

    public GitSyncCommand() {
        // A session read back from disk has no callback of its own, so the way to commit is handed
        // over once, here, rather than captured when the prompt is started
        PushUpdatePrompt.publisher(this::doPublish);
    }

    @HelpCommand
    public void doHelp(CommandSender sender, CommandHelp help) {
        help.showHelp();
    }

    @Subcommand("sync")
    @Description("Sync the pack with the remote repository. --force overwrites the edits made on this server")
    @Syntax("[--force]")
    @CommandCompletion("--force")
    public void sync(CommandSender sender, @Optional String option) {
        // Spelled out rather than taken as a boolean: ACF only reads true/yes/on/1 as true, so a
        // mistyped flag would silently turn into a plain sync
        boolean force = option != null && (option.equalsIgnoreCase("--force") || option.equalsIgnoreCase("force"));
        if (option != null && !force) {
            send(sender, "Unknown option " + option + ", did you mean --force?", NamedTextColor.RED);
            return;
        }

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
    @Description("Throw away the local edits and render the pack over them again")
    public void resetHead(CommandSender sender) {
        send(sender, "Restoring the pack...", NamedTextColor.GREEN);

        runAsync(sender, "restoring the pack", git ->
                GitSyncPlugin.instance().gitSyncService().resetRender(sender));
    }

    @Subcommand("status")
    @Description("Show the pack state and whether the server needs a restart")
    public void status(CommandSender sender) {
        GitSyncService service = GitSyncPlugin.instance().gitSyncService();
        PluginConfiguration config = GitSyncPlugin.instance().pluginConfiguration();
        ServerConfiguration server = GitSyncPlugin.instance().serverConfiguration();

        runAsync(sender, "reading the pack state", git -> {
            Repository repo = git.getRepository();
            PackManifest manifest = service.manifest();

            send(sender, "--- GitSync ---", NamedTextColor.GOLD);
            field(sender, "Remote", config.remote.isBlank() ? "<not configured>" : config.remote);
            field(sender, "Branch", repo.getBranch());
            field(sender, "Commit", describeHead(repo));
            field(sender, "Layers", String.join(" -> ", service.renderer().layers()));
            field(sender, "Pack", manifest.plugins.size() + " plugin(s): " + String.join(", ", manifest.plugins.keySet()));
            field(sender, "Auto sync", "every " + config.checkIntervalSeconds + "s" + (service.isSyncing() ? " (running now)" : ""));

            if (server.role.isBlank()) {
                send(sender, "No role set in server.yml, only base/ is rendered.", NamedTextColor.YELLOW);
            }

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
    @Description("Show which synced files were edited on this server")
    public void gitStatus(CommandSender sender) {
        send(sender, "Checking for local edits...", NamedTextColor.GREEN);

        runAsync(sender, "checking for local edits", git -> {
            GitSyncService service = GitSyncPlugin.instance().gitSyncService();
            List<PackRenderer.LocalChange> changes = service.localChanges();
            List<String> unknown = service.unknownJars();

            if (changes.isEmpty() && unknown.isEmpty()) {
                send(sender, "No local edits, this server matches the pack.", NamedTextColor.GREEN);
                return;
            }

            send(sender, "--- Local edits ---", NamedTextColor.GOLD);
            for (PackRenderer.LocalChange change : changes) {
                sender.sendMessage(Component.text(prefixOf(change.kind())).color(colorOf(change.kind()))
                    .append(Component.text(change.logicalPath()).color(NamedTextColor.WHITE))
                    .append(Component.text(" -> " + change.targetLayer()).color(NamedTextColor.GRAY)));
            }
            for (String jar : unknown) {
                sender.sendMessage(Component.text("[?] ").color(NamedTextColor.GRAY)
                    .append(Component.text(jar).color(NamedTextColor.WHITE))
                    .append(Component.text(" (not in the pack)").color(NamedTextColor.GRAY)));
            }
        });
    }

    private String prefixOf(PackRenderer.Kind kind) {
        return switch (kind) {
            case NEW -> "[A] ";
            case MODIFIED -> "[M] ";
            case DELETED -> "[D] ";
        };
    }

    private NamedTextColor colorOf(PackRenderer.Kind kind) {
        return switch (kind) {
            case NEW -> NamedTextColor.GREEN;
            case MODIFIED -> NamedTextColor.YELLOW;
            case DELETED -> NamedTextColor.RED;
        };
    }

    @Subcommand("git diff")
    @Description("Show what differs between the pack and the files on this server")
    @Syntax("[path]")
    public void gitDiff(CommandSender sender, @Optional String path) {
        send(sender, "Building the diff...", NamedTextColor.GREEN);

        runAsync(sender, "building the diff", git -> {
            GitSyncService service = GitSyncPlugin.instance().gitSyncService();
            PackRenderer renderer = service.renderer();
            Map<String, String> plan = renderer.plan(service.manifest());

            List<PackRenderer.LocalChange> changes = new ArrayList<>(service.localChanges());
            if (path != null) {
                String prefix = path.endsWith("/") ? path : path + "/";
                changes.removeIf(change -> !change.logicalPath().equals(path) && !change.logicalPath().startsWith(prefix));
            }

            if (changes.isEmpty()) {
                send(sender, path == null ? "No local edits." : "No local edits under " + path + ".", NamedTextColor.GREEN);
                return;
            }

            send(sender, "--- Diff against the pack ---", NamedTextColor.GOLD);

            int budget = DIFF_LINE_LIMIT;
            int truncated = 0;
            for (PackRenderer.LocalChange change : changes) {
                byte[] packed = renderer.renderedBytes(plan, change.logicalPath());
                Path file = renderer.pluginsFile(change.logicalPath());
                byte[] local = Files.isRegularFile(file) ? Files.readAllBytes(file) : new byte[0];
                if (packed == null) {
                    packed = new byte[0];
                }

                send(sender, change.kind() + " " + change.logicalPath(), NamedTextColor.AQUA);
                if (RawText.isBinary(packed) || RawText.isBinary(local)) {
                    send(sender, "  (binary)", NamedTextColor.GRAY);
                    continue;
                }

                for (String line : hunksOf(packed, local)) {
                    if (budget-- <= 0) {
                        truncated++;
                        continue;
                    }
                    sender.sendMessage(Component.text(shorten(line)).color(diffColor(line)));
                }
            }

            if (truncated > 0) {
                send(sender, "... " + truncated + " more line(s), narrow it down with /gitsync git diff <path>", NamedTextColor.GRAY);
            }
        });
    }

    /** The hunks between what the pack holds and what sits on disk, headers left out. */
    private List<String> hunksOf(byte[] packed, byte[] local) throws Exception {
        RawText oldText = new RawText(packed);
        RawText newText = new RawText(local);
        // Kills the line ending churn: a file that only swapped CRLF for LF produces no hunk
        EditList edits = new HistogramDiff().diff(RawTextComparator.WS_IGNORE_TRAILING, oldText, newText);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); DiffFormatter formatter = new DiffFormatter(out)) {
            formatter.setContext(2);
            formatter.format(edits, oldText, newText);
            formatter.flush();

            List<String> lines = new ArrayList<>();
            for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
                lines.add(line.stripTrailing());
            }
            return lines;
        }
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

    @Subcommand("pushupdate")
    @Description("Publish the edits made on this server back to the pack")
    @Syntax("<message> [--confirm]")
    public void pushUpdate(CommandSender sender, String message) {
        String cleaned = message.trim();
        boolean confirmed = cleaned.toLowerCase().endsWith(CONFIRM_FLAG);
        if (confirmed) {
            cleaned = cleaned.substring(0, cleaned.length() - CONFIRM_FLAG.length()).trim();
        }

        if (cleaned.isEmpty()) {
            send(sender, "A commit message is required.", NamedTextColor.RED);
            return;
        }
        publish(sender, cleaned, confirmed);
    }

    // The three below are only ever reached by clicking a button the prompt prints, which puts the
    // command in the chat box for the value to be typed after it. Split so the config path is the
    // only one completed against the files on disk.

    @Subcommand("prompt wildcard")
    @Private
    @Syntax("<wildcard>")
    public void promptWildcard(Player player, String value) {
        PushUpdatePrompt.accept(player, "wildcard", value);
    }

    @Subcommand("prompt config")
    @Private
    @Syntax("<path>")
    @CommandCompletion("@pluginfiles")
    public void promptConfig(Player player, String value) {
        PushUpdatePrompt.accept(player, "config", value);
    }

    @Subcommand("prompt reload")
    @Private
    @Syntax("<command>")
    public void promptReload(Player player, String value) {
        PushUpdatePrompt.accept(player, "reload", value);
    }

    @Subcommand("prompt show")
    @Description("Print the push being prepared again, for when the chat has run away")
    public void promptShow(Player player) {
        PushUpdatePrompt.show(player);
    }

    @Subcommand("prompt cancel")
    @Description("Drop the push being prepared, committing nothing")
    public void promptCancel(Player player) {
        PushUpdatePrompt.cancel(player);
    }

    /** Everything a push would touch is laid out in chat first, and only a confirm commits it. */
    private void publish(CommandSender sender, String message, boolean confirmed) {
        // Two sessions would answer for the same files and commit over each other
        String busy = PushUpdatePrompt.busyWith();
        if (busy != null) {
            send(sender, busy + " started a push that is still waiting to be answered, "
                + "pick it up with /gitsync prompt show or drop it with /gitsync prompt cancel.", NamedTextColor.RED);
            return;
        }

        runAsync(sender, "checking what would be published", git -> {
            GitSyncService service = GitSyncPlugin.instance().gitSyncService();
            List<PackRenderer.LocalChange> changes = service.localChanges();
            List<String> jars = service.unknownJars();

            if (changes.isEmpty() && jars.isEmpty()) {
                doPublish(sender, message, confirmed, List.of(), Map.of());
                return;
            }

            // The console has no chat to click in, so it commits the way it always did - except for
            // a jar nobody declared, which cannot be placed without an answer
            if (!(sender instanceof Player player)) {
                if (!jars.isEmpty()) {
                    send(sender, "Nothing was committed, " + jars.size() + " plugin(s) in plugins/ are not part of the pack: "
                        + String.join(", ", jars), NamedTextColor.YELLOW);
                    send(sender, "Run this command in game to place them, the buttons need a chat to click in.", NamedTextColor.YELLOW);
                    return;
                }
                doPublish(sender, message, confirmed, List.of(), Map.of());
                return;
            }
            new PushUpdatePrompt(player, changes, jars, message, confirmed).start();
        });
    }

    private void doPublish(CommandSender sender, String message, boolean confirmed,
                           List<GitSyncService.NewPlugin> newPlugins, Map<String, String> fileLayers) {
        send(sender, "Publishing local edits...", NamedTextColor.GREEN);

        runAsync(sender, "committing and pushing", git -> {
            GitSyncService service = GitSyncPlugin.instance().gitSyncService();
            if (!service.commitAndPush(sender, message, confirmed, newPlugins, fileLayers).isEmpty()) {
                offerToPublishAnyway(sender, message, newPlugins, fileLayers);
            }
        });
    }

    /** The service already named the lines it could not put back, this is the way past it. */
    private void offerToPublishAnyway(CommandSender sender, String message,
                                      List<GitSyncService.NewPlugin> newPlugins, Map<String, String> fileLayers) {
        if (!(sender instanceof Player)) {
            send(sender, "Add " + CONFIRM_FLAG.trim() + " to the end of the message to publish them anyway.", NamedTextColor.YELLOW);
            return;
        }

        // Carries the answers along, so saying yes here does not ask about every jar again
        sender.sendMessage(Component.text("[publish anyway]").color(NamedTextColor.RED)
                .hoverEvent(HoverEvent.showText(Component.text("Send this server's own values to every server rendering these files")))
                .clickEvent(ClickEvent.callback(audience -> doPublish(sender, message, true, newPlugins, fileLayers))));
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

    private void send(CommandSender sender, String message, NamedTextColor color) {
        sender.sendMessage(Component.text(message).color(color));
    }

    @FunctionalInterface
    private interface GitAction {
        void run(Git git) throws Exception;
    }
}
