package pl.fanth.gitsync.git;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackManifestTest {
    private static final String PACK = """
            {
              "plugins": {
                "ItemsAdder": {
                  "pluginJarWildcard": "ItemsAdder-*.jar",
                  "configPaths": ["ItemsAdder/storage", "ItemsAdder/config.yml"],
                  "reloadCommands": ["iareload"]
                },
                "EssentialsX": {
                  "pluginJarWildcard": "EssentialsX-*.jar",
                  "configPaths": ["EssentialsX/config.yml"],
                  "reloadCommands": ["ess reload"]
                }
              }
            }
            """;

    @Test
    void gitignoreIgnoresEverythingButDeclaredPaths() {
        List<String> lines = PackManifest.parse(PACK).gitignoreLines();

        assertTrue(lines.contains("/**"));
        // A parent directory has to be re-included or git never descends into it
        assertTrue(lines.contains("!/ItemsAdder/"));
        assertTrue(lines.contains("!/ItemsAdder/storage"));
        assertTrue(lines.contains("!/ItemsAdder/storage/**"));
        assertTrue(lines.contains("!/ItemsAdder/config.yml"));
        assertTrue(lines.contains("!/ItemsAdder-*.jar"));
        assertTrue(lines.indexOf("/**") < lines.indexOf("!/ItemsAdder/"), "negations must come after the catch-all");
    }

    @Test
    void reloadCommandsFollowChangedPaths() {
        PackManifest manifest = PackManifest.parse(PACK);

        assertEquals(List.of("iareload"), manifest.reloadCommandsFor(Set.of("ItemsAdder/storage/items/sword.yml")));
        assertEquals(List.of("iareload"), manifest.reloadCommandsFor(Set.of("ItemsAdder-4.0.jar")));
        assertEquals(List.of("ess reload"), manifest.reloadCommandsFor(Set.of("EssentialsX/config.yml")));
        assertEquals(List.of(), manifest.reloadCommandsFor(Set.of("SomeOther/config.yml")));
        // A prefix must not match a sibling directory
        assertEquals(List.of(), manifest.reloadCommandsFor(Set.of("ItemsAdder/storage-backup/x.yml")));
    }
}
