package net.seanomik.tamablefoxes.util.io;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Asks a player for a name through a plain anvil inventory.
 *
 * AnvilGUI cannot be used on 26.x: it picks its wrapper by probing for the spigot class names, and
 * 26.x no longer uses those, so merely touching the class throws there.
 *
 * The typed name is read from the renamed output item rather than through
 * {@code AnvilInventory#getRenameText}: this module is compiled against the 1.14 API and the anvil
 * inventory class has moved between versions, so the rename text is not reliably reachable. The
 * anvil itself is tracked by identity for the same reason - the inventory type enum cannot be
 * named from this api level either.
 */
public class FoxRenameGui implements Listener {

    private static final int ANVIL_OUTPUT_SLOT = 2;

    private static final class Pending {
        private final Inventory anvil;
        private final Consumer<String> onRename;

        private Pending(Inventory anvil, Consumer<String> onRename) {
            this.anvil = anvil;
            this.onRename = onRename;
        }
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static final FoxRenameGui LISTENER = new FoxRenameGui();
    private static boolean registered;

    private FoxRenameGui() {
    }

    /**
     * Opens the prompt for the player. The callback runs with the typed name, or with an empty
     * string when the player closed the anvil without naming anything.
     */
    public static void open(Plugin plugin, Player player, Consumer<String> onRename) {
        register(plugin);

        Inventory anvil = createAnvil();
        if (anvil == null) {
            onRename.accept("");
            return;
        }

        // An anvil with empty slots has nothing to confirm, so the player gets a piece of paper
        // whose name they edit.
        anvil.setItem(0, new ItemStack(Material.PAPER));

        PENDING.put(player.getUniqueId(), new Pending(anvil, onRename));
        player.openInventory(anvil);
    }

    /**
     * Built reflectively and without naming any API class: this module is compiled against the 1.14
     * API, and later versions moved the inventory type to another package - 26.x does not even have
     * the class the newer API uses. So the type is taken from the factory method that accepts it.
     */
    private static Inventory createAnvil() {
        Method plain = null;
        Method titled = null;

        for (Method method : Bukkit.class.getMethods()) {
            if (!method.getName().equals("createInventory") || method.getParameterCount() < 2
                    || method.getParameterTypes()[0] != InventoryHolder.class
                    || !method.getParameterTypes()[1].isEnum()) {
                continue;
            }

            if (method.getParameterCount() == 2) {
                plain = method;
            } else if (method.getParameterCount() == 3 && method.getParameterTypes()[2] == String.class) {
                titled = method;
            }
        }

        Inventory inventory = titled == null ? null : anvilOf(titled, "Name your new friend!");
        if (inventory == null && plain != null) {
            inventory = anvilOf(plain);
        }

        if (inventory == null) {
            Bukkit.getLogger().warning("TamableFoxes: no usable Bukkit#createInventory overload for an anvil was found");
        }

        return inventory;
    }

    private static Inventory anvilOf(Method create, Object... title) {
        try {
            Object anvil = create.getParameterTypes()[1].getField("ANVIL").get(null);
            Object[] arguments = new Object[create.getParameterCount()];
            arguments[0] = null;
            arguments[1] = anvil;
            for (int i = 0; i < title.length; i++) {
                arguments[2 + i] = title[i];
            }

            return (Inventory) create.invoke(null, arguments);
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            return null;
        }
    }

    private static synchronized void register(Plugin plugin) {
        if (!registered) {
            Bukkit.getPluginManager().registerEvents(LISTENER, plugin);
            registered = true;
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Pending pending = PENDING.get(player.getUniqueId());
        if (pending == null || event.getInventory() != pending.anvil) {
            return;
        }

        // Nothing may leave or enter this anvil; it only exists to collect the name.
        event.setCancelled(true);

        if (event.getRawSlot() != ANVIL_OUTPUT_SLOT) {
            return;
        }

        // The output slot holds the paper renamed to whatever the player typed, so the name comes
        // from the item itself. An empty output means nothing was typed yet - stay open.
        String text = displayName(event.getCurrentItem());
        if (text.isEmpty()) {
            return;
        }

        PENDING.remove(player.getUniqueId());
        player.closeInventory();
        pending.onRename.accept(text);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        Pending pending = PENDING.remove(player.getUniqueId());
        if (pending != null) {
            pending.onRename.accept("");
        }
    }

    private static String displayName(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) {
            return "";
        }

        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName() ? meta.getDisplayName() : "";
    }
}
