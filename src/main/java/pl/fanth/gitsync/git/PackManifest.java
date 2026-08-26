package pl.fanth.gitsync.git;

import com.google.gson.Gson;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Model of pack.json living in the root of the synced repository.
 */
public class PackManifest {
    private static final Gson GSON = new Gson();

    public Map<String, Entry> plugins = new LinkedHashMap<>();

    public static class Entry {
        public String pluginJarWildcard;
        public List<String> configPaths = new ArrayList<>();
        public List<String> reloadCommands = new ArrayList<>();

        /** Does a repository-relative path belong to this plugin? */
        public boolean matches(String path) {
            return matchesConfig(path) || matchesJar(path);
        }

        public boolean matchesConfig(String path) {
            if (this.configPaths == null) {
                return false;
            }
            for (String configPath : this.configPaths) {
                String clean = normalize(configPath);
                if (!clean.isEmpty() && (path.equals(clean) || path.startsWith(clean + "/"))) {
                    return true;
                }
            }
            return false;
        }

        public boolean matchesJar(String path) {
            if (this.pluginJarWildcard == null || this.pluginJarWildcard.isBlank()) {
                return false;
            }
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + normalize(this.pluginJarWildcard));
            return matcher.matches(java.nio.file.Path.of(path));
        }

        public boolean hasReloadCommands() {
            return this.reloadCommands != null && !this.reloadCommands.isEmpty();
        }
    }

    public static PackManifest parse(String json) {
        PackManifest manifest = GSON.fromJson(json, PackManifest.class);
        if (manifest == null) {
            manifest = new PackManifest();
        }
        if (manifest.plugins == null) {
            manifest.plugins = new LinkedHashMap<>();
        }
        return manifest;
    }

    /** Does any plugin in the pack claim this path? */
    public boolean matches(String path) {
        return this.plugins.values().stream().anyMatch(entry -> entry.matches(path));
    }

    /** Which plugin in the pack claims this path, null when none of them does. */
    public String ownerOf(String path) {
        for (Map.Entry<String, Entry> plugin : this.plugins.entrySet()) {
            if (plugin.getValue().matches(path)) {
                return plugin.getKey();
            }
        }
        return null;
    }

    /** Returns the list of plugin entries that are affected by the given changed paths. */
    public List<Entry> getEntriesFor(Collection<String> changedPaths) {
        List<Entry> entries = new ArrayList<>();
        for (Entry entry : this.plugins.values()) {
            if (entry.configPaths == null || entry.configPaths.isEmpty()) {
                continue;
            }
            for (String path : changedPaths) {
                if (entry.matches(path)) {
                    entries.add(entry);
                    break;
                }
            }
        }
        return entries;
    }

    /** Reload commands of every plugin touched by the given changed paths, in pack.json order. */
    public List<String> reloadCommandsFor(Collection<String> changedPaths) {
        return commandsOf(getEntriesFor(changedPaths));
    }

    /**
     * Changed paths that no reload command can apply, so the server has to be restarted:
     * a plugin jar was added, removed or updated, or a config of a plugin that declares
     * no reload commands changed.
     *
     * @return the offending paths mapped to the reason, empty when a reload is enough
     */
    public Map<String, String> restartRequiredPaths(Collection<String> changedPaths) {
        Map<String, String> reasons = new LinkedHashMap<>();
        for (String path : changedPaths) {
            for (Entry entry : this.plugins.values()) {
                if (entry.matchesJar(path)) {
                    reasons.put(path, "plugin jar changed");
                    break;
                }
                if (entry.matchesConfig(path) && !entry.hasReloadCommands()) {
                    reasons.put(path, "no reload commands declared");
                    break;
                }
            }
        }
        return reasons;
    }

    private static List<String> commandsOf(Collection<Entry> entries) {
        return entries.stream()
            // The key is optional in pack.json, gson leaves the field null when it is missing
            .flatMap(entry -> entry.reloadCommands == null ? Stream.<String>empty() : entry.reloadCommands.stream())
            .distinct()
            .toList();
    }

    public static String normalize(String path) {
        return path.replace('\\', '/').replaceAll("^/+", "").replaceAll("/+$", "");
    }
}
