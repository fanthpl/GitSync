package pl.fanth.gitsync.prompt;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pl.fanth.gitsync.GitSyncPlugin;
import pl.fanth.gitsync.config.DataConfiguration;
import pl.fanth.gitsync.git.GitSyncService;
import pl.fanth.gitsync.git.PackManifest;
import pl.fanth.gitsync.git.PackRenderer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Walks the admin through everything a push would publish, and commits nothing until it is
 * confirmed. The screens, in order:
 * <ol>
 *   <li>a jar in plugins/ that no pack entry claims - what its config paths are and how it
 *       reloads, one screen per jar, submitted or kept private;</li>
 *   <li>where every file the pack has never seen goes, a screen per plugin that owns them - the
 *       jars just submitted first, with the files they drag in, then the plugins the pack already
 *       had. Guessing at the role layer would quietly hand a file meant for this server to every
 *       server of the same kind, so each screen settles its plugin in one click or its files one
 *       by one;</li>
 *   <li>the summary, which lists every file the push touches, the way /gitsync git status does.</li>
 * </ol>
 * A file that only drifted needs no question at all: it goes back into the layer it was rendered
 * from.
 * <p>
 * Nothing is written anywhere until confirm is clicked, so cancelling or walking away leaves the
 * pack and this server exactly as they were.
 * <p>
 * The very same plugin screen also edits an entry the pack already has, which is the one case that
 * stops there: it only writes the local pack.json, and the next pushupdate is what publishes it.
 */
public class PushUpdatePrompt {
    /**
     * One session for the whole server, shared: whoever runs /gitsync prompt show answers the same
     * questions the last admin left behind, and it outlives both their logout and a restart.
     */
    private static volatile PushUpdatePrompt active;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Adding a value needs it typed out, and a click can only put a command in the chat box. */
    private static final String INPUT_COMMAND = "/gitsync prompt ";
    /** Two lines per file, and the chat keeps around a hundred - a page has to leave room to read. */
    private static final int FILES_PER_PAGE = 8;
    /** The summary is there to be read before confirming, which a thousand lines is not. */
    private static final int SUMMARY_LINES = 20;
    /** Set once at startup, so a session read back from disk still knows where to commit. */
    private static Publisher publisher;
    private static Editor editor;

    /** Whoever is looking at it right now - not part of the session, so it is never saved. */
    private transient Player viewer;
    /** Who started it, for the message a second push gets. */
    private String starter;
    private String message;
    private boolean confirmed;
    /** An entry the pack already has, edited on its own - one screen, and no file is staged. */
    private boolean editing;
    private final List<PluginDraft> plugins = new ArrayList<>();
    /** What a jar joining the pack brings with it, rebuilt as the config paths are typed in. */
    private final List<FileGroup> pluginGroups = new ArrayList<>();
    /** Files the pack already owns but has never seen here, a screen per plugin that owns them. */
    private final List<FileGroup> fileGroups = new ArrayList<>();
    /** Edited and deleted files, which already know the layer they came from. */
    private final List<PackRenderer.LocalChange> tracked = new ArrayList<>();

    /** Which screen is up: a plugin, then the new files, then the summary. */
    private int index;
    /** How far down the file list of the screen that is up, for the groups too long to print at once. */
    private int page;
    /** Bumped by every print, so the buttons still sitting further up the chat go quiet. */
    private int generation;

    /** For Gson, which fills the fields in itself. */
    private PushUpdatePrompt() {
    }

    public PushUpdatePrompt(Player player, List<PackRenderer.LocalChange> changes, List<String> jars,
                            String message, boolean confirmed) {
        this.viewer = player;
        this.starter = player.getName();
        this.message = message;
        this.confirmed = confirmed;
        for (String jar : jars) {
            this.plugins.add(new PluginDraft(jar, service().pluginName(jar), PackRenderer.deriveWildcard(jar)));
        }
        // Grouped by whoever owns them, so a plugin's files are answered together instead of one
        // long list nobody reads to the end
        PackManifest manifest = service().manifest();
        Map<String, List<FileDraft>> grouped = new LinkedHashMap<>();
        for (PackRenderer.LocalChange change : changes) {
            if (change.kind() != PackRenderer.Kind.NEW) {
                this.tracked.add(change);
                continue;
            }
            String owner = manifest.ownerOf(change.logicalPath());
            grouped.computeIfAbsent(owner == null ? "Other files" : owner, key -> new ArrayList<>())
                .add(new FileDraft(change.logicalPath()));
        }
        grouped.forEach((owner, files) -> this.fileGroups.add(new FileGroup(owner, files)));
    }

    /**
     * The same screen a jar joining the pack gets, for a plugin already in it. The jar itself is
     * not in hand here - the entry is matched to one by its wildcard - so only the three things
     * pack.json holds are on the screen.
     */
    public PushUpdatePrompt(Player player, String name, PackManifest.Entry entry) {
        this.viewer = player;
        this.starter = player.getName();
        this.editing = true;

        PluginDraft draft = new PluginDraft(null, name,
            entry.pluginJarWildcard == null ? "" : entry.pluginJarWildcard);
        if (entry.configPaths != null) {
            draft.configPaths.addAll(entry.configPaths);
        }
        if (entry.reloadCommands != null) {
            draft.reloadCommands.addAll(entry.reloadCommands);
        }
        this.plugins.add(draft);
    }

    /** Where the answers go once they are confirmed, which a restart cannot bring back by itself. */
    public interface Publisher {
        void publish(CommandSender sender, String message, boolean confirmed,
                     List<GitSyncService.NewPlugin> plugins, Map<String, String> fileLayers);
    }

    public static void publisher(Publisher publisher) {
        PushUpdatePrompt.publisher = publisher;
    }

    /** Where an edited entry goes, which is the local pack.json - published by the next pushupdate. */
    public interface Editor {
        void edit(CommandSender sender, String name, PackManifest.Entry entry);
    }

    public static void editor(Editor editor) {
        PushUpdatePrompt.editor = editor;
    }

    public void start() {
        active = this;
        render();
    }

    /** Who started the session waiting to be answered, null when there is none. */
    public static String busyWith() {
        PushUpdatePrompt prompt = session();
        return prompt == null ? null : prompt.starter;
    }

    /** The one session there is, read back from disk the first time it is asked for after a restart. */
    private static synchronized PushUpdatePrompt session() {
        if (active == null) {
            try {
                Path file = file();
                if (Files.isRegularFile(file)) {
                    active = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), PushUpdatePrompt.class);
                }
            } catch (Exception exception) {
                // A file nobody can read is a session nobody can answer, so it is dropped
                GitSyncPlugin.instance().getLogger().log(Level.WARNING, "Could not read the saved push prompt", exception);
                drop();
            }
        }
        return active;
    }

    private static Path file() {
        return GitSyncPlugin.instance().getDataFolder().toPath().resolve("prompt.json");
    }

    /** Every answer is written out, so a restart mid-session picks up where the admin left off. */
    private void save() {
        try {
            Path file = file();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            GitSyncPlugin.instance().getLogger().log(Level.WARNING, "Could not save the push prompt", exception);
        }
    }

    private static void drop() {
        active = null;
        try {
            Files.deleteIfExists(file());
        } catch (IOException exception) {
            GitSyncPlugin.instance().getLogger().log(Level.WARNING, "Could not delete the saved push prompt", exception);
        }
    }

    /** A value the admin typed after clicking one of the [+] buttons. */
    public static void accept(Player player, String field, String value) {
        PushUpdatePrompt prompt = of(player);
        if (prompt != null) {
            prompt.set(field, value.trim());
        }
    }

    /** The chat is short and this block is long, so it can always be asked for again. */
    public static void show(Player player) {
        PushUpdatePrompt prompt = of(player);
        if (prompt != null) {
            prompt.render();
        }
    }

    /** Nothing has been written yet, so dropping the session is the whole of cancelling it. */
    public static void cancel(Player player) {
        if (session() == null) {
            player.sendMessage(Component.text("Nothing is waiting to be published.").color(NamedTextColor.RED));
            return;
        }
        drop();
        player.sendMessage(Component.text("Cancelled, nothing was committed.").color(NamedTextColor.YELLOW));
    }

    private static PushUpdatePrompt of(Player player) {
        PushUpdatePrompt prompt = session();
        if (prompt == null) {
            player.sendMessage(Component.text("Nothing is waiting to be published, run /gitsync pushupdate first.")
                .color(NamedTextColor.RED));
            return null;
        }
        // The session is everyone's, so whoever asked about it last is who it prints to
        prompt.viewer = player;
        return prompt.alive() ? prompt : null;
    }

    /**
     * The chat keeps every button this session ever printed, so once it is confirmed or cancelled
     * they have to go quiet - editing a draft that was already committed changes nothing but what
     * the next screen claims is about to be pushed.
     */
    private boolean alive() {
        if (active != this) {
            say("That push is already done, run /gitsync pushupdate again to publish more.",
                NamedTextColor.RED);
            return false;
        }
        return true;
    }

    private void set(String field, String value) {
        if (this.index >= this.plugins.size()) {
            say("There is no plugin on this screen, go back with the arrow.", NamedTextColor.RED);
            return;
        }

        PluginDraft draft = this.plugins.get(this.index);
        switch (field.toLowerCase()) {
            case "wildcard" -> setWildcard(draft, value);
            // A command is dispatched without its leading slash, whichever way it was typed
            case "reload" -> add(draft.reloadCommands, value.replaceFirst("^/", ""));
            case "config" -> addConfigPath(draft, value);
            default -> {
                say("Unknown field " + field + ", expected wildcard, config or reload.", NamedTextColor.RED);
                return;
            }
        }
        render();
    }

    private void add(List<String> values, String value) {
        if (!value.isBlank() && !values.contains(value)) {
            values.add(value);
        }
    }

    /**
     * A config path may only ever name something inside plugins/. This ends up in pack.json, so a
     * ../ let through here would have every server rendering the pack write outside its own plugins
     * directory - and our own data folder is refused too, since the renderer never touches it and
     * the entry would silently do nothing.
     */
    private void addConfigPath(PluginDraft draft, String value) {
        String path = PackManifest.normalize(value);
        String own = GitSyncPlugin.instance().getDataFolder().getName();
        if (path.isEmpty()) {
            return;
        }

        if (path.equals(own) || path.startsWith(own + "/")) {
            say(path + " is GitSync's own directory, it is never synced.", NamedTextColor.RED);
            return;
        }
        try {
            // The renderer owns the rule, so an escape is caught by the very check that writing uses
            renderer().pluginsFile(path);
        } catch (IllegalArgumentException exception) {
            say(value + " points outside the plugins directory.", NamedTextColor.RED);
            return;
        }
        add(draft.configPaths, path);
    }

    /**
     * A wildcard that does not match the jar it was typed for would put an entry in pack.json that
     * claims no file at all, so the jar would never reach the pack. Refused, with the old one kept.
     */
    private void setWildcard(PluginDraft draft, String value) {
        if (value.isBlank()) {
            say("A wildcard is what the pack matches jars by, it cannot be empty.", NamedTextColor.RED);
            return;
        }

        PackManifest.Entry probe = new PackManifest.Entry();
        probe.pluginJarWildcard = value;
        // An entry already in the pack is edited without its jar in hand, nothing here to match it against
        if (draft.jar == null || probe.matchesJar(draft.jar)) {
            draft.wildcard = value;
            return;
        }
        say(value + " does not match " + draft.jar + ", kept " + draft.wildcard + ".", NamedTextColor.RED);
    }

    private void render() {
        int printed = ++this.generation;
        // Every answer arrives here on its way to the screen, so this is the one place worth saving
        save();

        this.viewer.sendMessage(Component.text("=== ATTENTION REQUIRED ===")
            .color(NamedTextColor.RED).decorate(TextDecoration.BOLD));
        if (this.index < this.plugins.size()) {
            renderPlugin(printed);
        } else if (this.index < summaryScreen()) {
            renderFileGroup(printed, this.index - this.plugins.size());
        } else {
            renderSummary(printed);
        }
        say("Lost in the chat? /gitsync prompt show prints this again.", NamedTextColor.DARK_GRAY);
    }

    private void renderPlugin(int printed) {
        PluginDraft draft = this.plugins.get(this.index);

        header(printed, this.editing ? draft.name + " (in the pack)"
            : draft.name + " (" + (this.index + 1) + " of " + this.plugins.size() + ")");
        if (!this.editing) {
            this.viewer.sendMessage(field("Jar", draft.jar));
        }
        this.viewer.sendMessage(Component.empty()
            .append(field("Wildcard", draft.wildcard))
            .append(Component.space())
            .append(button("[edit]", NamedTextColor.YELLOW, "Type a different wildcard for this jar")
                .clickEvent(ClickEvent.suggestCommand(INPUT_COMMAND + "wildcard " + draft.wildcard))));

        list(draft.configPaths, "Config paths", "config", "config path", printed, true);
        list(draft.reloadCommands, "Reload commands", "reload", "reload command", printed, false);

        this.viewer.sendMessage(Component.empty());
        if (this.editing) {
            // Where its files go is not asked: the ones it already had keep the layer they sit in,
            // and a config path added here brings its files to the next /gitsync pushupdate
            this.viewer.sendMessage(Component.empty()
                .append(button("[save]", NamedTextColor.GREEN, "Write the entry into the local pack.json, pushed by the next pushupdate")
                    .decorate(TextDecoration.BOLD)
                    .clickEvent(ClickEvent.callback(audience -> click(audience, printed, this::saveEntry))))
                .append(Component.space()).append(cancelButton(printed)));
            return;
        }
        // The layer is not asked for here: it belongs with every other placement, on one screen
        // where a plugin can be put wherever the files around it are going
        this.viewer.sendMessage(Component.empty()
            .append(button("[submit]", NamedTextColor.GREEN, "Put " + draft.name + " in the pack, layer picked later")
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.callback(audience -> click(audience, printed, () -> {
                    draft.submitted = true;
                    draft.ignored = false;
                    advance();
                }))))
            .append(Component.space()).append(ignoreButton(draft, printed))
            .append(Component.space()).append(cancelButton(printed)));
    }

    /**
     * A file the pack has never rendered here could belong anywhere, and the renderer's guess is
     * the role layer - which would hand a file meant for this one server to every server like it.
     * One screen per plugin, since its files usually all belong in the same place, so the top row
     * settles the lot of them and the rows underneath are only for the odd one out.
     */
    private void renderFileGroup(int printed, int group) {
        List<FileGroup> groups = groups();
        FileGroup files = groups.get(group);
        long placed = files.drafts.stream().filter(FileDraft::answered).count();
        // A plugin like ItemsAdder brings thousands of files, and a row each would push the top
        // row - the one that settles all of them at once - clean out of the chat
        int pages = (files.drafts.size() + FILES_PER_PAGE - 1) / FILES_PER_PAGE;
        this.page = Math.max(0, Math.min(this.page, pages - 1));

        header(printed, files.owner + " files (" + (group + 1) + " of " + groups.size() + ")");
        say("Pick where they go - " + placed + " of " + files.drafts.size() + " placed.",
            NamedTextColor.GRAY);

        this.viewer.sendMessage(Component.text("All of them: ").color(NamedTextColor.AQUA)
            .append(layerRow(printed, files.commonLayer(),
                "Publish every file of " + files.owner + " in ",
                layer -> files.drafts.forEach(draft -> draft.layer = layer))));

        int from = this.page * FILES_PER_PAGE;
        int to = Math.min(from + FILES_PER_PAGE, files.drafts.size());
        for (FileDraft draft : files.drafts.subList(from, to)) {
            this.viewer.sendMessage(Component.empty()
                .append(Component.text("  " + draft.path).color(NamedTextColor.WHITE))
                .append(Component.text(draft.answered() ? " -> " + draft.layer : " -> ?").color(NamedTextColor.GRAY)));
            this.viewer.sendMessage(Component.text("    ")
                .append(layerRow(printed, draft.layer, "Publish " + draft.path + " in ",
                    layer -> draft.layer = layer)));
        }
        if (pages > 1) {
            this.viewer.sendMessage(Component.empty()
                .append(pageButton("«", this.page - 1, this.page > 0, printed))
                .append(Component.text(" files " + (from + 1) + "-" + to + " of " + files.drafts.size()
                    + ", the row above settles them all at once ").color(NamedTextColor.GRAY))
                .append(pageButton("»", this.page + 1, this.page + 1 < pages, printed)));
        }

        this.viewer.sendMessage(Component.empty());
        this.viewer.sendMessage(Component.empty()
            .append(button("[submit]", NamedTextColor.GREEN, "Publish these files where they are placed")
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.callback(audience -> click(audience, printed, () -> {
                    // Moving on with a file still unplaced would publish it wherever the renderer guessed
                    if (!files.answered()) {
                        // Which page it is on is not worth hunting for by hand
                        this.page = firstUnplaced(files) / FILES_PER_PAGE;
                        say("Every file needs a layer first, the ones below are still open.", NamedTextColor.RED);
                        render();
                        return;
                    }
                    advance();
                }))))
            .append(Component.space()).append(cancelButton(printed)));
    }

    private int firstUnplaced(FileGroup files) {
        for (int i = 0; i < files.drafts.size(); i++) {
            if (!files.drafts.get(i).answered()) {
                return i;
            }
        }
        return 0;
    }

    private Component pageButton(String label, int target, boolean enabled, int printed) {
        if (!enabled) {
            return Component.text(label).color(NamedTextColor.DARK_GRAY);
        }
        return button(label, NamedTextColor.AQUA, "More files")
            .clickEvent(ClickEvent.callback(audience -> click(audience, printed, () -> {
                this.page = target;
                render();
            })));
    }

    /** One button per layer, the one already picked in bold. */
    private Component layerRow(int printed, String picked, String hover, java.util.function.Consumer<String> pick) {
        Component row = Component.empty();
        for (String layer : renderer().layers()) {
            row = row.append(layerButton(layer, picked, chosen -> {
                pick.accept(chosen);
                render();
            }, hover, printed)).append(Component.space());
        }
        return row;
    }

    /** Everything is answered, so this is the last look before anything is written. */
    private void renderSummary(int printed) {
        say("--- Ready to publish ---", NamedTextColor.GOLD);

        if (!this.plugins.isEmpty()) {
            say("New plugins:", NamedTextColor.GRAY);
            for (PluginDraft draft : this.plugins) {
                this.viewer.sendMessage(Component.text("  " + draft.name + " ").color(NamedTextColor.WHITE)
                    .append(Component.text("-> " + placementOf(draft)).color(NamedTextColor.GRAY)));
                if (!draft.ignored) {
                    say("    configs: " + summarize(draft.configPaths), NamedTextColor.DARK_GRAY);
                    say("    reloads: " + summarize(draft.reloadCommands), NamedTextColor.DARK_GRAY);
                }
            }
        }

        List<FileGroup> groups = groups();
        if (groups.isEmpty() && this.tracked.isEmpty()) {
            say("No file on this server differs from the pack.", NamedTextColor.GRAY);
        } else {
            say("Files:", NamedTextColor.GRAY);
            // A plugin bringing thousands of files would otherwise bury the confirm button under
            // a list nobody can scroll back through anyway
            int total = groups.stream().mapToInt(group -> group.drafts.size()).sum() + this.tracked.size();
            int left = SUMMARY_LINES;
            for (FileGroup group : groups) {
                for (FileDraft draft : group.drafts) {
                    if (left-- > 0) {
                        fileLine(PackRenderer.Kind.NEW, draft.path, draft.layer);
                    }
                }
            }
            for (PackRenderer.LocalChange change : this.tracked) {
                if (left-- > 0) {
                    fileLine(change.kind(), change.logicalPath(), change.targetLayer());
                }
            }
            if (total > SUMMARY_LINES) {
                say("  ... and " + (total - SUMMARY_LINES) + " more, /gitsync git status lists every one.",
                    NamedTextColor.GRAY);
            }
        }

        this.viewer.sendMessage(Component.empty());
        this.viewer.sendMessage(Component.empty()
            .append(arrow("«", this.index - 1, this.index > 0, printed))
            .append(Component.space())
            .append(button("[confirm and push]", NamedTextColor.GREEN, "Commit everything above and push it")
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.callback(audience -> click(audience, printed, this::confirm))))
            .append(Component.space())
            .append(cancelButton(printed)));
    }

    /** The same shape /gitsync git status prints, so the two read alike. */
    private void fileLine(PackRenderer.Kind kind, String path, String layer) {
        this.viewer.sendMessage(Component.text("  " + prefixOf(kind)).color(colorOfKind(kind))
            .append(Component.text(path).color(NamedTextColor.WHITE))
            .append(Component.text(" -> " + layer).color(NamedTextColor.GRAY)));
    }

    /** Only the local pack.json is written, so nothing reaches the remote until a pushupdate. */
    private void saveEntry() {
        PluginDraft draft = this.plugins.get(0);
        PackManifest.Entry entry = new PackManifest.Entry();
        entry.pluginJarWildcard = draft.wildcard;
        entry.configPaths = new ArrayList<>(draft.configPaths);
        entry.reloadCommands = new ArrayList<>(draft.reloadCommands);

        drop();
        editor.edit(this.viewer, draft.name, entry);
    }

    private void confirm() {
        drop();

        // Held back until now, so a cancelled session never leaves a jar marked private
        DataConfiguration data = GitSyncPlugin.instance().dataConfiguration();
        List<GitSyncService.NewPlugin> answers = new ArrayList<>();
        boolean ignoredAny = false;
        for (PluginDraft draft : this.plugins) {
            if (!draft.ignored) {
                // The jar leads its own group, so its layer is the one anything else the plugin
                // drags in falls back to
                answers.add(new GitSyncService.NewPlugin(draft.jar, draft.wildcard, layerOf(draft),
                    List.copyOf(draft.configPaths), List.copyOf(draft.reloadCommands)));
                continue;
            }
            String wildcard = PackRenderer.deriveWildcard(draft.jar);
            if (!data.ignoredPluginWildcards.contains(wildcard)) {
                data.ignoredPluginWildcards.add(wildcard);
                ignoredAny = true;
            }
        }
        if (ignoredAny) {
            data.save();
        }

        Map<String, String> fileLayers = new LinkedHashMap<>();
        for (FileGroup group : groups()) {
            group.drafts.forEach(draft -> fileLayers.put(draft.path, draft.layer));
        }
        publisher.publish(this.viewer, this.message, this.confirmed, answers, fileLayers);
    }

    private void header(int printed, String title) {
        // Started from an empty component: a child inherits the click of whatever it is appended
        // to, so hanging the title off the arrow would make the title itself a button
        this.viewer.sendMessage(Component.empty()
            .append(arrow("«", this.index - 1, this.index > 0, printed))
            .append(Component.text(" " + title + " ").color(NamedTextColor.GOLD))
            .append(arrow("»", this.index + 1, canLeaveForward(), printed)));
    }

    private void list(List<String> values, String title, String field, String what, int printed, boolean paths) {
        say(title + ":", NamedTextColor.GRAY);
        for (int i = 0; i < values.size(); i++) {
            int removed = i;
            // A path with nothing behind it publishes nothing, and a typo looks exactly like this
            Component line = paths && !exists(values.get(i))
                ? button("!", NamedTextColor.RED, "Nothing sits at this path on this server")
                    .decorate(TextDecoration.BOLD).append(Component.space())
                : Component.text("  ");
            this.viewer.sendMessage(Component.empty()
                .append(line)
                .append(Component.text(values.get(i)).color(NamedTextColor.WHITE))
                .append(Component.space())
                .append(button("[-]", NamedTextColor.RED, "Drop " + values.get(i))
                    .clickEvent(ClickEvent.callback(audience -> click(audience, printed, () -> {
                        if (removed < values.size()) {
                            values.remove(removed);
                        }
                        render();
                    })))));
        }
        this.viewer.sendMessage(Component.text("  ")
            .append(button("[+ add " + what + "]", NamedTextColor.GREEN, "Type the " + what + " to add")
                .clickEvent(ClickEvent.suggestCommand(INPUT_COMMAND + field + " "))));
    }

    /** Walking between the screens changes nothing, so the answers survive going back and forth. */
    private Component arrow(String label, int target, boolean enabled, int printed) {
        if (!enabled) {
            return Component.text(label).color(NamedTextColor.DARK_GRAY);
        }
        return button(label, NamedTextColor.AQUA, "To " + titleOf(target))
            .clickEvent(ClickEvent.callback(audience -> click(audience, printed, () -> {
                // A config path typed in since this line was printed can change what the screens are
                refreshPluginGroups();
                goTo(target);
            })));
    }

    /** What sits on a screen, so an arrow says where it leads rather than which way it points. */
    private String titleOf(int screen) {
        if (screen < this.plugins.size()) {
            return this.plugins.get(screen).name;
        }
        return screen < summaryScreen()
            ? groups().get(screen - this.plugins.size()).owner + " files"
            : "the summary";
    }

    /** A jar just submitted first, then whatever the pack already owned. */
    private List<FileGroup> groups() {
        List<FileGroup> groups = new ArrayList<>(this.pluginGroups);
        groups.addAll(this.fileGroups);
        return groups;
    }

    /**
     * A jar joining the pack brings its own files along, so it gets the very screen a plugin
     * already in the pack gets. Rebuilt rather than kept, because the config paths typed in on the
     * screen before are what decide which files those are - and a layer already picked survives it.
     */
    private void refreshPluginGroups() {
        Map<String, String> picked = new LinkedHashMap<>();
        this.pluginGroups.forEach(group -> group.drafts.forEach(draft -> picked.put(draft.path, draft.layer)));
        this.pluginGroups.clear();

        for (PluginDraft plugin : this.plugins) {
            if (!plugin.submitted) {
                continue;
            }
            List<String> paths = new ArrayList<>();
            paths.add(plugin.jar);
            try {
                paths.addAll(renderer().filesUnder(plugin.configPaths));
            } catch (IOException exception) {
                say("Could not list the files of " + plugin.name + ", only its jar is placed here.",
                    NamedTextColor.RED);
            }

            List<FileDraft> drafts = new ArrayList<>();
            for (String path : paths.stream().distinct().toList()) {
                FileDraft draft = new FileDraft(path);
                draft.layer = picked.get(path);
                drafts.add(draft);
            }
            this.pluginGroups.add(new FileGroup(plugin.name, drafts));
        }
    }

    /** The summary is only worth reaching once every question behind it has an answer. */
    private boolean canLeaveForward() {
        return this.index + 1 < summaryScreen() || answeredEverything();
    }

    private boolean answeredEverything() {
        return this.plugins.stream().allMatch(PluginDraft::answered)
            && groups().stream().allMatch(FileGroup::answered);
    }

    private int summaryScreen() {
        return this.plugins.size() + this.pluginGroups.size() + this.fileGroups.size();
    }

    /** Picking a layer is the answer, and the one already picked is spelled out in bold. */
    private Component layerButton(String layer, String picked, java.util.function.Consumer<String> pick,
                                  String hover, int printed) {
        Component button = button("[" + layer + "]", colorOf(layer), hover + layer + " - " + meaningOf(layer))
            .clickEvent(ClickEvent.callback(audience -> click(audience, printed, () -> pick.accept(layer))));
        return layer.equals(picked) ? button.decorate(TextDecoration.BOLD) : button;
    }

    private Component ignoreButton(PluginDraft draft, int printed) {
        String wildcard = PackRenderer.deriveWildcard(draft.jar);
        return button("[keep private]", NamedTextColor.DARK_GRAY,
            "Never ask about " + wildcard + " on this server again")
            .clickEvent(ClickEvent.callback(audience -> click(audience, printed, () -> {
                draft.ignored = true;
                draft.submitted = false;
                advance();
            })));
    }

    private Component cancelButton(int printed) {
        return button("[cancel]", NamedTextColor.RED, "Drop the whole thing, nothing is committed")
            .clickEvent(ClickEvent.callback(audience -> click(audience, printed, () -> cancel(this.viewer))));
    }

    /** On to the first screen still holding a question, and to the summary when none is left. */
    private void advance() {
        refreshPluginGroups();
        for (int i = 0; i < this.plugins.size(); i++) {
            if (!this.plugins.get(i).answered()) {
                goTo(i);
                return;
            }
        }
        List<FileGroup> groups = groups();
        for (int i = 0; i < groups.size(); i++) {
            if (!groups.get(i).answered()) {
                goTo(this.plugins.size() + i);
                return;
            }
        }
        goTo(summaryScreen());
    }

    /** The screens a submitted jar adds shift as it is submitted, so the target is kept in range. */
    private void goTo(int screen) {
        this.index = Math.min(screen, summaryScreen());
        this.page = 0;
        render();
    }

    /** The chat keeps every version of the block, only the buttons of the newest one still work. */
    private void click(Audience audience, int printed, Runnable action) {
        // Whoever clicked is who the next screen goes to, the session itself belongs to everyone
        if (audience instanceof Player player) {
            this.viewer = player;
        }
        if (!alive()) {
            return;
        }
        if (printed != this.generation) {
            say("That line is out of date, use the newest one below it.", NamedTextColor.RED);
            return;
        }
        action.run();
    }

    /** A path that was refused on the way in cannot escape, so this only ever asks about plugins/. */
    private boolean exists(String path) {
        return Files.exists(renderer().pluginsFile(path));
    }

    private String summarize(List<String> values) {
        return values.isEmpty() ? "none" : String.join(", ", values);
    }

    private String placementOf(PluginDraft draft) {
        return draft.ignored ? "kept private" : layerOf(draft);
    }

    /** Where the jar itself was placed, which is the first row of the plugin's own file screen. */
    private String layerOf(PluginDraft draft) {
        return this.pluginGroups.stream()
            .filter(group -> group.owner.equals(draft.name))
            .findFirst()
            .map(group -> group.drafts.get(0).layer)
            .orElse(renderer().defaultLayer());
    }

    private String prefixOf(PackRenderer.Kind kind) {
        return switch (kind) {
            case NEW -> "[A] ";
            case MODIFIED -> "[M] ";
            case DELETED -> "[D] ";
        };
    }

    private NamedTextColor colorOfKind(PackRenderer.Kind kind) {
        return switch (kind) {
            case NEW -> NamedTextColor.GREEN;
            case MODIFIED -> NamedTextColor.YELLOW;
            case DELETED -> NamedTextColor.RED;
        };
    }

    private Component button(String label, TextColor color, String hover) {
        return Component.text(label).color(color).hoverEvent(HoverEvent.showText(Component.text(hover)));
    }

    private Component field(String name, String value) {
        return Component.text(name + ": ").color(NamedTextColor.GRAY)
            .append(Component.text(value).color(NamedTextColor.WHITE));
    }

    /** A colour per layer, so how far something is about to reach is visible before the hover. */
    private TextColor colorOf(String layer) {
        if (layer.equals("base")) {
            return NamedTextColor.GREEN;
        }
        return layer.startsWith("role/") ? NamedTextColor.GOLD : NamedTextColor.LIGHT_PURPLE;
    }

    private String meaningOf(String layer) {
        if (layer.equals("base")) {
            return "every server in the network";
        }
        return layer.startsWith("role/") ? "every " + layer.substring(5) + " server" : "this server only";
    }

    private void say(String message, NamedTextColor color) {
        this.viewer.sendMessage(Component.text(message).color(color));
    }

    private PackRenderer renderer() {
        return service().renderer();
    }

    private GitSyncService service() {
        return GitSyncPlugin.instance().gitSyncService();
    }

    /** One jar as it stands, kept per plugin so the arrows have something to walk back into. */
    private static final class PluginDraft {
        /** Null for an entry already in the pack, which is edited without the jar in hand. */
        private final String jar;
        /** What the plugin calls itself, as opposed to what its file is called. */
        private final String name;
        private final List<String> configPaths = new ArrayList<>();
        private final List<String> reloadCommands = new ArrayList<>();
        private String wildcard;
        /** Put in the pack - where it goes is settled on its own file screen, further along. */
        private boolean submitted;
        /** Answered by keeping the jar to this server instead of putting it in the pack. */
        private boolean ignored;

        private PluginDraft(String jar, String name, String wildcard) {
            this.jar = jar;
            this.name = name;
            this.wildcard = wildcard;
        }

        private boolean answered() {
            return this.submitted || this.ignored;
        }
    }

    /** The new files of one plugin, which is what a screen shows and the top row settles at once. */
    private static final class FileGroup {
        private final String owner;
        private final List<FileDraft> drafts;

        private FileGroup(String owner, List<FileDraft> drafts) {
            this.owner = owner;
            this.drafts = drafts;
        }

        private boolean answered() {
            return this.drafts.stream().allMatch(FileDraft::answered);
        }

        /** The layer they all sit in, null when they disagree - so the top row shows it in bold. */
        private String commonLayer() {
            String layer = this.drafts.get(0).layer;
            return layer != null && this.drafts.stream().allMatch(draft -> layer.equals(draft.layer)) ? layer : null;
        }
    }

    /** A file the pack has never seen, waiting for the layer it belongs in. */
    private static final class FileDraft {
        private final String path;
        private String layer;

        private FileDraft(String path) {
            this.path = path;
        }

        private boolean answered() {
            return this.layer != null;
        }
    }
}
