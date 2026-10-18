package de.felix.lifeplugin.storage;

import java.util.Optional;
import java.util.UUID;

/** Blocking durable storage. Call through PlayerDataService outside the Bukkit thread. */
public interface Storage extends AutoCloseable {
    Optional<PlayerData> load(UUID uuid);
    void save(UUID uuid, PlayerData data);
    @Override void close();
}
