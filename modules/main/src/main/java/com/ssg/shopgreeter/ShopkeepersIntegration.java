package com.ssg.shopgreeter;

import com.nisovin.shopkeepers.api.events.ShopkeeperOpenUIEvent;
import com.nisovin.shopkeepers.api.events.ShopkeeperRemoveEvent;
import com.nisovin.shopkeepers.api.shopkeeper.Shopkeeper;
import com.nisovin.shopkeepers.api.ui.DefaultUITypes;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class ShopkeepersIntegration implements Listener {
    private static final String ACTION_ADD = "add";
    private static final String ACTION_REMOVE = "remove";
    private static final String ACTION_RADIUS = "radius";

    private final JavaPlugin plugin;
    private final GreeterStore store;
    private final GreeterService service;
    private final ChatInput chatInput;
    private final NamespacedKey actionKey;
    private final NamespacedKey shopkeeperKey;

    public ShopkeepersIntegration(JavaPlugin plugin, GreeterStore store, GreeterService service, ChatInput chatInput) {
        this.plugin = plugin;
        this.store = store;
        this.service = service;
        this.chatInput = chatInput;
        this.actionKey = new NamespacedKey(plugin, "greeter-action");
        this.shopkeeperKey = new NamespacedKey(plugin, "greeter-shopkeeper");
    }

    @EventHandler(ignoreCancelled = true)
    public void onOpenEditor(ShopkeeperOpenUIEvent event) {
        if (event.getUIType() != DefaultUITypes.EDITOR()) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("ssgshopgreeter.admin")) {
            return;
        }
        Shopkeeper shopkeeper = event.getShopkeeper();
        player.getScheduler().run(plugin, t -> injectButtons(player, shopkeeper), null);
    }

    private void injectButtons(Player player, Shopkeeper shopkeeper) {
        if (!player.isOnline()) {
            return;
        }
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getSize() < 9) {
            return;
        }

        UUID id = shopkeeper.getUniqueId();
        List<String> messages = store.getMessages(id);
        double radius = store.getRadius(id);

        int buttons = messages.isEmpty() ? 2 : 3;
        List<Integer> slots = findFreeSlotsFromEnd(top, buttons);
        if (slots.size() < buttons) {
            return;
        }

        int i = 0;
        top.setItem(slots.get(i++), buildAddButton(messages, id));
        top.setItem(slots.get(i++), buildRadiusButton(radius, id));
        if (!messages.isEmpty()) {
            top.setItem(slots.get(i), buildRemoveButton(messages, id));
        }
    }

    private ItemStack buildAddButton(List<String> messages, UUID shopkeeperId) {
        ItemStack item = buildButton(Material.WRITABLE_BOOK, "&aAdicionar mensagem de proximidade", ACTION_ADD, shopkeeperId);
        applyMessageLore(item, messages);
        return item;
    }

    private ItemStack buildRemoveButton(List<String> messages, UUID shopkeeperId) {
        ItemStack item = buildButton(Material.BARRIER, "&cRemover mensagem de proximidade", ACTION_REMOVE, shopkeeperId);
        applyMessageLore(item, messages);
        return item;
    }

    private ItemStack buildRadiusButton(double radius, UUID shopkeeperId) {
        ItemStack item = buildButton(Material.COMPASS, "&bDefinir raio de proximidade", ACTION_RADIUS, shopkeeperId);
        ItemMeta meta = item.getItemMeta();
        meta.lore(List.of(Component.text("Raio atual: " + formatRadius(radius) + " blocos", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private void applyMessageLore(ItemStack item, List<String> messages) {
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>();
        if (messages.isEmpty()) {
            lore.add(Component.text("Nenhuma mensagem definida.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        } else {
            for (int i = 0; i < messages.size(); i++) {
                String preview = ChatColor.translateAlternateColorCodes('&', messages.get(i));
                lore.add(
                    Component.text((i + 1) + ". ", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(preview).decoration(TextDecoration.ITALIC, false))
                );
            }
        }
        meta.lore(lore);
        item.setItemMeta(meta);
    }

    private String formatRadius(double radius) {
        return radius == Math.floor(radius) ? String.valueOf((int) radius) : String.valueOf(radius);
    }

    private List<Integer> findFreeSlotsFromEnd(Inventory top, int count) {
        List<Integer> found = new ArrayList<>();
        for (int slot = top.getSize() - 1; slot >= Math.max(0, top.getSize() - 9) && found.size() < count; slot--) {
            ItemStack item = top.getItem(slot);
            if (item == null || item.getType().isAir()) {
                found.add(slot);
            }
        }
        return found;
    }

    private ItemStack buildButton(Material material, String name, String action, UUID shopkeeperId) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(ChatColor.translateAlternateColorCodes('&', name)).decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        meta.getPersistentDataContainer().set(shopkeeperKey, PersistentDataType.STRING, shopkeeperId.toString());
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }

        ItemMeta meta = clicked.getItemMeta();
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        String shopkeeperIdRaw = meta.getPersistentDataContainer().get(shopkeeperKey, PersistentDataType.STRING);
        if (action == null || shopkeeperIdRaw == null) {
            return;
        }

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        UUID shopkeeperId = UUID.fromString(shopkeeperIdRaw);
        player.getScheduler().run(plugin, t -> player.closeInventory(), null);

        switch (action) {
            case ACTION_ADD -> promptAdd(player, shopkeeperId);
            case ACTION_REMOVE -> promptRemove(player, shopkeeperId);
            case ACTION_RADIUS -> promptRadius(player, shopkeeperId);
            default -> {
            }
        }
    }

    private void promptAdd(Player player, UUID shopkeeperId) {
        player.sendMessage(Component.text("Digite no chat a mensagem de proximidade a adicionar.", NamedTextColor.GREEN));
        player.sendMessage(Component.text("Use &cores, {player} para o nome do jogador e {shop} para o nome da loja.", NamedTextColor.GRAY));
        player.sendMessage(Component.text("Digite ! para cancelar.", NamedTextColor.GRAY));
        chatInput.await(player, text -> handleAddInput(player, shopkeeperId, text));
    }

    private void handleAddInput(Player player, UUID shopkeeperId, String rawText) {
        String text = rawText.trim();
        if (text.equals("!") || text.isEmpty()) {
            player.sendMessage(Component.text("Cancelado; nada foi alterado.", NamedTextColor.GRAY));
            return;
        }

        store.addMessage(shopkeeperId, text);
        player.sendMessage(Component.text("[SSG] Mensagem de proximidade adicionada.", NamedTextColor.GREEN));

        String preview = ChatColor.translateAlternateColorCodes(
            '&', "<" + shopkeeperName(shopkeeperId) + "§r> " + text.replace("{player}", player.getName()).replace("{shop}", shopkeeperName(shopkeeperId))
        );
        player.sendMessage(Component.text("Previa: ", NamedTextColor.GRAY).append(Component.text(preview)));
    }

    private void promptRemove(Player player, UUID shopkeeperId) {
        List<String> messages = store.getMessages(shopkeeperId);
        if (messages.isEmpty()) {
            player.sendMessage(Component.text("Esse vendedor nao tem mensagens configuradas.", NamedTextColor.GRAY));
            return;
        }

        player.sendMessage(Component.text("Mensagens atuais:", NamedTextColor.YELLOW));
        for (int i = 0; i < messages.size(); i++) {
            String preview = ChatColor.translateAlternateColorCodes('&', messages.get(i));
            player.sendMessage(Component.text((i + 1) + ". ", NamedTextColor.YELLOW).append(Component.text(preview)));
        }
        player.sendMessage(Component.text("Digite o numero da mensagem a remover, 0 para remover todas, ou ! para cancelar.", NamedTextColor.GREEN));
        chatInput.await(player, text -> handleRemoveInput(player, shopkeeperId, text));
    }

    private void handleRemoveInput(Player player, UUID shopkeeperId, String rawText) {
        String text = rawText.trim();
        if (text.equals("!") || text.isEmpty()) {
            player.sendMessage(Component.text("Cancelado; nada foi alterado.", NamedTextColor.GRAY));
            return;
        }

        int index;
        try {
            index = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Numero invalido; nada foi alterado.", NamedTextColor.RED));
            return;
        }

        if (index < 0) {
            player.sendMessage(Component.text("Numero invalido; nada foi alterado.", NamedTextColor.RED));
            return;
        }

        List<String> removed = store.removeMessage(shopkeeperId, index);
        if (removed.isEmpty()) {
            player.sendMessage(Component.text("Nenhuma mensagem correspondente encontrada.", NamedTextColor.GRAY));
            return;
        }

        if (store.getMessages(shopkeeperId).isEmpty()) {
            service.forgetShopkeeper(shopkeeperId);
        }

        player.sendMessage(
            Component.text(
                "[SSG] " + removed.size() + " mensagem(ns) de proximidade removida(s).", NamedTextColor.GREEN
            )
        );
    }

    private void promptRadius(Player player, UUID shopkeeperId) {
        player.sendMessage(
            Component.text(
                "Digite no chat o novo raio (em blocos, atual: " + formatRadius(store.getRadius(shopkeeperId)) + ").", NamedTextColor.GREEN
            )
        );
        player.sendMessage(Component.text("Digite ! para cancelar.", NamedTextColor.GRAY));
        chatInput.await(player, text -> handleRadiusInput(player, shopkeeperId, text));
    }

    private void handleRadiusInput(Player player, UUID shopkeeperId, String rawText) {
        String text = rawText.trim();
        if (text.equals("!") || text.isEmpty()) {
            player.sendMessage(Component.text("Cancelado; nada foi alterado.", NamedTextColor.GRAY));
            return;
        }

        double radius;
        try {
            radius = Double.parseDouble(text.replace(",", "."));
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Numero invalido; nada foi alterado.", NamedTextColor.RED));
            return;
        }

        if (radius <= 0 || radius > 100) {
            player.sendMessage(Component.text("O raio deve ser maior que 0 e no maximo 100.", NamedTextColor.RED));
            return;
        }

        store.setRadius(shopkeeperId, radius);
        player.sendMessage(Component.text("[SSG] Raio de proximidade definido para " + formatRadius(radius) + " blocos.", NamedTextColor.GREEN));
    }

    // Mirrors GreeterService.coloredShopName(): the entity's own custom name component carries the
    // real nameplate color, which Shopkeeper.getName() alone does not.
    private String shopkeeperName(UUID shopkeeperId) {
        Shopkeeper shopkeeper = com.nisovin.shopkeepers.api.ShopkeepersAPI.getShopkeeperRegistry().getShopkeeperByUniqueId(shopkeeperId);
        if (shopkeeper == null) {
            return "Loja";
        }
        if (shopkeeper.getShopObject() instanceof com.nisovin.shopkeepers.api.shopobjects.entity.EntityShopObject entityShopObject) {
            org.bukkit.entity.Entity entity = entityShopObject.getEntity();
            if (entity != null && entity.customName() != null) {
                return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(entity.customName());
            }
        }
        return shopkeeper.getName();
    }

    @EventHandler(ignoreCancelled = true)
    public void onShopkeeperRemove(ShopkeeperRemoveEvent event) {
        UUID id = event.getShopkeeper().getUniqueId();
        if (store.remove(id) != null) {
            service.forgetShopkeeper(id);
        }
    }
}
