package pl.fanth.gitsync.git;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.eclipse.jgit.diff.RawText;

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
    /** The prefix every one of our variables carries in a packed file. */
    public static final String VARIABLE_PREFIX = "GITSYNC_";
    /**
     * A per-server value in a packed file, always written as ${GITSYNC_NAME}. The prefix keeps the
     * ${placeholder} syntax other plugins use out of our way, and the name cannot hold a colon so
     * ${placeholder:default} is never mistaken for one of ours either.
     */
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{" + VARIABLE_PREFIX + "([A-Za-z0-9_.-]+)}");

    private final Path packDir;
    private final Path pluginsDir;
    /** Lowest priority first. */
    private final List<String> layers;
    /** Per-server values put into the packed files, empty when this server declares none. */
    private final Map<String, String> variables;

    public PackRenderer(Path packDir, Path pluginsDir, String role, String instance) {
        this(packDir, pluginsDir, role, instance, Map.of());
    }

    public PackRenderer(Path packDir, Path pluginsDir, String role, String instance, Map<String, String> variables) {
        this.packDir = packDir;
        this.pluginsDir = pluginsDir.normalize();
        this.variables = variables == null ? Map.of() : Map.copyOf(variables);

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
        Map<String, Set<String>> unresolved = new LinkedHashMap<>();

        for (Map.Entry<String, String> planned : plan.entrySet()) {
            String logical = planned.getKey();
            Set<String> missing = new LinkedHashSet<>();
            byte[] desired = substitute(
                    Files.readAllBytes(this.packDir.resolve(planned.getValue()).resolve(logical)), missing);
            for (String name : missing) {
                unresolved.computeIfAbsent(name, key -> new LinkedHashSet<>()).add(logical);
            }
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

        // A value this server never declared would land in the file as a raw ${GITSYNC_NAME}, which
        // the plugin owning it cannot read. Nothing is written until server.yml declares it, and
        // force does not help - there is no value to write either way.
        if (!unresolved.isEmpty() || (!conflicts.isEmpty() && !force)) {
            return new Render(Set.of(), conflicts, unresolved);
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
        return new Render(changed, conflicts, unresolved);
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
        return layer == null ? null
                : substitute(Files.readAllBytes(this.packDir.resolve(layer).resolve(logical)), null);
    }

    /**
     * What to put in the pack for a file edited on this server. Every line that still reads the way
     * the pack rendered it goes back to its ${VARIABLE} form, so the values belonging to this server
     * never reach the others. Lines are matched by content, not by number, so lines added or removed
     * elsewhere in the file do not shift the match.
     * <p>
     * A variable line that was edited by hand has no way back and is reported instead: publishing it
     * would hand this server's value to everyone rendering that file.
     */
    public Reversal reverse(String logical, String layer) throws IOException {
        Path templateFile = this.packDir.resolve(layer).resolve(logical);
        // Nothing to put back for a file the pack has never seen, or on a server without variables
        if (this.variables.isEmpty() || !Files.isRegularFile(templateFile)) {
            return Reversal.NONE;
        }

        byte[] template = Files.readAllBytes(templateFile);
        if (RawText.isBinary(template)) {
            return Reversal.NONE;
        }

        Map<String, String> templates = new LinkedHashMap<>();
        for (String line : lines(template)) {
            String rendered = substitute(line, null);
            // Two template lines rendering the same way render the same way on every server, so
            // either of them puts the file back together correctly
            if (!rendered.equals(line)) {
                templates.putIfAbsent(rendered, line);
            }
        }
        // No variable reaches this file, so the live copy belongs in the pack as it is. Checked
        // before reading it, which keeps jars and other big files out of memory.
        if (templates.isEmpty()) {
            return Reversal.NONE;
        }

        byte[] live = Files.readAllBytes(target(logical));
        if (RawText.isBinary(live)) {
            return Reversal.NONE;
        }

        Set<String> matched = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String line : lines(live)) {
            String original = templates.get(line);
            if (original != null) {
                matched.add(line);
            }
            out.add(original != null ? original : line);
        }

        List<String> warnings = new ArrayList<>();
        templates.forEach((rendered, original) -> {
            if (!matched.contains(rendered)) {
                warnings.add(logical + ": " + original.trim());
            }
        });
        return new Reversal(String.join("\n", out).getBytes(StandardCharsets.UTF_8), warnings);
    }

    /** Copy a file from plugins/ into the given layer of the pack. */
    public void stage(String logical, String layer) throws IOException {
        stage(logical, layer, Reversal.NONE);
    }

    /** Put a file from plugins/ into the given layer of the pack, variables restored. */
    public void stage(String logical, String layer, Reversal reversal) throws IOException {
        Path target = this.packDir.resolve(layer).resolve(logical);
        Files.createDirectories(target.getParent());
        if (reversal.bytes() == null) {
            Files.copy(target(logical), target, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.write(target, reversal.bytes());
        }
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

    /**
     * Every file this server holds under the given logical paths, a plain file naming itself. What
     * a jar joining the pack would drag in with it, before the pack has an entry to ask about it.
     */
    public List<String> filesUnder(Collection<String> logicalPaths) throws IOException {
        Set<String> found = new LinkedHashSet<>();
        for (String logical : logicalPaths) {
            String clean = PackManifest.normalize(logical);
            if (!clean.isEmpty() && !clean.startsWith(OWN_DIRECTORY)) {
                collect(target(clean), found);
            }
        }
        return List.copyOf(found);
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

    /**
     * The packed bytes as this server should see them, the very same array when no variable of ours
     * applies. Undefined names are left alone: a config may hold ${...} of its own, and blanking one
     * out would be worse than leaving it for the plugin to read.
     *
     * @param unresolved collects the names used here that this server does not define, may be null
     */
    private byte[] substitute(byte[] bytes, Set<String> unresolved) {
        if (RawText.isBinary(bytes)) {
            return bytes;
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.contains("${" + VARIABLE_PREFIX)) {
            return bytes;
        }
        String substituted = substitute(text, unresolved);
        return substituted.equals(text) ? bytes : substituted.getBytes(StandardCharsets.UTF_8);
    }

    private String substitute(String text, Set<String> unresolved) {
        Matcher matcher = VARIABLE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = this.variables.get(matcher.group(1));
            if (value == null) {
                if (unresolved != null) {
                    unresolved.add(matcher.group(1));
                }
                // Left as it stands, and never rescanned, so a value holding ${...} stays literal
                value = matcher.group();
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        return matcher.appendTail(out).toString();
    }

    /**
     * Lines without their ending. A server on Windows may hand back a config saved with CRLF, and
     * git stores everything as LF anyway, so the ending is dropped on both sides of a comparison.
     */
    private static List<String> lines(byte[] bytes) {
        List<String> lines = new ArrayList<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n", -1)) {
            lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
        return lines;
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

    /**
     * @param unresolved variable names used by the pack that this server does not define, mapped to
     *                   the files using them
     */
    public record Render(Set<String> changed, List<String> conflicts, Map<String, Set<String>> unresolved) {
    }

    /**
     * The pack-side content of a file edited on this server, and the variable lines that could not
     * be put back. Null bytes mean there was nothing to put back and the file goes in as it is.
     */
    public record Reversal(byte[] bytes, List<String> warnings) {
        public static final Reversal NONE = new Reversal(null, List.of());
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
