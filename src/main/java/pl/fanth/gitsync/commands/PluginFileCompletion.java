package pl.fanth.gitsync.commands;

import pl.fanth.gitsync.GitSyncPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Completes a config path against what actually sits under plugins/, so it can be tabbed together
 * instead of spelled out. The root offers the data folders alone - a jar there is claimed by the
 * pack wildcard, never by a config path, and our own directory holds the pack itself - while
 * inside one of them everything is fair game.
 */
public final class PluginFileCompletion {
    /** A data folder can hold hundreds of files, the tab list is not the place to see them all. */
    private static final int LIMIT = 100;

    private PluginFileCompletion() {
    }

    public static List<String> complete(String input) {
        File dataFolder = GitSyncPlugin.instance().getDataFolder();
        Path pluginsDir = dataFolder.getParentFile().toPath();
        String parent = input.substring(0, input.lastIndexOf('/') + 1);

        // Whatever was typed, the listing never leaves plugins/
        Path directory = pluginsDir.resolve(parent).normalize();
        if (!directory.startsWith(pluginsDir) || !Files.isDirectory(directory)) {
            return List.of();
        }

        List<String> matches;
        try (Stream<Path> files = Files.list(directory)) {
            matches = files
                .filter(file -> !parent.isEmpty()
                    || (Files.isDirectory(file) && !file.getFileName().toString().equals(dataFolder.getName())))
                .map(file -> parent + file.getFileName() + (Files.isDirectory(file) ? "/" : ""))
                .filter(path -> path.regionMatches(true, 0, input, 0, input.length()))
                .limit(LIMIT)
                .toList();
        } catch (IOException exception) {
            return List.of();
        }

        // The client does not ask again after a suggestion is tabbed in, so a lone folder brings its
        // contents along - the next tab then walks into it instead of stopping at the slash.
        if (matches.size() == 1 && matches.get(0).endsWith("/")) {
            List<String> expanded = new ArrayList<>(matches);
            expanded.addAll(complete(matches.get(0)));
            return expanded;
        }
        return matches;
    }
}
