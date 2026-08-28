package pl.fanth.gitsync.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import pl.fanth.gitsync.GitSyncPlugin;

public class PlayerListener implements Listener {

    /** Warn admins about a broken sync, delayed so the message is not buried by the join spam. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("gitsync.notify")) {
            return;
        }

        Bukkit.getScheduler().runTaskLater(GitSyncPlugin.instance(), () -> {
            if (!player.isOnline() || !GitSyncPlugin.instance().gitSyncService().lastSyncFailed()) {
                return;
            }
            player.sendMessage(Component.text("[GitSync] The last sync failed, see the server console for more information.")
                .color(NamedTextColor.RED));
            player.playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0F, 0.5F);
        }, 60L);
    }


}
