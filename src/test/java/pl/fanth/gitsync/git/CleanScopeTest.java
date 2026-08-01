package pl.fanth.gitsync.git;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /gitsync resethead runs clean + reset --hard, verify what that actually deletes
 * under a .gitignore that ignores everything outside pack.json.
 */
class CleanScopeTest {
    private static final String PACK = """
            {
              "plugins": {
                "ItemsAdder": {
                  "pluginJarWildcard": "ItemsAdder-*.jar",
                  "configPaths": ["ItemsAdder/config.yml"],
                  "reloadCommands": ["iareload"]
                }
              }
            }
            """;

    @Test
    void cleanKeepsFilesOutsideThePack(@TempDir Path dir) throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            Files.createDirectories(dir.resolve("ItemsAdder"));
            Files.writeString(dir.resolve("ItemsAdder/config.yml"), "tracked: true");
            Files.write(dir.resolve(".gitignore"), PackManifest.parse(PACK).gitignoreLines());

            git.add().addFilepattern("ItemsAdder/config.yml").call();
            git.commit().setMessage("init").setAuthor("t", "t@t").setCommitter("t", "t@t").setSign(false).call();

            // A plugin nobody declared in pack.json, plus a stray file in a declared directory
            Files.createDirectories(dir.resolve("OtherPlugin/data"));
            Files.writeString(dir.resolve("OtherPlugin/config.yml"), "keep: me");
            Files.writeString(dir.resolve("OtherPlugin/data/db.yml"), "keep: me");
            Files.writeString(dir.resolve("ItemsAdder/local-notes.txt"), "keep: me");
            // This one is inside the pack (matches the jar wildcard), so it is not ignored
            Files.writeString(dir.resolve("ItemsAdder-4.0.jar"), "not really a jar");

            git.clean().setForce(true).setCleanDirectories(true).call();

            assertTrue(Files.exists(dir.resolve("OtherPlugin/config.yml")), "ignored config of an undeclared plugin");
            assertTrue(Files.exists(dir.resolve("OtherPlugin/data/db.yml")), "ignored config in a nested directory");
            assertTrue(Files.exists(dir.resolve("ItemsAdder/local-notes.txt")), "ignored file inside a declared directory");
            assertTrue(Files.exists(dir.resolve("ItemsAdder/config.yml")), "tracked file");
            assertFalse(Files.exists(dir.resolve("ItemsAdder-4.0.jar")), "untracked file that the pack claims");
        }
    }
}
