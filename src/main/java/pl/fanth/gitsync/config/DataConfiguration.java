package pl.fanth.gitsync.config;

import eu.okaeri.configs.OkaeriConfig;
import eu.okaeri.configs.annotation.Comment;
import eu.okaeri.configs.annotation.Header;

import java.util.ArrayList;
import java.util.List;

@Header("The data configuration stores persistent data for the plugin.")
@Header("DO NOT MODIFY IT UNLESS YOU KNOW WHAT YOU ARE DOING!")
@Header("")
public class DataConfiguration extends OkaeriConfig {
    @Comment("Jars this server keeps to itself. Filled in by answering \"ignore\" when a commit")
    @Comment("finds a plugin that is not in the pack, so the same jar is never asked about twice.")
    public List<String> ignoredPluginWildcards = new ArrayList<>();

    @Comment("")
    @Comment("Set to true to make the next startup sync run with --force, throwing away the edits")
    @Comment("made on this server. Flipped back to false as soon as that sync has run, so it")
    @Comment("only ever applies once.")
    public boolean forceNextStartupSync = false;
}
