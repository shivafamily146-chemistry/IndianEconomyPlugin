package io.github.shivafamily146.indianeconomy;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

public final class IndianEconomyPlugin extends JavaPlugin implements Listener {
    private Connection database;
    private final Map<Integer, ShopEntry> shopEntries = new HashMap<>();
    private String symbol;

    private record ShopEntry(Material material, int amount, double buyPrice) {}

    private static final class ShopHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("shop.yml", false);
        symbol = getConfig().getString("currency-symbol", "₹");
        try {
            File folder = getDataFolder();
            if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("Cannot create plugin folder");
            database = DriverManager.getConnection("jdbc:sqlite:" + new File(folder, "economy.db").getAbsolutePath());
            try (Statement st = database.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS accounts (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, balance REAL NOT NULL CHECK(balance >= 0), last_daily TEXT)");
            }
        } catch (Exception ex) {
            getLogger().severe("Could not open economy database: " + ex.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        for (Player player : Bukkit.getOnlinePlayers()) ensureAccount(player);
        getLogger().info("IndianEconomy enabled.");
    }

    @Override
    public void onDisable() {
        try { if (database != null && !database.isClosed()) database.close(); }
        catch (SQLException ex) { getLogger().severe("Database close failed: " + ex.getMessage()); }
    }

    private void ensureAccount(Player player) {
        String sql = "INSERT OR IGNORE INTO accounts(uuid,name,balance,last_daily) VALUES(?,?,?,NULL)";
        try (PreparedStatement ps = database.prepareStatement(sql)) {
            ps.setString(1, player.getUniqueId().toString());
            ps.setString(2, player.getName());
            ps.setDouble(3, getConfig().getDouble("starting-balance", 1000.0));
            ps.executeUpdate();
        } catch (SQLException ex) { getLogger().severe("Account init failed: " + ex.getMessage()); }
        try (PreparedStatement ps = database.prepareStatement("UPDATE accounts SET name=? WHERE uuid=?")) {
            ps.setString(1, player.getName());
            ps.setString(2, player.getUniqueId().toString());
            ps.executeUpdate();
        } catch (SQLException ex) { getLogger().severe("Account update failed: " + ex.getMessage()); }
    }

    private double balance(UUID uuid) throws SQLException {
        try (PreparedStatement ps = database.prepareStatement("SELECT balance FROM accounts WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getDouble(1) : 0.0; }
        }
    }

    private void setBalance(UUID uuid, double amount) throws SQLException {
        if (!Double.isFinite(amount) || amount < 0) throw new IllegalArgumentException("Invalid balance");
        try (PreparedStatement ps = database.prepareStatement("UPDATE accounts SET balance=? WHERE uuid=?")) {
            ps.setDouble(1, amount);
            ps.setString(2, uuid.toString());
            if (ps.executeUpdate() != 1) throw new SQLException("Account not found");
        }
    }

    private boolean validAmount(String input) {
        try { double n = Double.parseDouble(input); return Double.isFinite(n) && n > 0 && n <= 1_000_000_000; }
        catch (NumberFormatException ex) { return false; }
    }

    private String money(double value) { return symbol + String.format(Locale.US, "%,.2f", value); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("bal")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
            ensureAccount(p);
            try { p.sendMessage("§aBalance: §e" + money(balance(p.getUniqueId()))); }
            catch (SQLException ex) { p.sendMessage("§cDatabase error."); }
            return true;
        }
        if (cmd.equals("pay")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
            if (args.length != 2 || !validAmount(args[1])) { p.sendMessage("§cUsage: /pay <player> <amount>"); return true; }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null || target.equals(p)) { p.sendMessage("§cPlayer must be online and cannot be yourself."); return true; }
            ensureAccount(p); ensureAccount(target);
            double amount = Double.parseDouble(args[1]);
            try {
                database.setAutoCommit(false);
                double from = balance(p.getUniqueId());
                if (from < amount) { database.rollback(); p.sendMessage("§cInsufficient funds."); return true; }
                setBalance(p.getUniqueId(), from - amount);
                setBalance(target.getUniqueId(), balance(target.getUniqueId()) + amount);
                database.commit();
                p.sendMessage("§aSent " + money(amount) + " to " + target.getName());
                target.sendMessage("§aReceived " + money(amount) + " from " + p.getName());
            } catch (SQLException ex) {
                try { database.rollback(); } catch (SQLException ignored) {}
                p.sendMessage("§cPayment failed; no money should have been transferred.");
                getLogger().severe("Payment failed: " + ex.getMessage());
            } finally { try { database.setAutoCommit(true); } catch (SQLException ignored) {} }
            return true;
        }
        if (cmd.equals("daily")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
            ensureAccount(p);
            try {
                String today = LocalDate.now().toString();
                String last;
                try (PreparedStatement ps = database.prepareStatement("SELECT last_daily FROM accounts WHERE uuid=?")) {
                    ps.setString(1, p.getUniqueId().toString());
                    try (ResultSet rs = ps.executeQuery()) { last = rs.next() ? rs.getString(1) : null; }
                }
                if (today.equals(last)) { p.sendMessage("§cYou already claimed today's reward."); return true; }
                double reward = getConfig().getDouble("daily-reward", 100.0);
                database.setAutoCommit(false);
                setBalance(p.getUniqueId(), balance(p.getUniqueId()) + reward);
                try (PreparedStatement ps = database.prepareStatement("UPDATE accounts SET last_daily=? WHERE uuid=?")) {
                    ps.setString(1, today); ps.setString(2, p.getUniqueId().toString()); ps.executeUpdate();
                }
                database.commit();
                p.sendMessage("§aDaily reward: §e" + money(reward));
            } catch (SQLException ex) {
                try { database.rollback(); } catch (SQLException ignored) {}
                p.sendMessage("§cDaily claim failed.");
            } finally { try { database.setAutoCommit(true); } catch (SQLException ignored) {} }
            return true;
        }
        if (cmd.equals("shop")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
            openShop(p); return true;
        }
        if (cmd.equals("ie")) {
            if (args.length == 1 && args[0].equalsIgnoreCase("help")) {
                sender.sendMessage("§6/ie give <player> <amount> | take | set | reload");
                return true;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig(); symbol = getConfig().getString("currency-symbol", "₹");
                sender.sendMessage("§aIndianEconomy config reloaded."); return true;
            }
            if (args.length == 3 && Set.of("give", "take", "set").contains(args[0].toLowerCase(Locale.ROOT)) && validAmount(args[2])) {
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { sender.sendMessage("§cTarget must be online."); return true; }
                ensureAccount(target);
                double amount = Double.parseDouble(args[2]);
                try {
                    double current = balance(target.getUniqueId());
                    double next = switch (args[0].toLowerCase(Locale.ROOT)) {
                        case "give" -> current + amount;
                        case "take" -> current - amount;
                        default -> amount;
                    };
                    if (next < 0) { sender.sendMessage("§cCannot make balance negative."); return true; }
                    setBalance(target.getUniqueId(), next);
                    sender.sendMessage("§aUpdated " + target.getName() + " balance to " + money(next));
                } catch (SQLException ex) { sender.sendMessage("§cDatabase error."); }
                return true;
            }
            sender.sendMessage("§cUsage: /ie help"); return true;
        }
        return false;
    }

    private void openShop(Player player) {
        ConfigurationSection items = getConfigSection("shop.yml", "items");
        ShopHolder holder = new ShopHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, "§2Indian Economy Shop");
        shopEntries.clear();
        if (items != null) {
            int slot = 0;
            for (String key : items.getKeys(false)) {
                if (slot >= 45) break;
                ConfigurationSection item = items.getConfigurationSection(key);
                if (item == null) continue;
                Material material = Material.matchMaterial(item.getString("material", ""));
                int amount = item.getInt("amount", 1);
                double price = item.getDouble("buy", -1);
                if (material == null || !material.isItem() || amount < 1 || amount > material.getMaxStackSize() || !Double.isFinite(price) || price <= 0) continue;
                ItemStack stack = new ItemStack(material, amount);
                ItemMeta meta = stack.getItemMeta();
                meta.setDisplayName("§e" + key.replace('_', ' '));
                meta.setLore(List.of("§7Price: §a" + money(price), "§eClick to buy"));
                stack.setItemMeta(meta);
                inv.setItem(slot, stack);
                shopEntries.put(slot, new ShopEntry(material, amount, price));
                slot++;
            }
        }
        holder.inventory = inv;
        player.openInventory(inv);
    }

    private ConfigurationSection getConfigSection(String file, String path) {
        File f = new File(getDataFolder(), file);
        org.bukkit.configuration.file.YamlConfiguration yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
        return yaml.getConfigurationSection(path);
    }

    @EventHandler
    public void onShopClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShopHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        ShopEntry entry = shopEntries.get(slot);
        if (entry == null) return;
        try {
            double current = balance(player.getUniqueId());
            if (current < entry.buyPrice()) { player.sendMessage("§cInsufficient funds."); return; }
            database.setAutoCommit(false);
            setBalance(player.getUniqueId(), current - entry.buyPrice());
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(new ItemStack(entry.material(), entry.amount()));
            if (!leftovers.isEmpty()) {
                setBalance(player.getUniqueId(), current);
                database.rollback();
                player.sendMessage("§cInventory full; purchase cancelled.");
            } else {
                database.commit();
                player.sendMessage("§aPurchased for " + money(entry.buyPrice()));
            }
        } catch (SQLException ex) {
            try { database.rollback(); } catch (SQLException ignored) {}
            player.sendMessage("§cPurchase failed.");
            getLogger().severe("Shop purchase failed: " + ex.getMessage());
        } finally { try { database.setAutoCommit(true); } catch (SQLException ignored) {} }
    }
}
