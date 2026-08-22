package pl.fanth.gitsync.git;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void matchesOnlyWhatThePackDeclares() {
        PackManifest manifest = PackManifest.parse(PACK);

        assertTrue(manifest.matches("ItemsAdder/config.yml"));
        assertTrue(manifest.matches("ItemsAdder/storage/items/sword.yml"));
        assertTrue(manifest.matches("ItemsAdder-4.0.jar"));
        assertFalse(manifest.matches("SomeOther/config.yml"));
    }

    @Test
    void restartIsRequiredForJarsAndForConfigsNothingCanReload() {
        PackManifest manifest = PackManifest.parse("""
                {
                  "plugins": {
                    "ItemsAdder": {
                      "pluginJarWildcard": "ItemsAdder-*.jar",
                      "configPaths": ["ItemsAdder/config.yml"],
                      "reloadCommands": ["iareload"]
                    },
                    "Vault": {
                      "pluginJarWildcard": "Vault-*.jar",
                      "configPaths": ["Vault/config.yml"]
                    }
                  }
                }
                """);

        // A reload command covers it, no restart needed
        assertEquals(Map.of(), manifest.restartRequiredPaths(Set.of("ItemsAdder/config.yml")));
        // Nothing outside the pack forces a restart either
        assertEquals(Map.of(), manifest.restartRequiredPaths(Set.of("pack.json")));

        assertEquals(Map.of("ItemsAdder-4.0.jar", "plugin jar changed"),
                manifest.restartRequiredPaths(Set.of("ItemsAdder-4.0.jar")));
        assertEquals(Map.of("Vault/config.yml", "no reload commands declared"),
                manifest.restartRequiredPaths(Set.of("Vault/config.yml")));
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
