package pl.fanth.gitsync.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackRendererTest {
    private static final PackManifest PACK = PackManifest.parse("""
            {
              "plugins": {
                "ItemsAdder": {
                  "pluginJarWildcard": "ItemsAdder_*.jar",
                  "configPaths": ["ItemsAdder/config.yml"],
                  "reloadCommands": ["iareload"]
                },
                "Essentials": {
                  "configPaths": ["Essentials/config.yml"]
                },
                "GitSync": {
                  "configPaths": ["GitSync/config.yml"]
                }
              }
            }
            """);

    @TempDir
    Path dir;

    private Path pack;
    private Path plugins;

    private PackRenderer renderer(String role, String instance) throws IOException {
        this.pack = this.dir.resolve("pack");
        this.plugins = this.dir.resolve("plugins");
        Files.createDirectories(this.pack);
        Files.createDirectories(this.plugins);
        return new PackRenderer(this.pack, this.plugins, role, instance);
    }

    @Test
    void theHighestLayerHoldingAFileWinsIt() throws Exception {
        PackRenderer renderer = renderer("city", "city-1");
        packed("base/ItemsAdder/config.yml", "base");
        packed("role/city/ItemsAdder/config.yml", "city");
        packed("instance/city-1/ItemsAdder/config.yml", "city-1");
        packed("base/Essentials/config.yml", "shared");
        // Declared by the pack, but pointing at our own data directory
        packed("base/GitSync/config.yml", "never");
        packed("base/Other/config.yml", "not declared");

        Map<String, String> plan = renderer.plan(PACK);

        assertEquals("instance/city-1", plan.get("ItemsAdder/config.yml"));
        assertEquals("base", plan.get("Essentials/config.yml"));
        assertFalse(plan.containsKey("GitSync/config.yml"), "our own directory is off limits");
        assertFalse(plan.containsKey("Other/config.yml"), "only what pack.json declares is rendered");
    }

    /** A jar override has a different file name, so both would land in plugins/ without this. */
    @Test
    void aRoleJarHidesTheBaseJarOfTheSamePlugin() throws Exception {
        PackRenderer renderer = renderer("city", "");
        packed("base/ItemsAdder_4.0.17.jar", "old");
        packed("role/city/ItemsAdder_4.0.18.jar", "new");

        Map<String, String> plan = renderer.plan(PACK);

        assertEquals(Set.of("ItemsAdder_4.0.18.jar"), plan.keySet());
    }

    @Test
    void aFirstRunTreatsADifferingLocalFileAsAConflict() throws Exception {
        PackRenderer renderer = renderer("", "");
        packed("base/Essentials/config.yml", "from the pack");
        local("Essentials/config.yml", "edited here");

        PackRenderer.State state = new PackRenderer.State();
        PackRenderer.Render render = renderer.apply(renderer.plan(PACK), state, false);

        assertEquals(List.of("Essentials/config.yml"), render.conflicts());
        assertEquals("edited here", localContent("Essentials/config.yml"), "nothing may be written on a conflict");

        renderer.apply(renderer.plan(PACK), state, true);
        assertEquals("from the pack", localContent("Essentials/config.yml"), "force lets the pack win");
    }

    @Test
    void anIdenticalLocalFileIsAdoptedWithoutAReload() throws Exception {
        PackRenderer renderer = renderer("", "");
        packed("base/Essentials/config.yml", "same");
        local("Essentials/config.yml", "same");

        PackRenderer.State state = new PackRenderer.State();
        PackRenderer.Render render = renderer.apply(renderer.plan(PACK), state, false);

        assertTrue(render.conflicts().isEmpty());
        assertTrue(render.changed().isEmpty(), "identical content is not a change");
        assertEquals("base", state.files.get("Essentials/config.yml").layer);
    }

    @Test
    void anUpdateIsAppliedButALocalEditBlocksIt() throws Exception {
        PackRenderer renderer = renderer("", "");
        packed("base/Essentials/config.yml", "one");

        PackRenderer.State state = new PackRenderer.State();
        assertEquals(Set.of("Essentials/config.yml"), renderer.apply(renderer.plan(PACK), state, false).changed());
        assertEquals("one", localContent("Essentials/config.yml"));

        // A clean file follows the pack
        packed("base/Essentials/config.yml", "two");
        assertEquals(Set.of("Essentials/config.yml"), renderer.apply(renderer.plan(PACK), state, false).changed());
        assertEquals("two", localContent("Essentials/config.yml"));

        // Once it was edited here, the same update has to stop
        local("Essentials/config.yml", "mine");
        packed("base/Essentials/config.yml", "three");
        PackRenderer.Render render = renderer.apply(renderer.plan(PACK), state, false);
        assertEquals(List.of("Essentials/config.yml"), render.conflicts());
        assertEquals("mine", localContent("Essentials/config.yml"));
    }

    @Test
    void aFileDroppedFromThePackIsDeletedUnlessItWasEditedHere() throws Exception {
        PackRenderer renderer = renderer("", "");
        packed("base/Essentials/config.yml", "one");

        PackRenderer.State state = new PackRenderer.State();
        renderer.apply(renderer.plan(PACK), state, false);

        Files.delete(this.pack.resolve("base/Essentials/config.yml"));
        assertEquals(Set.of("Essentials/config.yml"), renderer.apply(renderer.plan(PACK), state, false).changed());
        assertFalse(Files.exists(this.plugins.resolve("Essentials/config.yml")));
        assertTrue(state.files.isEmpty(), "a deleted file leaves the state too");

        // Same again, but the file was edited here first
        packed("base/Essentials/config.yml", "one");
        renderer.apply(renderer.plan(PACK), state, false);
        local("Essentials/config.yml", "mine");
        Files.delete(this.pack.resolve("base/Essentials/config.yml"));

        assertEquals(List.of("Essentials/config.yml"), renderer.apply(renderer.plan(PACK), state, false).conflicts());
        assertEquals("mine", localContent("Essentials/config.yml"));
    }

    @Test
    void localChangesPointAtTheLayerTheFileCameFrom() throws Exception {
        PackRenderer renderer = renderer("city", "");
        packed("base/Essentials/config.yml", "shared");
        packed("role/city/ItemsAdder/config.yml", "city");

        PackRenderer.State state = new PackRenderer.State();
        renderer.apply(renderer.plan(PACK), state, false);

        local("Essentials/config.yml", "edited");
        Files.delete(this.plugins.resolve("ItemsAdder/config.yml"));
        // Declared by the pack, created on this server, never rendered
        local("ItemsAdder_4.0.17.jar", "jar");

        Map<String, PackRenderer.LocalChange> changes = renderer.localChanges(PACK, state).stream()
                .collect(java.util.stream.Collectors.toMap(PackRenderer.LocalChange::logicalPath, change -> change));

        assertEquals(PackRenderer.Kind.MODIFIED, changes.get("Essentials/config.yml").kind());
        assertEquals("base", changes.get("Essentials/config.yml").targetLayer());

        assertEquals(PackRenderer.Kind.DELETED, changes.get("ItemsAdder/config.yml").kind());
        assertEquals("role/city", changes.get("ItemsAdder/config.yml").targetLayer());

        assertEquals(PackRenderer.Kind.NEW, changes.get("ItemsAdder_4.0.17.jar").kind());
        assertEquals("role/city", changes.get("ItemsAdder_4.0.17.jar").targetLayer(), "new files go to the role layer");
    }

    @Test
    void onlyJarsNobodyClaimsAreOffered() throws Exception {
        PackRenderer renderer = renderer("", "");
        local("ItemsAdder_4.0.17.jar", "declared by the pack");
        local("LuckPerms-5.4.jar", "new");
        local("Private-1.0.jar", "ignored");
        local("Essentials/config.yml", "not a jar");

        assertEquals(List.of("LuckPerms-5.4.jar"), renderer.unknownJars(PACK, List.of("Private-*.jar")));
    }

    @Test
    void aWildcardSurvivesAVersionBump() {
        assertEquals("EssentialsX-*.jar", PackRenderer.deriveWildcard("EssentialsX-2.20.1.jar"));
        assertEquals("ItemsAdder_*.jar", PackRenderer.deriveWildcard("ItemsAdder_4.0.17.jar"));
        assertEquals("NoVersion.jar", PackRenderer.deriveWildcard("NoVersion.jar"));

        assertEquals("EssentialsX", PackRenderer.derivePluginName("EssentialsX-2.20.1.jar"));
        assertEquals("NoVersion", PackRenderer.derivePluginName("NoVersion.jar"));
    }

    private void packed(String path, String content) throws IOException {
        write(this.pack.resolve(path), content);
    }

    private void local(String path, String content) throws IOException {
        write(this.plugins.resolve(path), content);
    }

    private String localContent(String path) throws IOException {
        return Files.readString(this.plugins.resolve(path), StandardCharsets.UTF_8);
    }

    private void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
