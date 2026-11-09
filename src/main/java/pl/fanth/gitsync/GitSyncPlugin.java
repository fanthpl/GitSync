package pl.fanth.gitsync;

import co.aikar.commands.PaperCommandManager;
import pl.fanth.gitsync.commands.GitSyncCommand;
import pl.fanth.gitsync.commands.PluginFileCompletion;
import pl.fanth.gitsync.config.ConfigurationFactory;
import pl.fanth.gitsync.config.DataConfiguration;
import pl.fanth.gitsync.config.PluginConfiguration;
import pl.fanth.gitsync.config.ServerConfiguration;
import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.java.JavaPlugin;
import pl.fanth.gitsync.git.GitSyncService;
import pl.fanth.gitsync.listeners.PlayerListener;

import com.google.gson.JsonParser;

import java.io.File;
import java.lang.module.ModuleDescriptor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public final class GitSyncPlugin extends JavaPlugin {
    private PluginConfiguration pluginConfiguration;
    private ServerConfiguration serverConfiguration;
    private DataConfiguration dataConfiguration;
    private GitSyncService gitSyncService;

    /** Newer release tag from GitHub, null when up to date or not checked yet. */
    private volatile String newerVersion;

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

        // Metrics
        new Metrics(this, 35233);

        // Every 24h (in ticks), starting right away
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::checkForUpdate, 0L, 20L * 60 * 60 * 24);
    }

    private void checkForUpdate() {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/fanthpl/GitSync/releases/latest")).build();
            String body = client.send(request, HttpResponse.BodyHandlers.ofString()).body();
            String latest = JsonParser.parseString(body).getAsJsonObject().get("tag_name").getAsString().replaceFirst("^v", "");
            String current = getPluginMeta().getVersion();
            if (ModuleDescriptor.Version.parse(latest).compareTo(ModuleDescriptor.Version.parse(current)) > 0) {
                this.newerVersion = latest;
                getLogger().warning("A newer version of GitSync is available: " + latest + " (current: " + current + ")");
            }
        } catch (Exception e) {
            getLogger().fine("Update check failed: " + e);
        }
    }

    public String newerVersion() {
        return newerVersion;
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

        manager.getCommandCompletions().registerAsyncCompletion("packplugins",
                context -> this.gitSyncService.manifest().plugins.keySet());

        manager.getCommandCompletions().registerAsyncCompletion("branches",
                context -> this.gitSyncService.branches());

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

    /** Our own jar, the one a pack update of GitSync is compared with. */
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
