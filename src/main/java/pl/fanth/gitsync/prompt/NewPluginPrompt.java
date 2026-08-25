package pl.fanth.gitsync.prompt;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import pl.fanth.gitsync.GitSyncPlugin;
import pl.fanth.gitsync.config.DataConfiguration;
import pl.fanth.gitsync.git.GitSyncService;
import pl.fanth.gitsync.git.PackManifest;
import pl.fanth.gitsync.git.PackRenderer;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Asks in chat where every jar that no pack entry claims belongs, one plugin at a time.
 * <p>
 * A jar nobody declared cannot be placed for the admin: base is every server, the role layer is
 * every server of this kind, and the instance layer is this one alone - and only whoever put the
 * jar there knows which of its files are config and how it reloads. So the entry is printed as it
 * stands, every value with a [-] beside it and a [+] under each list, and reprinted after every
 * click. The arrows walk between the jars, and once all of them are answered the summary is what
 * finally commits.
 * <p>
 * Nothing is written anywhere until confirm is clicked, so cancelling or walking away leaves the
 * pack and this server exactly as they were.
 */
public class NewPluginPrompt {
    /** Only one session at a time, two of them would race each other into the same commit. */
    private static final Map<UUID, NewPluginPrompt> ACTIVE = new ConcurrentHashMap<>();
    /** Adding a value needs it typed out, and a click can only put a command in the chat box. */
    private static final String INPUT_COMMAND = "/gitsync newplugin ";
    /** A session holds everyone else off, so one that is walked away from cannot hold forever. */
    private static final int TIMEOUT_MINUTES = 10;

    private final Player player;
    private final List<Draft> drafts = new ArrayList<>();
    private final Consumer<List<GitSyncService.NewPlugin>> onFinished;

    /** Which jar is on screen, or drafts.size() for the summary. */
    private int index;
    /** Set by the constructor off the main thread, read by whoever tries to commit next. */
    private volatile long touched = System.currentTimeMillis();
    /** Bumped by every print, so the buttons still sitting further up the chat go quiet. */
    private int generation;

    public NewPluginPrompt(Player player, List<String> jars, Consumer<List<GitSyncService.NewPlugin>> onFinished) {
        this.player = player;
        this.onFinished = onFinished;
        for (String jar : jars) {
            this.drafts.add(new Draft(jar, service().pluginName(jar), PackRenderer.deriveWildcard(jar)));
        }
    }

    public void start() {
        ACTIVE.put(this.player.getUniqueId(), this);
        render();
    }

    /** Who is placing plugins right now, null when nobody is. A second commit has to wait. */
    public static String busyWith() {
        ACTIVE.values().removeIf(NewPluginPrompt::expired);
        return ACTIVE.values().stream().findFirst().map(prompt -> prompt.player.getName()).orElse(null);
    }

    /** A value the admin typed after clicking one of the [+] buttons. */
    public static void accept(Player player, String field, String value) {
        NewPluginPrompt prompt = of(player);
        if (prompt != null) {
            prompt.set(field, value.trim());
        }
    }

    /** The chat is short and this block is long, so it can always be asked for again. */
    public static void show(Player player) {
        NewPluginPrompt prompt = of(player);
        if (prompt != null) {
            prompt.render();
        }
    }

    /** Nothing has been written yet, so dropping the session is the whole of cancelling it. */
    public static void cancel(Player player) {
        if (ACTIVE.remove(player.getUniqueId()) == null) {
            player.sendMessage(Component.text("You are not placing any plugins.").color(NamedTextColor.RED));
            return;
        }
        player.sendMessage(Component.text("Cancelled, nothing was committed.").color(NamedTextColor.YELLOW));
    }

    /** Nothing is committed halfway, so a prompt left behind is only worth forgetting. */
    public static void forget(Player player) {
        ACTIVE.remove(player.getUniqueId());
    }

    private static NewPluginPrompt of(Player player) {
        NewPluginPrompt prompt = ACTIVE.get(player.getUniqueId());
        if (prompt == null) {
            player.sendMessage(Component.text("No plugin is waiting to be placed, run /gitsync pushupdate first.")
                .color(NamedTextColor.RED));
            return null;
        }
        return prompt.alive() ? prompt : null;
    }

    private boolean expired() {
        return System.currentTimeMillis() - this.touched > TIMEOUT_MINUTES * 60_000L;
    }

    /** Anything the admin does keeps the session going, and the first thing after ten idle minutes ends it. */
    private boolean alive() {
        if (expired()) {
            ACTIVE.remove(this.player.getUniqueId());
            say("Your plugin placement timed out after " + TIMEOUT_MINUTES
                + " minutes, nothing was committed.", NamedTextColor.RED);
            return false;
        }
        this.touched = System.currentTimeMillis();
        return true;
    }

    private void set(String field, String value) {
        if (this.index == this.drafts.size()) {
            say("Every plugin is placed already, confirm or go back with the arrow.", NamedTextColor.RED);
            return;
        }

        Draft draft = current();
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
    private void addConfigPath(Draft draft, String value) {
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
    private void setWildcard(Draft draft, String value) {
        PackManifest.Entry probe = new PackManifest.Entry();
        probe.pluginJarWildcard = value;
        if (probe.matchesJar(draft.jar)) {
            draft.wildcard = value;
            return;
        }
        say(value + " does not match " + draft.jar + ", kept " + draft.wildcard + ".", NamedTextColor.RED);
    }

    private void render() {
        int printed = ++this.generation;

        this.player.sendMessage(Component.text("ATTENTION REQUIRED")
            .color(NamedTextColor.RED).decorate(TextDecoration.BOLD));
        if (this.index == this.drafts.size()) {
            renderSummary(printed);
        } else {
            renderPlugin(printed);
        }
        say("Lost in the chat? /gitsync newplugin show prints this again.", NamedTextColor.DARK_GRAY);
    }

    private void renderPlugin(int printed) {
        Draft draft = current();

        // Started from an empty component: a child inherits the click of whatever it is appended
        // to, so hanging the title off the arrow would make the title itself a button
        this.player.sendMessage(Component.empty()
            .append(arrow("«", this.index - 1, this.index > 0, printed))
            .append(Component.text(" " + draft.name + " (" + (this.index + 1) + " of " + this.drafts.size() + ") ")
                .color(NamedTextColor.GOLD))
            .append(arrow("»", this.index + 1, canLeaveForward(), printed)));
        this.player.sendMessage(field("Jar", draft.jar));
        this.player.sendMessage(field("Wildcard", draft.wildcard).append(Component.space())
            .append(button("[edit]", NamedTextColor.YELLOW, "Type a different wildcard for this jar")
                .clickEvent(ClickEvent.suggestCommand(INPUT_COMMAND + "wildcard " + draft.wildcard))));

        list(draft, "Config paths", draft.configPaths, "config", "config path", printed, true);
        list(draft, "Reload commands", draft.reloadCommands, "reload", "reload command", printed, false);

        this.player.sendMessage(Component.empty());
        say(draft.answered() ? "Submit (currently " + placementOf(draft) + "):" : "Submit (pick layer):",
            NamedTextColor.AQUA);

        Component buttons = Component.empty();
        for (String layer : renderer().layers()) {
            buttons = buttons.append(layerButton(draft, layer, printed)).append(Component.space());
        }
        this.player.sendMessage(buttons.append(ignoreButton(draft, printed))
            .append(Component.space()).append(cancelButton(printed)));
    }

    /** Every jar has an answer, so this is the last look before anything is written. */
    private void renderSummary(int printed) {
        say("--- Ready to publish (" + this.drafts.size() + " plugin(s)) ---", NamedTextColor.GOLD);
        for (Draft draft : this.drafts) {
            this.player.sendMessage(Component.text(draft.name + " ").color(NamedTextColor.WHITE)
                .append(Component.text("-> " + placementOf(draft)).color(NamedTextColor.GRAY)));
            if (draft.ignored) {
                continue;
            }
            say("  configs: " + summarize(draft.configPaths), NamedTextColor.DARK_GRAY);
            say("  reloads: " + summarize(draft.reloadCommands), NamedTextColor.DARK_GRAY);
        }

        this.player.sendMessage(Component.empty());
        this.player.sendMessage(Component.empty()
            .append(arrow("«", this.drafts.size() - 1, true, printed))
            .append(Component.space())
            .append(button("[confirm and push]", NamedTextColor.GREEN, "Commit every placement above and push it")
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.callback(audience -> click(printed, this::confirm))))
            .append(Component.space())
            .append(cancelButton(printed)));
    }

    private void confirm() {
        ACTIVE.remove(this.player.getUniqueId());

        // Held back until now, so a cancelled session never leaves a jar marked private
        DataConfiguration data = GitSyncPlugin.instance().dataConfiguration();
        List<GitSyncService.NewPlugin> answers = new ArrayList<>();
        boolean ignoredAny = false;
        for (Draft draft : this.drafts) {
            if (!draft.ignored) {
                answers.add(new GitSyncService.NewPlugin(draft.jar, draft.wildcard, draft.layer,
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
        this.onFinished.accept(answers);
    }

    private void list(Draft draft, String title, List<String> values, String field, String what,
                      int printed, boolean paths) {
        say(title + ":", NamedTextColor.GRAY);
        for (int i = 0; i < values.size(); i++) {
            int removed = i;
            // A path with nothing behind it publishes nothing, and a typo looks exactly like this
            Component line = paths && !exists(values.get(i))
                ? button("!", NamedTextColor.RED, "Nothing sits at this path on this server")
                    .decorate(TextDecoration.BOLD).append(Component.space())
                : Component.text("  ");
            this.player.sendMessage(Component.empty()
                .append(line)
                .append(Component.text(values.get(i)).color(NamedTextColor.WHITE))
                .append(Component.space())
                .append(button("[-]", NamedTextColor.RED, "Drop " + values.get(i))
                    .clickEvent(ClickEvent.callback(audience -> click(printed, () -> {
                        if (removed < values.size()) {
                            values.remove(removed);
                        }
                        render();
                    })))));
        }
        this.player.sendMessage(Component.text("  ")
            .append(button("[+ add " + what + "]", NamedTextColor.GREEN, "Type the " + what + " to add")
                .clickEvent(ClickEvent.suggestCommand(INPUT_COMMAND + field + " "))));
    }

    /** Walking between the jars changes nothing, so the answers survive going back and forth. */
    private Component arrow(String label, int target, boolean enabled, int printed) {
        if (!enabled) {
            return Component.text(label).color(NamedTextColor.DARK_GRAY);
        }
        return button(label, NamedTextColor.AQUA,
            target == this.drafts.size() ? "To the summary" : "To " + this.drafts.get(target).name)
            .clickEvent(ClickEvent.callback(audience -> click(printed, () -> {
                this.index = target;
                render();
            })));
    }

    /** The summary sits past the last jar, and it is only worth reaching once all of them answered. */
    private boolean canLeaveForward() {
        return this.index + 1 < this.drafts.size() || this.drafts.stream().allMatch(Draft::answered);
    }

    /** Picking the layer answers this jar and moves on to whatever is still unanswered. */
    private Component layerButton(Draft draft, String layer, int printed) {
        return button("[" + layer + "]", colorOf(layer),
            "Publish " + draft.jar + " in " + layer + " - " + meaningOf(layer)).decorate(TextDecoration.BOLD)
            .clickEvent(ClickEvent.callback(audience -> click(printed, () -> {
                draft.layer = layer;
                draft.ignored = false;
                advance();
            })));
    }

    private Component ignoreButton(Draft draft, int printed) {
        String wildcard = PackRenderer.deriveWildcard(draft.jar);
        return button("[keep private]", NamedTextColor.DARK_GRAY,
            "Never ask about " + wildcard + " on this server again")
            .clickEvent(ClickEvent.callback(audience -> click(printed, () -> {
                draft.ignored = true;
                draft.layer = null;
                advance();
            })));
    }

    private Component cancelButton(int printed) {
        return button("[cancel]", NamedTextColor.RED, "Drop the whole thing, nothing is committed")
            .clickEvent(ClickEvent.callback(audience -> click(printed, () -> cancel(this.player))));
    }

    /** On to the first jar still without an answer, or to the summary when there is none left. */
    private void advance() {
        for (int i = 0; i < this.drafts.size(); i++) {
            if (!this.drafts.get(i).answered()) {
                this.index = i;
                render();
                return;
            }
        }
        this.index = this.drafts.size();
        render();
    }

    /** The chat keeps every version of the block, only the buttons of the newest one still work. */
    private void click(int printed, Runnable action) {
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

    private String placementOf(Draft draft) {
        return draft.ignored ? "kept private" : draft.layer;
    }

    private Component button(String label, TextColor color, String hover) {
        return Component.text(label).color(color).hoverEvent(HoverEvent.showText(Component.text(hover)));
    }

    private Component field(String name, String value) {
        return Component.text(name + ": ").color(NamedTextColor.GRAY)
            .append(Component.text(value).color(NamedTextColor.WHITE));
    }

    /** A colour per layer, so how far a plugin is about to reach is visible before the hover. */
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

    private Draft current() {
        return this.drafts.get(this.index);
    }

    private void say(String message, NamedTextColor color) {
        this.player.sendMessage(Component.text(message).color(color));
    }

    private PackRenderer renderer() {
        return service().renderer();
    }

    private GitSyncService service() {
        return GitSyncPlugin.instance().gitSyncService();
    }

    /** One jar as it stands, kept per plugin so the arrows have something to walk back into. */
    private static final class Draft {
        private final String jar;
        /** What the plugin calls itself, as opposed to what its file is called. */
        private final String name;
        private final List<String> configPaths = new ArrayList<>();
        private final List<String> reloadCommands = new ArrayList<>();
        private String wildcard;
        /** The layer picked for it, null while it is unanswered or kept private. */
        private String layer;
        /** Answered by keeping the jar to this server instead of putting it in the pack. */
        private boolean ignored;

        private Draft(String jar, String name, String wildcard) {
            this.jar = jar;
            this.name = name;
            this.wildcard = wildcard;
        }

        private boolean answered() {
            return this.layer != null || this.ignored;
        }
    }
}
