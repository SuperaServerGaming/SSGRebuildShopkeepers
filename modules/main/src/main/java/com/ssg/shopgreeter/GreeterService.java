package com.ssg.shopgreeter;

import com.nisovin.shopkeepers.api.ShopkeepersAPI;
import com.nisovin.shopkeepers.api.shopkeeper.Shopkeeper;
import com.nisovin.shopkeepers.api.shopobjects.ShopObject;
import com.nisovin.shopkeepers.api.shopobjects.entity.EntityShopObject;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

public final class GreeterService {
    private static final long COOLDOWN_MILLIS = 30_000L;
    private static final long TICK_INTERVAL_TICKS = 10L;

    private final JavaPlugin plugin;
    private final GreeterStore store;
    private final Map<UUID, Set<UUID>> insidePlayersByShopkeeper = new ConcurrentHashMap<>();
    private final Map<String, Long> lastGreetingMillis = new ConcurrentHashMap<>();
    private ScheduledTask task;

    public GreeterService(JavaPlugin plugin, GreeterStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public void start() {
        if (task != null) {
            return;
        }
        // A varredura (só lista vendedores ativos) roda na global region; a checagem em si é
        // despachada pra região dona da localização de cada vendedor, já que vendedores
        // diferentes podem estar em regiões diferentes no Folia.
        task = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, t -> tick(), 20L, TICK_INTERVAL_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        Collection<? extends Shopkeeper> shopkeepers = ShopkeepersAPI.getShopkeeperRegistry().getActiveShopkeepers();

        for (Shopkeeper shopkeeper : shopkeepers) {
            List<String> messages = store.getMessages(shopkeeper.getUniqueId());
            if (messages.isEmpty()) {
                continue;
            }

            Location center = shopkeeper.getLocation();
            if (center == null || center.getWorld() == null) {
                continue;
            }

            plugin.getServer().getRegionScheduler().run(plugin, center, t -> checkShopkeeper(shopkeeper, center, messages));
        }
    }

    private void checkShopkeeper(Shopkeeper shopkeeper, Location center, List<String> messages) {
        long now = System.currentTimeMillis();
        double radius = store.getRadius(shopkeeper.getUniqueId());
        double radiusSq = radius * radius;
        Set<UUID> insideNow = insidePlayersByShopkeeper.computeIfAbsent(shopkeeper.getUniqueId(), id -> new LinkedHashSet<>());

        for (Player player : center.getWorld().getPlayers()) {
            boolean isNear = player.getLocation().distanceSquared(center) <= radiusSq;
            boolean wasInside = insideNow.contains(player.getUniqueId());

            if (isNear && !wasInside) {
                insideNow.add(player.getUniqueId());
                String cooldownKey = shopkeeper.getUniqueId() + ":" + player.getUniqueId();
                Long last = lastGreetingMillis.get(cooldownKey);
                if (last == null || now - last >= COOLDOWN_MILLIS) {
                    lastGreetingMillis.put(cooldownKey, now);
                    String chosen = messages.get(ThreadLocalRandom.current().nextInt(messages.size()));
                    player.sendMessage(formatMessage(chosen, shopkeeper, player));
                }
            } else if (!isNear && wasInside) {
                insideNow.remove(player.getUniqueId());
            }
        }
    }

    private String formatMessage(String template, Shopkeeper shopkeeper, Player player) {
        String shopName = coloredShopName(shopkeeper);
        String body = template.replace("{player}", player.getName()).replace("{shop}", shopName);
        // Mimics vanilla chat formatting (<Name> message), keeping the shop's own nameplate color
        // as-is and resetting to default before the message body, same as a real player message would.
        String raw = "<" + shopName + "§r> " + body;
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    // Shopkeeper.getName() returns the plain configured name; the color actually shown above the
    // shop (the nameplate) lives on the underlying entity's own custom name component, so that's
    // what has to be read to match "the same color as the shop's name" in chat.
    private String coloredShopName(Shopkeeper shopkeeper) {
        ShopObject shopObject = shopkeeper.getShopObject();
        if (shopObject instanceof EntityShopObject entityShopObject) {
            Entity entity = entityShopObject.getEntity();
            if (entity != null) {
                Component customName = entity.customName();
                if (customName != null) {
                    return LegacyComponentSerializer.legacySection().serialize(customName);
                }
            }
        }
        return shopkeeper.getName();
    }

    public void forgetShopkeeper(UUID shopkeeperId) {
        insidePlayersByShopkeeper.remove(shopkeeperId);
    }
}
