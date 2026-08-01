package pl.fanth.gitsync;

import co.aikar.commands.PaperCommandManager;
import pl.fanth.gitsync.commands.GitSyncCommand;
import pl.fanth.gitsync.config.ConfigurationFactory;
import pl.fanth.gitsync.config.DataConfiguration;
import pl.fanth.gitsync.config.PluginConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import pl.fanth.gitsync.git.GitSyncService;

import java.io.File;

public final class GitSyncPlugin extends JavaPlugin {
    private PluginConfiguration pluginConfiguration;
    private DataConfiguration dataConfiguration;
    private GitSyncService gitSyncService;

    private static GitSyncPlugin instance;

    @Override
    public void onEnable() {
        instance = this;

        this.pluginConfiguration = ConfigurationFactory.createConfiguration(PluginConfiguration.class, new File(this.getDataFolder(), "config.yml"), this.getLogger());
        this.dataConfiguration = ConfigurationFactory.createConfiguration(DataConfiguration.class, new File(this.getDataFolder(), "data.yml"), this.getLogger());

        this.gitSyncService = new GitSyncService(this);
        this.gitSyncService.start();

        registerCommands();
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

        manager.registerCommand(new GitSyncCommand());
    }

    public static GitSyncPlugin instance() {
        return instance;
    }

    public PluginConfiguration pluginConfiguration() {
        return pluginConfiguration;
    }

    public DataConfiguration dataConfiguration() {
        return dataConfiguration;
    }

    public GitSyncService gitSyncService() {
        return gitSyncService;
    }

    public void reloadConfiguration() {
        this.pluginConfiguration.load();
        this.dataConfiguration.load();
    }
}
