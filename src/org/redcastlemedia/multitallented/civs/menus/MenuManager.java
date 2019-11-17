package org.redcastlemedia.multitallented.civs.menus;

import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.redcastlemedia.multitallented.civs.Civs;
import org.redcastlemedia.multitallented.civs.civilians.Civilian;
import org.redcastlemedia.multitallented.civs.civilians.CivilianManager;
import org.redcastlemedia.multitallented.civs.events.TwoSecondEvent;
import org.reflections.Reflections;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import java.io.File;
import java.util.*;

public class MenuManager implements Listener {
    private static MenuManager instance = null;
    public static HashMap<UUID, CycleGUI> cycleGuis = new HashMap<>();
    private static HashMap<UUID, Map<String, Object>> data = new HashMap<>();
    private static HashMap<UUID, ArrayList<MenuHistoryState>> history = new HashMap<>();
    private static HashMap<UUID, String> openMenus = new HashMap<>();
    private static HashMap<String, CustomMenu> menus = new HashMap<>();

    @Getter
    private MenuIcon backButton;
    @Getter
    private MenuIcon prevButton;
    @Getter
    private MenuIcon nextButton;

    public MenuManager() {
        if (Civs.getInstance() != null) {
            Bukkit.getPluginManager().registerEvents(this, Civs.getInstance());
        }
        instance = this;
    }
    public static MenuManager getInstance() {
        if (instance == null) {
            new MenuManager();
        }
        return instance;
    }

    public boolean hasMenuOpen(UUID uuid) {
        return openMenus.containsKey(uuid);
    }
    public boolean hasMenuOpen(UUID uuid, String menuName) {
        return menuName.equals(openMenus.get(uuid));
    }

    @EventHandler
    public void onTwoSecondEvent(TwoSecondEvent event) {
        try {
            for (CycleGUI gui : new HashSet<>(cycleGuis.values())) {
                gui.advanceItemPositions();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!openMenus.containsKey(uuid)) {
            return;
        }
        clearCycleItems(uuid);
        Civilian civilian = CivilianManager.getInstance().getCivilian(uuid);
        menus.get(openMenus.get(uuid)).onCloseMenu(civilian, event.getInventory());
        openMenus.remove(uuid);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        UUID uuid = event.getWhoClicked().getUniqueId();
        if (!openMenus.containsKey(uuid)) {
            return;
        }
        Civilian civilian = CivilianManager.getInstance().getCivilian(uuid);
        if (event.getCurrentItem() != null) {
            if (backButton.createCVItem(civilian.getLocale())
                    .equivalentItem(event.getCurrentItem(), true, true)) {
                goBack(civilian.getUuid());
                event.setCancelled(true);
                return;
            } else if (prevButton.createCVItem(civilian.getLocale())
                    .equivalentItem(event.getCurrentItem(), true, true)) {
                int page = (Integer) getData(civilian.getUuid(), "page");
                putData(civilian.getUuid(), "page", page < 1 ? 0 : page - 1);
                refreshMenu(civilian);
                event.setCancelled(true);
                return;
            } else if (nextButton.createCVItem(civilian.getLocale())
                    .equivalentItem(event.getCurrentItem(), true, true)) {
                int page = (Integer) getData(civilian.getUuid(), "page");
                int maxPage = (Integer) getData(civilian.getUuid(), "maxPage");
                putData(civilian.getUuid(), "page", page >= maxPage ? maxPage : page + 1);
                refreshMenu(civilian);
                event.setCancelled(true);
                return;
            }
        }

        boolean shouldCancel = menus.get(openMenus.get(uuid)).doActionsAndCancel(civilian, event.getCursor(), event.getCurrentItem());
        if (shouldCancel) {
            event.setCancelled(true);
        }
    }

    public void goBack(UUID uuid) {
        popLastMenu(uuid);
        MenuHistoryState menuHistoryState = popLastMenu(uuid);
        Player player = Bukkit.getPlayer(uuid);
        openMenuFromHistory(player, menuHistoryState.getMenuName(), menuHistoryState.getData());
    }

    public void loadMenuConfigs() {
        File menuFolder = new File(Civs.getInstance().getDataFolder(), "menus");
        if (menuFolder.exists()) {
            menuFolder.mkdir();
        }
        File menuFile = new File(menuFolder, "default.yml");
        if (!menuFile.exists()) {
            Civs.logger.severe(Civs.getPrefix() + "Unable to find menu default.yml");
            return;
        }
        try {
            FileConfiguration config = new YamlConfiguration();
            config.load(menuFile);
            backButton = new MenuIcon("back",
                    config.getString("back.icon", "REDSTONE_BLOCK"),
                    config.getString("back.name", "back-button"),
                    config.getString("back.desc", ""));
            prevButton = new MenuIcon("prev",
                    config.getString("prev.icon", "REDSTONE"),
                    config.getString("prev.name", "prev-button"),
                    config.getString("prev.desc", ""));
            nextButton = new MenuIcon("next",
                    config.getString("next.icon", "EMERALD"),
                    config.getString("next.name", "next-button"),
                    config.getString("next.desc", ""));
        } catch (Exception e) {
            Civs.logger.severe(Civs.getPrefix() + "Unable to load menu default.yml");
            e.printStackTrace();
            return;
        }

        ConfigurationBuilder configurationBuilder = new ConfigurationBuilder();
        configurationBuilder.addUrls(ClasspathHelper.forPackage("org.redcastlemedia.multitallented.civs.menus"));
        Reflections reflections = new Reflections(configurationBuilder);
        Set<Class<? extends CustomMenu>> menuClasses = reflections.getSubTypesOf(CustomMenu.class);
        for (Class<? extends CustomMenu> menuClass : menuClasses) {
            try {
                CustomMenu currentMenu = menuClass.newInstance();
                loadConfig(currentMenu);
                menus.put(menuClass.getAnnotation(CivsMenu.class).name(), currentMenu);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private synchronized static void clearCycleItems(UUID uuid) {
        cycleGuis.remove(uuid);
    }

    private void loadConfig(CustomMenu customMenu) {
        File menuFolder = new File(Civs.getInstance().getDataFolder(), "menus");
        if (menuFolder.exists()) {
            menuFolder.mkdir();
        }
        String menuName = customMenu.getClass().getAnnotation(CivsMenu.class).name();
        File menuFile = new File(menuFolder, menuName + ".yml");
        if (!menuFile.exists()) {
            Civs.logger.severe(Civs.getPrefix() + "Unable to load menu " + menuName);
            return;
        }

        FileConfiguration config = new YamlConfiguration();
        try {
            config.load(menuFile);
        } catch (Exception e) {
            Civs.logger.severe(Civs.getPrefix() + "Unable to load menu " + menuName);
        }
        int newSize = config.getInt("size", 36);
        int size = MenuUtil.getInventorySize(newSize);
        String name = config.getString("name", "Unnamed");
        HashSet<MenuIcon> items = new HashSet<>();
        for (String key : config.getConfigurationSection("items").getKeys(false)) {
            MenuIcon menuIcon = new MenuIcon(key, config.getConfigurationSection("items." + key));
            if (menuIcon.getIndex().isEmpty() ||
                    menuIcon.getIndex().get(0) < 0) {
                continue;
            }
            items.add(menuIcon);
        }
        customMenu.loadConfig(items, size, name);
    }

    public void openMenuFromHistory(Player player, String menuName, Map<String, Object> data) {
        if (!menus.containsKey(menuName)) {
            return;
        }
        Civilian civilian = CivilianManager.getInstance().getCivilian(player.getUniqueId());
        player.openInventory(menus.get(menuName).createMenuFromHistory(civilian, data));
        if (menus.get(menuName).cycleItems.containsKey(civilian.getUuid())) {
            cycleGuis.put(civilian.getUuid(), menus.get(menuName).cycleItems.get(civilian.getUuid()));
            menus.get(menuName).cycleItems.remove(civilian.getUuid());
        }
        openMenus.put(player.getUniqueId(), menuName);
        if (!history.containsKey(player.getUniqueId())) {
            history.put(player.getUniqueId(), new ArrayList<>());
        }
        MenuHistoryState menuHistoryState = new MenuHistoryState(menuName, getAllData(player.getUniqueId()));
        history.get(player.getUniqueId()).add(menuHistoryState);
    }

    public void openMenu(Player player, String menuName, Map<String, String> params) {
        if (!menus.containsKey(menuName)) {
            return;
        }
        Civilian civilian = CivilianManager.getInstance().getCivilian(player.getUniqueId());
        String redirectMenu = menus.get(menuName).beforeOpenMenu(civilian);
        if (redirectMenu != null) {
            openMenu(player, redirectMenu, params);
            return;
        }
        player.openInventory(menus.get(menuName).createMenu(civilian, params));
        if (menus.get(menuName).cycleItems.containsKey(civilian.getUuid())) {
            cycleGuis.put(civilian.getUuid(), menus.get(menuName).cycleItems.get(civilian.getUuid()));
            menus.get(menuName).cycleItems.remove(civilian.getUuid());
        }
        openMenus.put(player.getUniqueId(), menuName);
        if (!history.containsKey(player.getUniqueId())) {
            history.put(player.getUniqueId(), new ArrayList<>());
        }
        MenuHistoryState menuHistoryState = new MenuHistoryState(menuName, getAllData(player.getUniqueId()));
        history.get(player.getUniqueId()).add(menuHistoryState);
    }
    public void refreshMenu(Civilian civilian) {
        if (!openMenus.containsKey(civilian.getUuid())) {
            return;
        }
        Player player = Bukkit.getPlayer(civilian.getUuid());
        String menuName = openMenus.get(civilian.getUuid());
        player.openInventory(menus.get(menuName).createMenu(civilian));
        openMenus.put(civilian.getUuid(), menuName);
    }

    public static void addHistory(UUID uuid, CustomMenu customMenu, Map<String, Object> data) {
        if (!history.containsKey(uuid)) {
            history.put(uuid, new ArrayList<>());
        }
        String name = customMenu.getClass().getAnnotation(CivsMenu.class).name();
        MenuHistoryState menuHistoryState = new MenuHistoryState(name, data);
        history.get(uuid).add(menuHistoryState);
    }
    public static MenuHistoryState popLastMenu(UUID uuid) {
        if (!history.containsKey(uuid) ||
                history.get(uuid).isEmpty()) {
            MenuHistoryState menuHistoryState = new MenuHistoryState("main", new HashMap<>());
            return menuHistoryState;
        }
        MenuHistoryState menuHistoryState = history.get(uuid).get(history.get(uuid).size() - 1);
        history.get(uuid).remove(history.get(uuid).size() - 1);
        return menuHistoryState;
    }
    public static void clearHistory(UUID uuid) {
        history.remove(uuid);
    }
    public static Map<String, Object> getAllData(UUID uuid) {
        return data.get(uuid);
    }
    public static Object getData(UUID uuid, String key) {
        Map<String, Object> dataMap = data.get(uuid);
        if (dataMap == null) {
            return null;
        }
        return dataMap.get(key);
    }
    public static void putData(UUID uuid, String key, Object value) {
        data.get(uuid).put(key, value);
    }
    public static void setNewData(UUID uuid, Map<String, Object> newData) {
        data.put(uuid, newData);
    }
    public static void clearData(UUID uuid) {
        data.remove(uuid);
    }
}