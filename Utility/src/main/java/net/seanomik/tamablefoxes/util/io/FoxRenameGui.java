package net.seanomik.tamablefoxes.util.io;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
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
 * The typed text is read reflectively because this module is compiled against the 1.14 API, which
 * has no {@code AnvilInventory#getRenameText} yet.
 */
public class FoxRenameGui implements Listener {

    private static final int ANVIL_OUTPUT_SLOT = 2;

    private static final Method GET_RENAME_TEXT = findRenameText();
    private static final Map<UUID, Consumer<String>> PENDING = new ConcurrentHashMap<>();
    private static final FoxRenameGui LISTENER = new FoxRenameGui();
    private static boolean registered;

    private static Method findRenameText() {
        try {
            return Class.forName("org.bukkit.inventory.AnvilInventory").getMethod("getRenameText");
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            return null;
        }
    }

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

        PENDING.put(player.getUniqueId(), onRename);
        player.openInventory(anvil);
    }

    /**
     * Built reflectively: this module is compiled against the 1.14 API, where the inventory type
     * lives in another package, and the title overload of the factory differs between versions
     * (newer servers take an adventure Component, older ones a plain String).
     */
    private static Inventory createAnvil() {
        Class<?> inventoryType;
        Object anvil;
        try {
            inventoryType = Class.forName("org.bukkit.inventory.InventoryType");
            anvil = inventoryType.getField("ANVIL").get(null);
        } catch (ReflectiveOperationException e) {
            Bukkit.getLogger().warning("TamableFoxes: could not resolve the anvil inventory type: " + e);
            return null;
        }

        Class<?> component = optionalClass("net.kyori.adventure.text.Component");
        if (component != null) {
            try {
                Object title = component.getMethod("text", String.class).invoke(null, "Name your new friend!");
                Inventory inventory = (Inventory) Bukkit.class
                        .getMethod("createInventory", InventoryHolder.class, inventoryType, component)
                        .invoke(null, null, anvil, title);
                if (inventory != null) {
                    return inventory;
                }
            } catch (ReflectiveOperationException ignored) {
                // Fall through to the String overload.
            }
        }

        try {
            return (Inventory) Bukkit.class
                    .getMethod("createInventory", InventoryHolder.class, inventoryType, String.class)
                    .invoke(null, null, anvil, "Name your new friend!");
        } catch (ReflectiveOperationException ignored) {
            // Fall through to the overload without a title.
        }

        try {
            return (Inventory) Bukkit.class
                    .getMethod("createInventory", InventoryHolder.class, inventoryType)
                    .invoke(null, null, anvil);
        } catch (ReflectiveOperationException e) {
            Bukkit.getLogger().warning("TamableFoxes: could not create the anvil inventory: " + e);
            return null;
        }
    }

    private static Class<?> optionalClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
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
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() != ANVIL_OUTPUT_SLOT) {
            return;
        }

        if (!"ANVIL".equals(String.valueOf(event.getView().getTopInventory().getType()))) {
            return;
        }

        Consumer<String> onRename = PENDING.remove(player.getUniqueId());
        if (onRename == null) {
            return;
        }

        // The vanilla anvil would hand the renamed item over; we only want the text.
        event.setCancelled(true);
        player.closeInventory();
        onRename.accept(renameText(event.getView().getTopInventory()));
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        Consumer<String> onRename = PENDING.remove(player.getUniqueId());
        if (onRename != null) {
            onRename.accept("");
        }
    }

    private static String renameText(Inventory anvil) {
        if (GET_RENAME_TEXT == null) {
            return "";
        }

        try {
            Object text = GET_RENAME_TEXT.invoke(anvil);
            return text == null ? "" : text.toString();
        } catch (ReflectiveOperationException e) {
            return "";
        }
    }
}
