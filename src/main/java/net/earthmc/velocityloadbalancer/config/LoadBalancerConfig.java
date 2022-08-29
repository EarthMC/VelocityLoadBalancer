package net.earthmc.velocityloadbalancer.config;

import com.moandjiezana.toml.Toml;
import net.earthmc.velocityloadbalancer.VelocityLoadBalancer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LoadBalancerConfig {
    private static final String CONFIG_FILE_NAME = "config.toml";

    private final VelocityLoadBalancer plugin;
    private final Path pluginFolder;
    private final Path configPath;

    private final List<String> servers = new ArrayList<>();

    public LoadBalancerConfig(VelocityLoadBalancer plugin, Path pluginFolder) {
        this.plugin = plugin;
        this.pluginFolder = pluginFolder;
        this.configPath = pluginFolder.resolve(CONFIG_FILE_NAME);
    }

    public void load() {
        saveDefaultConfig();

        Toml config = new Toml().read(configPath.toFile());

        servers.clear();

        List<String> servers = config.getList("servers");
        this.servers.addAll(servers.stream().map(server -> server.toLowerCase(Locale.ROOT)).toList());

        plugin.logger().info("Loaded {} servers.", servers.size());
    }

    public void saveDefaultConfig() {
        if (Files.exists(configPath))
            return;

        try {
            Files.createDirectories(pluginFolder);
        } catch (IOException e) {
            plugin.logger().error("An exception occurred when creating plugin folder", e);
            return;
        }

        try (InputStream is = VelocityLoadBalancer.class.getResourceAsStream("/" + CONFIG_FILE_NAME)) {
            if (is == null) {
                plugin.logger().error("Could not find file {} in the plugin jar.", CONFIG_FILE_NAME);
                return;
            }

            Files.copy(is, configPath);
        } catch (IOException e) {
            plugin.logger().error("An exception occurred when saving default config file", e);
        }
    }

    public List<String> servers() {
        return this.servers;
    }
}
