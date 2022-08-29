package net.earthmc.velocityloadbalancer;

import com.google.inject.Inject;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.earthmc.velocityloadbalancer.config.LoadBalancerConfig;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "velocityloadbalancer",
        name = "VelocityLoadBalancer",
        version = "0.0.1",
        description = "A simple load balancer for Velocity proxies.",
        authors = {"Warriorrr"}
)
public class VelocityLoadBalancer {
    private final ProxyServer proxy;
    private final Path pluginFolder;
    private final Logger logger;
    private LoadBalancerConfig config;

    private final Map<String, ServerData> serverDataMap = new ConcurrentHashMap<>();

    @Inject
    public VelocityLoadBalancer(ProxyServer proxy, @DataDirectory Path pluginFolder, Logger logger) {
        this.proxy = proxy;
        this.pluginFolder = pluginFolder;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        config = new LoadBalancerConfig(this, pluginFolder);
        config.load();

        for (String server : config.servers())
            serverDataMap.put(server, new ServerData());

        proxy.getScheduler().buildTask(this, () -> {
            Iterator<Map.Entry<String, ServerData>> entryIterator = serverDataMap.entrySet().iterator();

            while (entryIterator.hasNext()) {
                Map.Entry<String, ServerData> entry = entryIterator.next();

                proxy.getServer(entry.getKey()).ifPresentOrElse(server -> {
                    server.ping()
                        .completeOnTimeout(null, 3, TimeUnit.SECONDS)
                        .thenAccept(ping -> {
                            if (ping == null) {
                                entry.getValue().online = false;
                                return;
                            }

                            entry.getValue().online = true;
                            ping.getPlayers().ifPresent(players -> entry.getValue().playerCount = players.getOnline());
                        });
                }, entryIterator::remove);
            }
        }).repeat(Duration.ofSeconds(30)).schedule();
    }

    @Subscribe
    public void onProxyReload(ProxyReloadEvent event) {
        config.load();

        serverDataMap.clear();
        for (String server : config.servers())
            serverDataMap.put(server, new ServerData());
    }

    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        findBestServer().ifPresent(event::setInitialServer);
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        event.getPreviousServer().ifPresent(this::disconnect);
        connect(event.getServer());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        event.getPlayer().getCurrentServer().map(ServerConnection::getServer).ifPresent(this::disconnect);
    }

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        if (!event.kickedDuringServerConnect())
            return;

        ServerData data = this.serverDataMap.get(event.getServer().getServerInfo().getName().toLowerCase(Locale.ROOT));
        if (data == null)
            return;

        // The player got kicked whilst attempting to connect to a load balanced server, attempt to redirect them to a new server.
        findBestServer().ifPresent(server -> event.setResult(KickedFromServerEvent.RedirectPlayer.create(server)));
    }

    public void connect(RegisteredServer server) {
        ServerData serverData = this.serverDataMap.get(server.getServerInfo().getName().toLowerCase(Locale.ROOT));
        if (serverData != null) {
            serverData.online = true;
            serverData.playerCount++;
        }
    }

    public void disconnect(RegisteredServer server) {
        ServerData serverData = this.serverDataMap.get(server.getServerInfo().getName().toLowerCase(Locale.ROOT));
        if (serverData != null)
            serverData.playerCount--;
    }

    public Optional<RegisteredServer> findBestServer() {
        return this.serverDataMap.entrySet().stream()
                .filter(entry -> entry.getValue().online)
                .min(Comparator.comparingInt(entry -> entry.getValue().playerCount))
                .flatMap(entry -> proxy.getServer(entry.getKey()));
    }

    public Logger logger() {
        return this.logger;
    }

    public ProxyServer proxy() {
        return this.proxy;
    }

    private static class ServerData {
        public int playerCount = 0;
        public boolean online = true;
    }
}
