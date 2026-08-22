package pl.fanth.gitsync;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import org.jetbrains.annotations.NotNull;
import pl.fanth.gitsync.config.ConfigurationFactory;
import pl.fanth.gitsync.config.PluginConfiguration;
import pl.fanth.gitsync.config.ServerConfiguration;
import pl.fanth.gitsync.git.GitSyncService;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.logging.Logger;

/**
 * The bootstrap phase runs before any plugin is loaded, which is the only point where synced
 * configs are guaranteed to be on disk before their owners read them. Bukkit does not exist yet.
 */
public class GitSyncBootstrap implements PluginBootstrap {
    private static final Logger LOGGER = Logger.getLogger("GitSync");

    @Override
    public void bootstrap(@NotNull BootstrapContext context) {
        Path dataDirectory = context.getDataDirectory();
        try {
            Files.createDirectories(dataDirectory);
        } catch (Exception exception) {
            LOGGER.severe("Could not create the data directory: " + exception.getMessage());
            return;
        }

        PluginConfiguration config = ConfigurationFactory.createConfiguration(
                PluginConfiguration.class, new File(dataDirectory.toFile(), "config.yml"), LOGGER);

        if (!config.syncOnStartup) {
            return;
        }

        ServerConfiguration server = ConfigurationFactory.createConfiguration(
                ServerConfiguration.class, new File(dataDirectory.toFile(), "server.yml"), LOGGER);

        GitSyncService service = new GitSyncService(dataDirectory, LOGGER, () -> config, () -> server);
        try {
            if (!service.open()) {
                return;
            }
            Set<String> changed = service.sync(null, false);
            if (changed.stream().anyMatch(path -> path.endsWith(".jar"))) {
                shutdown(changed);
            }
        } finally {
            service.stop();
        }
    }

    /**
     * The server already scanned plugins/ before this phase, so freshly pulled jars cannot be
     * picked up during this boot. Stop and let the wrapper start us again with them in place.
     */
    private void shutdown(Set<String> changed) {
        String jars = changed.stream().filter(path -> path.endsWith(".jar")).reduce((a, b) -> a + ", " + b).orElse("");
        LOGGER.severe("=".repeat(70));
        LOGGER.severe("GitSync pulled new plugin jars: " + jars);
        LOGGER.severe("They cannot be loaded during this boot, stopping the server so it");
        LOGGER.severe("gets restarted with them. If nothing restarts it, start it manually.");
        LOGGER.severe("=".repeat(70));
        System.exit(1);
    }
}
