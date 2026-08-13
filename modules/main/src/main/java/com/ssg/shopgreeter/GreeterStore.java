package com.ssg.shopgreeter;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class GreeterStore {
    public static final double DEFAULT_RADIUS = 4.0;

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, GreeterPoint> points = new ConcurrentHashMap<>();

    public GreeterStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "greeters.yml");
    }

    public void load() {
        points.clear();
        if (!file.exists()) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("greeters");
        if (root == null) {
            return;
        }

        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            try {
                UUID shopkeeperId = UUID.fromString(key);
                GreeterPoint point = new GreeterPoint(shopkeeperId, s.getDouble("radius", DEFAULT_RADIUS));
                point.getMessages().addAll(s.getStringList("messages"));
                points.put(shopkeeperId, point);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Chave invalida em greeters.yml, ignorando: " + key);
            }
        }
    }

    public void saveAll() {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("greeters");

        for (GreeterPoint point : points.values()) {
            ConfigurationSection s = root.createSection(point.getShopkeeperId().toString());
            s.set("radius", point.getRadius());
            s.set("messages", point.getMessages());
        }

        try {
            file.getParentFile().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Falha ao salvar greeters.yml", e);
        }
    }

    public GreeterPoint get(UUID shopkeeperId) {
        return points.get(shopkeeperId);
    }

    private GreeterPoint getOrCreate(UUID shopkeeperId) {
        return points.computeIfAbsent(shopkeeperId, id -> new GreeterPoint(id, DEFAULT_RADIUS));
    }

    public List<String> getMessages(UUID shopkeeperId) {
        GreeterPoint point = points.get(shopkeeperId);
        return point == null ? List.of() : List.copyOf(point.getMessages());
    }

    public double getRadius(UUID shopkeeperId) {
        GreeterPoint point = points.get(shopkeeperId);
        return point == null ? DEFAULT_RADIUS : point.getRadius();
    }

    public void addMessage(UUID shopkeeperId, String message) {
        getOrCreate(shopkeeperId).getMessages().add(message);
        saveAll();
    }

    /**
     * @param oneBasedIndex 1-based index into the message list, or 0 to remove all.
     * @return the removed message(s), for confirmation feedback, or empty if nothing matched.
     */
    public List<String> removeMessage(UUID shopkeeperId, int oneBasedIndex) {
        GreeterPoint point = points.get(shopkeeperId);
        if (point == null || point.isEmpty()) {
            return List.of();
        }

        if (oneBasedIndex == 0) {
            List<String> all = List.copyOf(point.getMessages());
            point.getMessages().clear();
            cleanupIfEmpty(point);
            saveAll();
            return all;
        }

        int index = oneBasedIndex - 1;
        if (index < 0 || index >= point.getMessages().size()) {
            return List.of();
        }

        String removed = point.getMessages().remove(index);
        cleanupIfEmpty(point);
        saveAll();
        return List.of(removed);
    }

    public void setRadius(UUID shopkeeperId, double radius) {
        getOrCreate(shopkeeperId).setRadius(radius);
        saveAll();
    }

    private void cleanupIfEmpty(GreeterPoint point) {
        if (point.isEmpty()) {
            points.remove(point.getShopkeeperId());
        }
    }

    public GreeterPoint remove(UUID shopkeeperId) {
        GreeterPoint removed = points.remove(shopkeeperId);
        if (removed != null) {
            saveAll();
        }
        return removed;
    }
}
