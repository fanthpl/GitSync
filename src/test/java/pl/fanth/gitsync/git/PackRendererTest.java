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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        return renderer(role, instance, Map.of());
    }

    private PackRenderer renderer(String role, String instance, Map<String, String> variables) throws IOException {
        this.pack = this.dir.resolve("pack");
        this.plugins = this.dir.resolve("plugins");
        Files.createDirectories(this.pack);
        Files.createDirectories(this.plugins);
        return new PackRenderer(this.pack, this.plugins, role, instance, variables);
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

    @Test
    void aDefinedVariableIsRenderedAndAnUnprefixedOneIsLeftAlone() throws Exception {
        PackRenderer renderer = renderer("", "", Map.of("SERVER_NAME", "lobby-1"));
        packed("base/Essentials/config.yml", "serverName: ${GITSYNC_SERVER_NAME}\nother: ${SERVER_NAME}\n");

        PackRenderer.State state = new PackRenderer.State();
        PackRenderer.Render render = renderer.apply(renderer.plan(PACK), state, false);

        // Without the prefix it belongs to whatever plugin owns the file, so it is left as it is
        assertEquals("serverName: lobby-1\nother: ${SERVER_NAME}\n", localContent("Essentials/config.yml"));
        assertTrue(render.unresolved().isEmpty());

        // The rendered form is what the state remembers, so the next sync sees no drift at all
        PackRenderer.Render again = renderer.apply(renderer.plan(PACK), state, false);
        assertTrue(again.changed().isEmpty());
        assertTrue(again.conflicts().isEmpty());
    }

    @Test
    void publishingPutsTheVariableBackAndKeepsTheEdit() throws Exception {
        PackRenderer renderer = renderer("", "", Map.of("SERVER_NAME", "lobby-1"));
        packed("base/Essentials/config.yml", "serverName: ${GITSYNC_SERVER_NAME}\nmotd: hello\n");
        renderer.apply(renderer.plan(PACK), new PackRenderer.State(), false);

        // Edited around the variable: one line changed, one added above it
        local("Essentials/config.yml", "debug: true\nserverName: lobby-1\nmotd: goodbye\n");

        PackRenderer.Reversal reversal = renderer.reverse("Essentials/config.yml", "base");
        renderer.stage("Essentials/config.yml", "base", reversal);

        assertTrue(reversal.warnings().isEmpty());
        assertEquals("debug: true\nserverName: ${GITSYNC_SERVER_NAME}\nmotd: goodbye\n",
                packedContent("base/Essentials/config.yml"));
    }

    @Test
    void aVariableLineEditedByHandIsReportedAndStaysResolved() throws Exception {
        PackRenderer renderer = renderer("", "", Map.of("SERVER_NAME", "lobby-1"));
        packed("base/Essentials/config.yml", "serverName: ${GITSYNC_SERVER_NAME}\nmotd: hello\n");
        renderer.apply(renderer.plan(PACK), new PackRenderer.State(), false);

        local("Essentials/config.yml", "serverName: renamed-by-hand\nmotd: hello\n");

        PackRenderer.Reversal reversal = renderer.reverse("Essentials/config.yml", "base");
        renderer.stage("Essentials/config.yml", "base", reversal);

        assertEquals(List.of("Essentials/config.yml: serverName: ${GITSYNC_SERVER_NAME}"), reversal.warnings());
        assertEquals("serverName: renamed-by-hand\nmotd: hello\n", packedContent("base/Essentials/config.yml"));
    }

    @Test
    void aLineHoldingTwoVariablesGoesBothWays() throws Exception {
        PackRenderer renderer = renderer("", "", Map.of("HOST", "db.local", "PORT", "3306"));
        packed("base/Essentials/config.yml", "url: jdbc:mysql://${GITSYNC_HOST}:${GITSYNC_PORT}/mc\n");
        renderer.apply(renderer.plan(PACK), new PackRenderer.State(), false);
        assertEquals("url: jdbc:mysql://db.local:3306/mc\n", localContent("Essentials/config.yml"));

        renderer.stage("Essentials/config.yml", "base", renderer.reverse("Essentials/config.yml", "base"));
        assertEquals("url: jdbc:mysql://${GITSYNC_HOST}:${GITSYNC_PORT}/mc\n", packedContent("base/Essentials/config.yml"));
    }

    /** A value YAML cannot carry as a plain scalar is quoted on the way in, and put back on the way out. */
    @Test
    void aValueBreakingYamlIsQuotedAutomatically() throws Exception {
        PackRenderer renderer = renderer("", "", Map.of(
                "MOTD", "hello: world #wave",
                "PREFIX", "&7[lobby]",
                "PORT", "3306",
                "TOKEN", "'tis a token"));
        packed("base/Essentials/config.yml", """
                motd: ${GITSYNC_MOTD}
                port: ${GITSYNC_PORT}
                token: ${GITSYNC_TOKEN}
                quoted: "${GITSYNC_MOTD}"
                inside: pre-${GITSYNC_MOTD}
                list:
                  - ${GITSYNC_PREFIX}
                """);

        renderer.apply(renderer.plan(PACK), new PackRenderer.State(), false);

        assertEquals("""
                motd: 'hello: world #wave'
                port: 3306
                token: '''tis a token'
                quoted: "hello: world #wave"
                inside: pre-hello: world #wave
                list:
                  - '&7[lobby]'
                """, localContent("Essentials/config.yml"),
                "only a whole, unquoted value is wrapped; a number stays a number");

        // The quoted form is what reverse knows, so publishing puts the placeholder back
        renderer.stage("Essentials/config.yml", "base", renderer.reverse("Essentials/config.yml", "base"));
        assertTrue(packedContent("base/Essentials/config.yml").startsWith("motd: ${GITSYNC_MOTD}\n"));
    }

    /** The Windows servers sharing the pack hand back CRLF, git stores LF either way. */
    @Test
    void aConfigSavedWithCrlfStillPutsTheVariableBack() throws Exception {
        PackRenderer renderer = renderer("", "", Map.of("SERVER_NAME", "lobby-1"));
        packed("base/Essentials/config.yml", "serverName: ${GITSYNC_SERVER_NAME}\nmotd: hello\n");
        renderer.apply(renderer.plan(PACK), new PackRenderer.State(), false);

        local("Essentials/config.yml", "serverName: lobby-1\r\nmotd: goodbye\r\n");

        PackRenderer.Reversal reversal = renderer.reverse("Essentials/config.yml", "base");
        renderer.stage("Essentials/config.yml", "base", reversal);

        assertTrue(reversal.warnings().isEmpty());
        assertEquals("serverName: ${GITSYNC_SERVER_NAME}\nmotd: goodbye\n", packedContent("base/Essentials/config.yml"));
    }

    @Test
    void aChangedVariableValueIsAnUpdateAndNotAConflict() throws Exception {
        PackRenderer renderer = renderer("", "", Map.of("SERVER_NAME", "lobby-1"));
        packed("base/Essentials/config.yml", "serverName: ${GITSYNC_SERVER_NAME}\n");

        PackRenderer.State state = new PackRenderer.State();
        renderer.apply(renderer.plan(PACK), state, false);
        assertEquals("serverName: lobby-1\n", localContent("Essentials/config.yml"));

        PackRenderer renamed = renderer("", "", Map.of("SERVER_NAME", "lobby-2"));
        PackRenderer.Render render = renamed.apply(renamed.plan(PACK), state, false);

        assertTrue(render.conflicts().isEmpty(), "the file was never edited here, only the value changed");
        assertEquals(Set.of("Essentials/config.yml"), render.changed());
        assertEquals("serverName: lobby-2\n", localContent("Essentials/config.yml"));
    }

    @Test
    void aBinaryFileIsNeverSubstituted() throws Exception {
        PackRenderer renderer = renderer("", "", Map.of("SERVER_NAME", "lobby-1"));
        byte[] binary = "PK\003\004\0${GITSYNC_SERVER_NAME}\0".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(this.pack.resolve("base"));
        Files.write(this.pack.resolve("base/ItemsAdder_4.0.17.jar"), binary);

        renderer.apply(renderer.plan(PACK), new PackRenderer.State(), false);

        assertArrayEquals(binary, Files.readAllBytes(this.plugins.resolve("ItemsAdder_4.0.17.jar")));
        assertNull(renderer.reverse("ItemsAdder_4.0.17.jar", "base").bytes(), "a jar goes into the pack as it is");
    }

    @Test
    void aServerWithoutVariablesTouchesNothing() throws Exception {
        PackRenderer renderer = renderer("", "");
        packed("base/Essentials/config.yml", "serverName: ${SERVER_NAME}\n");

        PackRenderer.Render render = renderer.apply(renderer.plan(PACK), new PackRenderer.State(), false);

        assertEquals("serverName: ${SERVER_NAME}\n", localContent("Essentials/config.yml"));
        assertTrue(render.unresolved().isEmpty(), "nothing here is ours to fill in");
        assertNull(renderer.reverse("Essentials/config.yml", "base").bytes());
    }

    /** A missing value cannot be rendered, and half a config on disk is worse than none. */
    @Test
    void aMissingVariableStopsTheWholeRender() throws Exception {
        PackRenderer renderer = renderer("", "", Map.of("SERVER_NAME", "lobby-1"));
        packed("base/Essentials/config.yml", "serverName: ${GITSYNC_SERVER_NAME}\nmotd: ${GITSYNC_SOMEONE_ELSES}\n");
        packed("base/ItemsAdder/config.yml", "storage: sqlite\n");

        PackRenderer.State state = new PackRenderer.State();
        PackRenderer.Render render = renderer.apply(renderer.plan(PACK), state, false);

        assertEquals(Map.of("SOMEONE_ELSES", Set.of("Essentials/config.yml")), render.unresolved());
        assertTrue(render.changed().isEmpty());
        assertTrue(state.files.isEmpty(), "nothing was rendered, so nothing is tracked");
        assertFalse(Files.exists(this.plugins.resolve("Essentials/config.yml")));
        assertFalse(Files.exists(this.plugins.resolve("LuckPerms/config.yml")), "the file without variables waits too");

        // Forcing cannot invent the value either
        assertTrue(renderer.apply(renderer.plan(PACK), state, true).changed().isEmpty());
    }

    private void packed(String path, String content) throws IOException {
        write(this.pack.resolve(path), content);
    }

    private String packedContent(String path) throws IOException {
        return Files.readString(this.pack.resolve(path), StandardCharsets.UTF_8);
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
