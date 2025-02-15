package com.roverisadog.infohud;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Biome;

public class Biomes {
    private static final Registry<Biome> registry;

    static {
        RegistryAccess ra = RegistryAccess.registryAccess();
        registry = ra.getRegistry(RegistryKey.BIOME);
    }

    public static Biome get(String name) {
        var key = NamespacedKey.fromString(name.toLowerCase());
        if (key != null) {
            return registry.get(key);
        }
        return null;
    }
}
