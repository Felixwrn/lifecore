package de.felix.lifeplugin;

import java.util.UUID;

public interface Storage {

    void loadPlayer(UUID uuid);

    void savePlayer(UUID uuid, int lives);

    int getLives(UUID uuid);
}
