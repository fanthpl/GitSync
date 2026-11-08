package pl.fanth.gitsync.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import pl.fanth.gitsync.GitSyncPlugin;
import pl.fanth.gitsync.git.GitSyncService;

public class PlayerListener implements Listener {

    /** Warn admins about a broken sync or a pending restart, delayed so it is not buried by the join spam. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("gitsync.notify")) {
            return;
        }

        Bukkit.getScheduler().runTaskLater(GitSyncPlugin.instance(), () -> {
            if (!player.isOnline()) {
                return;
            }
            GitSyncService service = GitSyncPlugin.instance().gitSyncService();
            if (service.lastSyncFailed()) {
                player.sendMessage(Component.text("[GitSync] The last sync failed. Try syncing manually using '/gitsync sync'.")
                    .color(NamedTextColor.RED));
                player.playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0F, 0.5F);
            }
            if (!service.restartReasons().isEmpty()) {
                // A different sound than the failure above, so the two are told apart without reading
                player.sendMessage(Component.text("[GitSync] The server needs a restart to apply the synced changes. "
                        + "Click for the details.")
                    .color(NamedTextColor.YELLOW)
                    .hoverEvent(HoverEvent.showText(Component.text("/gitsync info")))
                    .clickEvent(ClickEvent.runCommand("/gitsync info")));
                player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0F, 1.0F);
            }
        }, 60L);
    }


}
