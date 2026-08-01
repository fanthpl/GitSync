package pl.fanth.gitsync.config;

import eu.okaeri.configs.OkaeriConfig;
import eu.okaeri.configs.ConfigManager;
import eu.okaeri.configs.serdes.commons.SerdesCommons;
import eu.okaeri.configs.validator.okaeri.OkaeriValidator;
import eu.okaeri.configs.yaml.bukkit.YamlBukkitConfigurer;

import java.io.File;
import java.util.logging.Logger;

public class ConfigurationFactory {

    private ConfigurationFactory(){
    }

    public static <T extends OkaeriConfig> T createConfiguration(Class<T> clazz, File configurationFile, Logger logger) {
        return ConfigManager.create(clazz, it -> {
            it.configure(opt -> {
                // No SerdesBukkit here, it does Class.forName("org.bukkit.Tag") whose static
                // initializer calls Bukkit.getTag() and blows up during the bootstrap phase.
                opt.configurer(new YamlBukkitConfigurer(), new SerdesCommons());
                opt.validator(new OkaeriValidator());
                opt.bindFile(configurationFile); // specify Path, File or pathname
                opt.removeOrphans(true); // automatic removal of undeclared keys
                opt.resolvePlaceholders(); // resolve ${VAR} and ${VAR:default} from environment
                opt.logger(logger);
            });
            it.saveDefaults();
            it.load(true);
        });
    }
}
