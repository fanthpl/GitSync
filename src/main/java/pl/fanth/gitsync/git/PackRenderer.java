package pl.fanth.gitsync.git;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Resolves the layered pack into the plugins directory: base, then role, then instance, with the
 * higher layer replacing the whole file. Plain file work, no Bukkit and no git, so the bootstrap
 * phase can run it too.
 */
public class PackRenderer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Where a version starts in a jar name, so ItemsAdder_4.0.17.jar becomes ItemsAdder_*.jar. */
    private static final Pattern VERSION_START = Pattern.compile("[-_]\\d");
    /** Our own data directory, a pack entry pointing in there would overwrite the pack itself. */
    private static final String OWN_DIRECTORY = "GitSync/";

    private final Path packDir;
    private final Path pluginsDir;
    /** Lowest priority first. */
    private final List<String> layers;

    public PackRenderer(Path packDir, Path pluginsDir, String role, String instance) {
        this.packDir = packDir;
        this.pluginsDir = pluginsDir.normalize();

        List<String> layers = new ArrayList<>();
        layers.add("base");
        if (role != null && !role.isBlank()) {
            layers.add("role/" + role.trim());
        }
        if (instance != null && !instance.isBlank()) {
            layers.add("instance/" + instance.trim());
        }
        this.layers = List.copyOf(layers);
    }

    public List<String> layers() {
        return this.layers;
    }

    /** The layer a file created on this server goes to when the pack has never seen it. */
    public String defaultLayer() {
        return this.layers.size() > 1 ? this.layers.get(1) : "base";
    }

    /**
     * What every declared path should look like on disk: the logical path mapped to the layer that
     * wins it. Anything the pack does not declare, and anything inside our own data directory, is
     * left out.
     */
    public Map<String, String> plan(PackManifest manifest) throws IOException {
        Map<String, String> plan = new LinkedHashMap<>();
        for (String layer : this.layers) {
            Path root = this.packDir.resolve(layer);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String logical = root.relativize(file).toString().replace('\\', '/');
                    if (logical.startsWith(OWN_DIRECTORY) || !manifest.matches(logical)) {
                        continue;
                    }
                    plan.put(logical, layer);
                }
            }
        }
        suppressShadowedJars(manifest, plan);
        return plan;
    }

    /**
     * A jar override carries a different file name, so it does not replace the lower layer the way
     * a config does - both would land in plugins/ and the server would load two versions of the
     * same plugin. The highest layer holding any jar of a wildcard takes that wildcard alone.
     */
    private void suppressShadowedJars(PackManifest manifest, Map<String, String> plan) {
        for (PackManifest.Entry entry : manifest.plugins.values()) {
            List<String> jars = plan.keySet().stream().filter(entry::matchesJar).toList();
            if (jars.size() < 2) {
                continue;
            }
            int winner = jars.stream().mapToInt(jar -> this.layers.indexOf(plan.get(jar))).max().orElse(-1);
            jars.stream().filter(jar -> this.layers.indexOf(plan.get(jar)) < winner).forEach(plan::remove);
        }
    }

    /**
     * Bring plugins/ in line with the plan.
     * <p>
     * A file whose content on disk no longer matches what we last rendered was edited on this
     * server. Overwriting it would throw that work away, so it is reported as a conflict and the
     * whole render is abandoned - unless force says the pack wins.
     *
     * @return the logical paths whose content changed, and the ones that were edited here
     */
    public Render apply(Map<String, String> plan, State state, boolean force) throws IOException {
        Map<String, byte[]> writes = new LinkedHashMap<>();
        Map<String, String> writeLayers = new LinkedHashMap<>();
        Map<String, State.Entry> adopted = new LinkedHashMap<>();
        Set<String> deletes = new LinkedHashSet<>();
        List<String> conflicts = new ArrayList<>();

        for (Map.Entry<String, String> planned : plan.entrySet()) {
            String logical = planned.getKey();
            byte[] desired = Files.readAllBytes(this.packDir.resolve(planned.getValue()).resolve(logical));
            String desiredHash = hash(desired);
            String diskHash = hashOf(target(logical));

            // Identical content, whatever the layer or the state says. Nothing to write, nothing
            // to reload, but the state has to learn which layer owns it now.
            if (desiredHash.equals(diskHash)) {
                adopted.put(logical, new State.Entry(planned.getValue(), desiredHash));
                continue;
            }
            if (!Objects.equals(diskHash, hashIn(state, logical))) {
                conflicts.add(logical);
            }
            writes.put(logical, desired);
            writeLayers.put(logical, planned.getValue());
        }

        for (Map.Entry<String, State.Entry> tracked : state.files.entrySet()) {
            String logical = tracked.getKey();
            if (plan.containsKey(logical)) {
                continue;
            }
            String diskHash = hashOf(target(logical));
            if (diskHash == null) {
                // Gone from the pack and gone from disk, only the state still remembers it
                deletes.add(logical);
                continue;
            }
            if (!diskHash.equals(tracked.getValue().hash)) {
                conflicts.add(logical);
            }
            deletes.add(logical);
        }

        if (!conflicts.isEmpty() && !force) {
            return new Render(Set.of(), conflicts);
        }

        Set<String> changed = new LinkedHashSet<>();
        for (Map.Entry<String, byte[]> write : writes.entrySet()) {
            Path target = target(write.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, write.getValue());
            state.files.put(write.getKey(), new State.Entry(writeLayers.get(write.getKey()), hash(write.getValue())));
            changed.add(write.getKey());
        }
        for (String logical : deletes) {
            if (Files.deleteIfExists(target(logical))) {
                changed.add(logical);
            }
            state.files.remove(logical);
        }
        state.files.putAll(adopted);
        return new Render(changed, conflicts);
    }

    /** Files declared by the pack that differ on this server from what we last rendered. */
    public List<LocalChange> localChanges(PackManifest manifest, State state) throws IOException {
        Set<String> candidates = new LinkedHashSet<>(state.files.keySet());
        candidates.addAll(declaredOnDisk(manifest));

        List<LocalChange> changes = new ArrayList<>();
        for (String logical : candidates) {
            String diskHash = hashOf(target(logical));
            State.Entry tracked = state.files.get(logical);

            if (tracked == null) {
                if (diskHash != null) {
                    changes.add(new LocalChange(logical, Kind.NEW, defaultLayer()));
                }
            } else if (diskHash == null) {
                changes.add(new LocalChange(logical, Kind.DELETED, tracked.layer));
            } else if (!diskHash.equals(tracked.hash)) {
                changes.add(new LocalChange(logical, Kind.MODIFIED, tracked.layer));
            }
        }
        return changes;
    }

    /** Jars sitting in plugins/ that no pack entry claims - candidates for joining the pack. */
    public List<String> unknownJars(PackManifest manifest, Collection<String> ignored) throws IOException {
        List<String> jars = new ArrayList<>();
        try (Stream<Path> files = Files.list(this.pluginsDir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".jar")) {
                    continue;
                }
                if (manifest.plugins.values().stream().anyMatch(entry -> entry.matchesJar(name))) {
                    continue;
                }
                if (ignored.stream().anyMatch(glob -> matchesGlob(glob, name))) {
                    continue;
                }
                jars.add(name);
            }
        }
        return jars;
    }

    /** What the pack says this file should contain, null when the pack does not have it. */
    public byte[] renderedBytes(Map<String, String> plan, String logical) throws IOException {
        String layer = plan.get(logical);
        return layer == null ? null : Files.readAllBytes(this.packDir.resolve(layer).resolve(logical));
    }

    /** Copy a file from plugins/ into the given layer of the pack. */
    public void stage(String logical, String layer) throws IOException {
        Path target = this.packDir.resolve(layer).resolve(logical);
        Files.createDirectories(target.getParent());
        Files.copy(target(logical), target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Drop a file from the given layer of the pack. */
    public void unstage(String logical, String layer) throws IOException {
        Files.deleteIfExists(this.packDir.resolve(layer).resolve(logical));
    }

    public String diskHash(String logical) throws IOException {
        return hashOf(target(logical));
    }

    public Path pluginsFile(String logical) {
        return target(logical);
    }

    /** Every declared path that currently exists on this server. */
    private Set<String> declaredOnDisk(PackManifest manifest) throws IOException {
        Set<String> found = new LinkedHashSet<>();
        for (PackManifest.Entry entry : manifest.plugins.values()) {
            if (entry.configPaths != null) {
                for (String configPath : entry.configPaths) {
                    String clean = PackManifest.normalize(configPath);
                    if (clean.isEmpty() || clean.startsWith(OWN_DIRECTORY)) {
                        continue;
                    }
                    collect(target(clean), found);
                }
            }
        }

        try (Stream<Path> files = Files.list(this.pluginsDir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (manifest.plugins.values().stream().anyMatch(entry -> entry.matchesJar(name))) {
                    found.add(name);
                }
            }
        }
        return found;
    }

    private void collect(Path path, Set<String> found) throws IOException {
        if (Files.isRegularFile(path)) {
            found.add(this.pluginsDir.relativize(path).toString().replace('\\', '/'));
            return;
        }
        if (!Files.isDirectory(path)) {
            return;
        }
        try (Stream<Path> files = Files.walk(path)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                found.add(this.pluginsDir.relativize(file).toString().replace('\\', '/'));
            }
        }
    }

    /** Nothing the pack declares may reach outside plugins/, whatever the repository holds. */
    private Path target(String logical) {
        Path path = this.pluginsDir.resolve(logical).normalize();
        if (!path.startsWith(this.pluginsDir)) {
            throw new IllegalArgumentException("Path escapes the plugins directory: " + logical);
        }
        return path;
    }

    private String hashOf(Path file) throws IOException {
        return Files.isRegularFile(file) ? hash(Files.readAllBytes(file)) : null;
    }

    private static String hashIn(State state, String logical) {
        State.Entry entry = state.files.get(logical);
        return entry == null ? null : entry.hash;
    }

    static boolean matchesGlob(String glob, String path) {
        return FileSystems.getDefault()
                .getPathMatcher("glob:" + PackManifest.normalize(glob))
                .matches(Path.of(path));
    }

    /** EssentialsX-2.20.1.jar becomes EssentialsX-*.jar, so a version bump needs no pack.json edit. */
    public static String deriveWildcard(String jarName) {
        Matcher matcher = VERSION_START.matcher(jarName);
        return matcher.find() ? jarName.substring(0, matcher.start() + 1) + "*.jar" : jarName;
    }

    public static String derivePluginName(String jarName) {
        Matcher matcher = VERSION_START.matcher(jarName);
        if (matcher.find()) {
            return jarName.substring(0, matcher.start());
        }
        return jarName.endsWith(".jar") ? jarName.substring(0, jarName.length() - ".jar".length()) : jarName;
    }

    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            // Every JRE ships SHA-256
            throw new IllegalStateException(exception);
        }
    }

    public enum Kind {
        NEW, MODIFIED, DELETED
    }

    public record LocalChange(String logicalPath, Kind kind, String targetLayer) {
    }

    public record Render(Set<String> changed, List<String> conflicts) {
    }

    /**
     * What the last render put on disk. Comparing it against both the pack and the working files is
     * what tells an update apart from an edit made on this server.
     */
    public static class State {
        public String packHash;
        public Map<String, Entry> files = new LinkedHashMap<>();

        public static class Entry {
            public String layer;
            public String hash;

            public Entry() {
            }

            public Entry(String layer, String hash) {
                this.layer = layer;
                this.hash = hash;
            }
        }

        /** A missing or damaged state file starts over, which turns every local file into a conflict. */
        public static State load(Path file) {
            try {
                if (Files.isRegularFile(file)) {
                    State state = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), State.class);
                    if (state != null) {
                        if (state.files == null) {
                            state.files = new LinkedHashMap<>();
                        }
                        return state;
                    }
                }
            } catch (Exception exception) {
                return new State();
            }
            return new State();
        }

        public void save(Path file) throws IOException {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
        }
    }
}
