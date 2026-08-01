package pl.fanth.gitsync.config;

import eu.okaeri.configs.OkaeriConfig;
import eu.okaeri.configs.annotation.Comment;
import eu.okaeri.configs.annotation.Header;

@Header("A config file for the plugin.")
@Header("")
public class PluginConfiguration extends OkaeriConfig {
    @Comment("Remote repository holding pack.json, plugin jars and configs. Leave empty to disable syncing.")
    public String remote = "";

    @Comment("Branch to track")
    public String branch = "main";

    @Comment("Credentials for private repositories. On GitHub use a personal access token as the password.")
    public String username = "";
    public String password = "";

    @Comment("How often to check the remote for new commits (seconds)")
    public int checkIntervalSeconds = 300;

    @Comment("Sync during the bootstrap phase, before any plugin loads, so synced configs are read by their owners.")
    @Comment("New plugin jars cannot be loaded during a boot that already started, so the server stops to be restarted with them.")
    public boolean syncOnStartup = true;
}
