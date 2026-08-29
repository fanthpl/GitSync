package pl.fanth.gitsync;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import org.jetbrains.annotations.NotNull;
import pl.fanth.gitsync.config.ConfigurationFactory;
import pl.fanth.gitsync.config.DataConfiguration;
import pl.fanth.gitsync.config.PluginConfiguration;
import pl.fanth.gitsync.config.ServerConfiguration;
import pl.fanth.gitsync.git.GitSyncService;
import pl.fanth.gitsync.git.PackRenderer;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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

        DataConfiguration data = ConfigurationFactory.createConfiguration(
                DataConfiguration.class, new File(dataDirectory.toFile(), "data.yml"), LOGGER);

        // Spent whether the sync below succeeds or not, so a broken remote cannot leave the
        // server forcing on every boot from here on
        boolean force = data.forceNextStartupSync;
        if (force) {
            LOGGER.warning("data.yml asked for one forced startup sync, the edits made on this server are dropped.");
            data.forceNextStartupSync = false;
            data.save();
        }

        GitSyncService service = new GitSyncService(dataDirectory, LOGGER, () -> config, () -> server);
        try {
            if (!service.open()) {
                return;
            }
            Set<String> changed = service.sync(null, force);
            if (!service.unresolvedVariables().isEmpty()) {
                missingVariables(service.unresolvedVariables());
            }
            if (changed.stream().anyMatch(path -> path.endsWith(".jar"))) {
                shutdown(changed);
            }
        } finally {
            service.stop();
        }
    }

    /**
     * The pack asks for values this server never declared, so the rendered configs hold a raw
     * ${GITSYNC_NAME} their plugins will not understand. Booting on that quietly breaks whatever
     * the variable was holding - a database password, a server name - so it stops here instead.
     */
    private void missingVariables(Map<String, Set<String>> unresolved) {
        LOGGER.severe("=".repeat(70));
        LOGGER.severe("GitSync cannot render the pack, server.yml is missing " + unresolved.size() + " variable(s):");
        unresolved.forEach((name, files) -> LOGGER.severe("  " + PackRenderer.VARIABLE_PREFIX + name
                + " - used by " + String.join(", ", files)));
        LOGGER.severe("Add them under variables: in " + "plugins/GitSync/server.yml"
                + " (without the " + PackRenderer.VARIABLE_PREFIX + " prefix) and start the server again.");
        LOGGER.severe("=".repeat(70));
        System.exit(1);
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
