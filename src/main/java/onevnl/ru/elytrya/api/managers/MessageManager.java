package onevnl.ru.elytrya.api.managers;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import net.md_5.bungee.api.ChatColor;
import onevnl.ru.elytrya.util.RewardLevels;

public class MessageManager {

    private final JavaPlugin plugin;
    private FileConfiguration messagesConfig;
    private File messagesFile;

    public MessageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        loadMessages();
    }

    public void loadMessages() {
        messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
    }

    public String getMessage(String path) {
        String message = messagesConfig.getString(path, "&cMessage not found: " + path);
        String prefix = messagesConfig.getString("prefix", "");
        return color(prefix + message);
    }

    public String getMessage(String path, String defaultMessage) {
        String message = messagesConfig.getString(path, defaultMessage);
        String prefix = messagesConfig.getString("prefix", "");
        return color(prefix + (message != null ? message : defaultMessage));
    }

    public String getRawMessage(String path, String defaultMessage) {
        String message = messagesConfig.getString(path, defaultMessage);
        return color(message != null ? message : defaultMessage);
    }

    public List<String> getMessageList(String path) {
        List<String> list = messagesConfig.getStringList(path);
        if (list.isEmpty()) {
            return Collections.singletonList(color("&cMessages not found: " + path));
        }
        return list.stream().map(this::color).collect(Collectors.toList());
    }

    private String color(String text) {
        if (text == null) return "";
        Pattern pattern = Pattern.compile("&#([A-Fa-f0-9]{6})");
        Matcher matcher = pattern.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, ChatColor.of("#" + matcher.group(1)).toString());
        }
        return ChatColor.translateAlternateColorCodes('&', matcher.appendTail(buffer).toString());
    }

    public void broadcastCongratulation(String playerName, String levelName) {
        if (levelName.equalsIgnoreCase("none")) return;

        boolean enabled = RewardLevels.congratulation(plugin.getConfig(), levelName);
        if (!enabled) return;

        List<String> lines = getMessageList("congratulation_message");
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (String line : lines) {
                p.sendMessage(line.replace("{nickname}", playerName).replace("{level_name}", levelName));
            }
        }
    }
}
