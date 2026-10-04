package net.seanomik.tamablefoxes.versions.version_26_2_R1;

import net.seanomik.tamablefoxes.util.FoxNamePrompt;
import net.seanomik.tamablefoxes.util.Utils;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * Feeds chat messages into {@link FoxNamePrompt}. The listener lives in the version module so it
 * compiles against the chat event the server actually ships.
 */
public class FoxNameChatListener implements Listener {

    private static boolean registered;

    /**
     * Registered on the first rename prompt: the entity registration runs in onLoad, where the
     * plugin may not register listeners yet.
     */
    public static synchronized void register() {
        if (!registered) {
            Bukkit.getPluginManager().registerEvents(new FoxNameChatListener(), Utils.tamableFoxesPlugin);
            registered = true;
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (FoxNamePrompt.handleChat(event.getPlayer(), event.getMessage())) {
            event.setCancelled(true);
        }
    }
}
