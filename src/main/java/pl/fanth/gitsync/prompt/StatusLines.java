package pl.fanth.gitsync.prompt;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import pl.fanth.gitsync.git.PackRenderer;

/** The file lines /git status and the pushupdate summary share, so the two read alike. */
public final class StatusLines {
    private static final ClickCallback.Options UNLIMITED =
        ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).build();

    private StatusLines() {
    }

    /** "[stage all] [unstage all]", meant for above the file list. */
    public static Component allRow(ClickCallback<Audience> stageAll, ClickCallback<Audience> unstageAll) {
        return Component.empty()
            .append(Component.text("[stage all]").color(NamedTextColor.GREEN)
                .hoverEvent(HoverEvent.showText(Component.text("Publish every file below with the next push")))
                .clickEvent(ClickEvent.callback(stageAll, UNLIMITED)))
            .append(Component.space())
            .append(Component.text("[unstage all]").color(NamedTextColor.RED)
                .hoverEvent(HoverEvent.showText(Component.text("Leave every file below out of the next push")))
                .clickEvent(ClickEvent.callback(unstageAll, UNLIMITED)));
    }

    /** "[+][-] [M] path -> layer", the state the file is in already shown in bold. */
    public static Component line(PackRenderer.Kind kind, String path, String layer, boolean staged,
                                 ClickCallback<Audience> stage, ClickCallback<Audience> unstage) {
        return Component.empty()
            .append(toggle("[+]", NamedTextColor.GREEN, staged, "Stage " + path, stage))
            .append(toggle("[-]", NamedTextColor.RED, !staged, "Unstage " + path, unstage))
            .append(Component.text(" " + prefixOf(kind)).color(staged ? colorOf(kind) : NamedTextColor.DARK_GRAY))
            .append(Component.text(path).color(staged ? NamedTextColor.WHITE : NamedTextColor.GRAY))
            .append(Component.text(" -> " + layer).color(NamedTextColor.GRAY));
    }

    private static Component toggle(String label, NamedTextColor color, boolean active, String hover,
                                    ClickCallback<Audience> action) {
        return Component.text(label)
            .color(active ? color : NamedTextColor.DARK_GRAY)
            .decoration(TextDecoration.BOLD, active)
            .hoverEvent(HoverEvent.showText(Component.text(hover)))
            .clickEvent(ClickEvent.callback(action, UNLIMITED));
    }

    public static String prefixOf(PackRenderer.Kind kind) {
        return switch (kind) {
            case NEW -> "[A] ";
            case MODIFIED -> "[M] ";
            case DELETED -> "[D] ";
        };
    }

    public static NamedTextColor colorOf(PackRenderer.Kind kind) {
        return switch (kind) {
            case NEW -> NamedTextColor.GREEN;
            case MODIFIED -> NamedTextColor.YELLOW;
            case DELETED -> NamedTextColor.RED;
        };
    }
}
