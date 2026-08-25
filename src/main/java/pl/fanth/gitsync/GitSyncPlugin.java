package pl.fanth.gitsync;

import co.aikar.commands.PaperCommandManager;
import pl.fanth.gitsync.commands.GitSyncCommand;
import pl.fanth.gitsync.commands.PluginFileCompletion;
import pl.fanth.gitsync.config.ConfigurationFactory;
import pl.fanth.gitsync.config.DataConfiguration;
import pl.fanth.gitsync.config.PluginConfiguration;
import pl.fanth.gitsync.config.ServerConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import pl.fanth.gitsync.git.GitSyncService;
import pl.fanth.gitsync.listeners.PlayerListener;

import java.io.File;

public final class GitSyncPlugin extends JavaPlugin {
    private PluginConfiguration pluginConfiguration;
    private ServerConfiguration serverConfiguration;
    private DataConfiguration dataConfiguration;
    private GitSyncService gitSyncService;

    private static GitSyncPlugin instance;

    @Override
    public void onEnable() {
        instance = this;

        this.pluginConfiguration = ConfigurationFactory.createConfiguration(PluginConfiguration.class, new File(this.getDataFolder(), "config.yml"), this.getLogger());
        this.serverConfiguration = ConfigurationFactory.createConfiguration(ServerConfiguration.class, new File(this.getDataFolder(), "server.yml"), this.getLogger());
        this.dataConfiguration = ConfigurationFactory.createConfiguration(DataConfiguration.class, new File(this.getDataFolder(), "data.yml"), this.getLogger());

        this.gitSyncService = new GitSyncService(this);
        this.gitSyncService.start();

        registerCommands();
        getServer().getPluginManager().registerEvents(new PlayerListener(), this);
    }

    @Override
    public void onDisable() {
        if (this.gitSyncService != null) {
            this.gitSyncService.stop();
        }
    }

    private void registerCommands() {
        PaperCommandManager manager = new PaperCommandManager(this);

        manager.enableUnstableAPI("help");
        // Async, the listing walks the disk
        manager.getCommandCompletions().registerAsyncCompletion("pluginfiles",
                context -> PluginFileCompletion.complete(context.getInput()));

        manager.registerCommand(new GitSyncCommand());
    }

    public static GitSyncPlugin instance() {
        return instance;
    }

    public PluginConfiguration pluginConfiguration() {
        return pluginConfiguration;
    }

    public ServerConfiguration serverConfiguration() {
        return serverConfiguration;
    }

    public DataConfiguration dataConfiguration() {
        return dataConfiguration;
    }

    public GitSyncService gitSyncService() {
        return gitSyncService;
    }

    /** Our own jar, so a commit does not offer to add GitSync itself to the pack. */
    @Override
    public File getFile() {
        return super.getFile();
    }

    public void reloadConfiguration() {
        this.pluginConfiguration.load();
        this.serverConfiguration.load();
        this.dataConfiguration.load();
    }
}
