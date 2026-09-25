package net.seanomik.tamablefoxes;

import net.seanomik.tamablefoxes.util.FoliaCompat;
import net.seanomik.tamablefoxes.util.io.Config;
import net.seanomik.tamablefoxes.util.io.LanguageConfig;
import org.bukkit.ChatColor;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Completes a pending /givefox request when the player right clicks one of their foxes.
 *
 * The swap happens on the thread this event is delivered on, which is the thread owning the
 * clicked fox - the only one allowed to change it, and on Folia that is not necessarily the
 * thread the command ran on.
 */
public class PlayerInteractEntityEventListener implements Listener {

    private static final class GiveFoxRequest {
        private final Player givingTo;

        GiveFoxRequest(Player givingTo) {
            this.givingTo = givingTo;
        }
    }

    private final TamableFoxes plugin;
    private final Map<UUID, GiveFoxRequest> requests;

    PlayerInteractEntityEventListener(TamableFoxes plugin) {
        this.plugin = plugin;
        this.requests = new ConcurrentHashMap<>();
    }

    /** Arms the next fox this player right clicks as the one to hand over. */
    void expectFoxChoice(Player player, Player givingTo) {
        requests.put(player.getUniqueId(), new GiveFoxRequest(givingTo));
    }

    @EventHandler
    public void onPlayerInteractEntityEvent(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Fox)) {
            return;
        }

        Player player = event.getPlayer();
        GiveFoxRequest request = requests.remove(player.getUniqueId());
        if (request == null) {
            return;
        }

        Fox fox = (Fox) event.getRightClicked();
        Player givingTo = request.givingTo;

        if (player.getUniqueId().equals(plugin.nmsInterface.getFoxOwner(fox)) ||
                player.hasPermission("tamablefoxes.givefox.give.others")) {
            plugin.nmsInterface.changeFoxOwner(fox, givingTo);
            player.sendMessage(Config.getPrefix() + ChatColor.GREEN + LanguageConfig.getGaveFox(givingTo));

            // The new owner names the fox, so the prompt runs on the thread owning them: on Folia
            // they may well be in another region than the fox.
            if (givingTo.isOnline()) {
                FoliaCompat.runOnEntity(givingTo, () -> plugin.nmsInterface.renameFox(fox, givingTo));
            }
        } else {
            player.sendMessage(Config.getPrefix() + ChatColor.RED + LanguageConfig.getNotYourFox());
        }
    }
}
