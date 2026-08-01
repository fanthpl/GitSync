package pl.fanth.gitsync.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.CommandHelp;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Default;
import co.aikar.commands.annotation.Description;
import co.aikar.commands.annotation.HelpCommand;
import co.aikar.commands.annotation.Subcommand;
import co.aikar.commands.annotation.Syntax;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import pl.fanth.gitsync.GitSyncPlugin;
import pl.fanth.gitsync.git.GitSyncService;

import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;

@CommandAlias("gitsync")
@CommandPermission("gitsync.admin")
public class GitSyncCommand extends BaseCommand {
    @HelpCommand
    public void doHelp(CommandSender sender, CommandHelp help) {
        help.showHelp();
    }

    @Subcommand("sync")
    @Description("Sync the pack with the remote repository")
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

    @Subcommand("resethead")
    @Description("Reset the repository to HEAD (git reset --hard HEAD)")
    public void resetHead(CommandSender sender) {
        send(sender, "Resetting the repository to HEAD...", NamedTextColor.GREEN);

        runAsync(sender, "resetting the repository", git -> {
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
    @Description("Show the pack status (git status)")
    public void status(CommandSender sender) {
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

    @Subcommand("showahead")
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

    @Subcommand("commitandpush")
    @Description("Commit the current changes and push them (git commit -m <message> & git push)")
    @Syntax("<message>")
    public void commitAndPush(CommandSender sender, String message) {
        send(sender, "Creating a commit...", NamedTextColor.GREEN);

        runAsync(sender, "committing and pushing", git -> {
            git.add()
                .addFilepattern(".")
                .call();

            if (!git.status().call().isClean()) {
                git.commit()
                    .setMessage(message)
                    .call();
                send(sender, "Commit created! Pushing...", NamedTextColor.GREEN);
            } else {
                send(sender, "Nothing to commit, pushing...", NamedTextColor.YELLOW);
            }

            git.push()
                .setRemote("origin")
                .setCredentialsProvider(GitSyncPlugin.instance().gitSyncService().credentials())
                .call();

            send(sender, "Successfully pushed to the repository!", NamedTextColor.GREEN);
        });
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
