package net.seanomik.tamablefoxes.util.io.sqlite;

import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Counts how many foxes a player has tamed.
 *
 * The database is only ever touched on one background thread. Querying it from the thread ticking a
 * fox would stall that region - on Folia many regions tick in parallel, so there is no single
 * thread to hide the wait on - and connecting per query made every taming click pay for opening the
 * file. Reads are served from a map that is filled once at startup and kept current by the same
 * background thread, so the code running on a region thread never waits for the disk.
 */
public class SQLiteHelper {

    private static final String USER_AMOUNT_TABLE_NAME = "USER_FOX_AMT";

    private static SQLiteHelper instance;

    private final Plugin plugin;
    private final SQLiteHandler sqliteHandler = SQLiteHandler.getInstance();
    private final ExecutorService databaseThread = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "TamableFoxes-Database");
        thread.setDaemon(true);
        return thread;
    });

    /** Read on region threads, written to the database by {@link #databaseThread}. */
    private final Map<UUID, Integer> foxAmounts = new ConcurrentHashMap<>();

    public static synchronized SQLiteHelper getInstance(Plugin plugin) {
        if (instance == null) {
            instance = new SQLiteHelper(plugin);
        }

        return instance;
    }

    private SQLiteHelper(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Creates the table and loads the counts. Blocks, so only call it while the server starts. */
    public void createTablesIfNotExist() {
        awaitOnDatabaseThread(() -> {
            try {
                String userFoxAmountQuery =
                        "CREATE TABLE IF NOT EXISTS `" + USER_AMOUNT_TABLE_NAME + "` ( " +
                            "`UUID` TEXT PRIMARY KEY ,  " +
                            "`AMOUNT` INT NOT NULL);";

                try (PreparedStatement statement = connection().prepareStatement(userFoxAmountQuery)) {
                    statement.executeUpdate();
                }

                loadFoxAmounts();
            } catch (SQLException e) {
                this.plugin.getLogger().log(Level.SEVERE, "Could not prepare the fox amount table", e);
            }
        });
    }

    /**
     * The amount of foxes the player owns. Served from memory, because the caller is usually the
     * thread ticking the fox, which must not wait for the disk. Players without a row yield -1.
     */
    public int getPlayerFoxAmount(UUID uuid) {
        return this.foxAmounts.getOrDefault(uuid, -1);
    }

    public void addPlayerFoxAmount(UUID uuid, int amt) {
        int amount = this.foxAmounts.merge(uuid, amt, Integer::sum);

        this.databaseThread.execute(() -> writeFoxAmount(uuid, amount));
    }

    public void removePlayerFoxAmount(UUID uuid, int amt) {
        Integer amount = this.foxAmounts.computeIfPresent(uuid, (key, current) -> Math.max(0, current - amt));
        if (amount != null) {
            this.databaseThread.execute(() -> writeFoxAmount(uuid, amount));
        }
    }

    /** Drains the pending writes and closes the database. Called when the plugin is disabled. */
    public static synchronized void shutdown() {
        if (instance == null) {
            return;
        }

        instance.databaseThread.shutdown();
        try {
            instance.databaseThread.awaitTermination(5L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (instance.sqliteHandler.getConnection() != null) {
            instance.sqliteHandler.closeConnection();
        }

        instance = null;
    }

    private void loadFoxAmounts() throws SQLException {
        this.foxAmounts.clear();

        try (PreparedStatement statement = connection().prepareStatement("SELECT UUID, AMOUNT FROM " + USER_AMOUNT_TABLE_NAME);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                try {
                    this.foxAmounts.put(UUID.fromString(results.getString("UUID")), results.getInt("AMOUNT"));
                } catch (IllegalArgumentException e) {
                    // A row that does not hold a UUID is not worth refusing to start over.
                }
            }
        }
    }

    private void writeFoxAmount(UUID uuid, int amount) {
        try {
            try (PreparedStatement statement = connection().prepareStatement(
                    "UPDATE " + USER_AMOUNT_TABLE_NAME + " SET AMOUNT = ? WHERE UUID = ?")) {
                statement.setInt(1, amount);
                statement.setString(2, uuid.toString());
                if (statement.executeUpdate() > 0) {
                    return;
                }
            }

            try (PreparedStatement statement = connection().prepareStatement(
                    "INSERT INTO " + USER_AMOUNT_TABLE_NAME + " (UUID, AMOUNT) VALUES(?, ?)")) {
                statement.setString(1, uuid.toString());
                statement.setInt(2, amount);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Could not store the tamed fox amount of " + uuid, e);
        }
    }

    /** Only call this from a task of {@link #databaseThread}. */
    private Connection connection() throws SQLException {
        Connection connection = this.sqliteHandler.getConnection();
        if (connection == null || connection.isClosed()) {
            this.sqliteHandler.connect(this.plugin);
            connection = this.sqliteHandler.getConnection();
        }

        return connection;
    }

    private void awaitOnDatabaseThread(Runnable work) {
        try {
            this.databaseThread.submit(work).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Could not prepare the fox amount database", e.getCause());
        }
    }
}
