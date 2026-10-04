package net.seanomik.tamablefoxes.util;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Asks a player for a name through the chat: the next message they send within one minute becomes
 * the answer, while "cancel" and the timeout answer with an empty string.
 *
 * Only the bookkeeping lives here so this class stays free of any event class; the version module
 * registers a listener for whatever chat event its server api actually has and forwards the
 * message to {@link #handleChat}.
 */
public final class FoxNamePrompt {

    private static final long TIMEOUT_TICKS = 20L * 60L;

    private static final Map<UUID, Consumer<String>> PENDING = new ConcurrentHashMap<>();

    private FoxNamePrompt() {
    }

    public static void ask(Player player, Consumer<String> onAnswer) {
        PENDING.put(player.getUniqueId(), onAnswer);
        FoliaCompat.runOnEntityLater(player, () -> {
            if (PENDING.remove(player.getUniqueId(), onAnswer)) {
                onAnswer.accept("");
            }
        }, TIMEOUT_TICKS);
    }

    /**
     * Consumes the message as the player's answer when they have a pending prompt. Returns true
     * when the message was consumed and should not reach the chat.
     */
    public static boolean handleChat(Player player, String message) {
        Consumer<String> onAnswer = PENDING.remove(player.getUniqueId());
        if (onAnswer == null) {
            return false;
        }

        String answer = message.trim();
        onAnswer.accept(answer.equalsIgnoreCase("cancel") ? "" : answer);
        return true;
    }
}
