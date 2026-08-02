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

    /**
     * A hard reset does not touch untracked files, it deletes what the index tracks and the
     * target commit does not have. Without a .gitignore an "add ." makes every unrelated
     * plugin tracked, and only then does a later reset wipe them.
     */
    @Test
    void resetDeletesWhateverGotTrackedWithoutTheGitignore(@TempDir Path dir) throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            Files.writeString(dir.resolve("pack.json"), PACK);
            git.add().addFilepattern("pack.json").call();
            var base = git.commit().setMessage("base").setAuthor("t", "t@t").setCommitter("t", "t@t").setSign(false).call();

            Files.createDirectories(dir.resolve("OtherPlugin"));
            Files.writeString(dir.resolve("OtherPlugin/config.yml"), "keep: me");

            // Untracked, so the reset leaves it alone
            git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(base.name()).call();
            assertTrue(Files.exists(dir.resolve("OtherPlugin/config.yml")), "untracked files survive a hard reset");

            // No .gitignore, so "add ." tracks it - now the same reset deletes it
            git.add().addFilepattern(".").call();
            git.commit().setMessage("oops").setAuthor("t", "t@t").setCommitter("t", "t@t").setSign(false).call();
            git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(base.name()).call();

            assertFalse(Files.exists(dir.resolve("OtherPlugin/config.yml")), "once tracked, a reset wipes it");
        }
    }

    /** Why /gitsync git resethead refuses to run without a .gitignore. */
    @Test
    void cleanWipesEverythingWithoutTheGitignore(@TempDir Path dir) throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            Files.createDirectories(dir.resolve("OtherPlugin"));
            Files.writeString(dir.resolve("OtherPlugin/config.yml"), "keep: me");

            git.clean().setForce(true).setCleanDirectories(true).call();

            assertFalse(Files.exists(dir.resolve("OtherPlugin/config.yml")), "nothing is ignored, so nothing survives");
        }
    }

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
