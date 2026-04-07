package org.hackedserver.spigot.probing;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.hackedserver.core.HackedPlayer;
import org.hackedserver.core.HackedServer;
import org.hackedserver.core.probing.ProbingConfig;
import org.hackedserver.core.probing.TranslationCheck;
import org.hackedserver.spigot.HackedServerPlugin;
import org.hackedserver.spigot.listeners.PayloadProcessor;
import org.hackedserver.spigot.utils.logs.Logs;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Active probing via the sign translation vulnerability (MC-265322).
 * <p>
 * Places an invisible sign underground, writes translatable components on it,
 * opens the sign editor for the player, and reads back the resolved text.
 * If the client resolves a mod-specific translation key, the mod is detected.
 * <p>
 * Paper 1.20+ only (requires Sign API with Side support).
 */
public class SignTranslationProber implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    /**
     * Tracks active probes: player UUID → probe session data.
     */
    private final Map<UUID, ProbeSession> activeSessions = new ConcurrentHashMap<>();

    /**
     * Stores which checks are assigned to which sign line for each probe.
     */
    private record ProbeSession(Location signLocation, BlockData originalBlockData,
                                List<TranslationCheck> lineChecks) {
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!ProbingConfig.isEnabled()) {
            return;
        }

        List<TranslationCheck> checks = ProbingConfig.getChecks();
        if (checks.isEmpty()) {
            return;
        }

        Player player = event.getPlayer();
        if (player.hasPermission("hackedserver.bypass")) {
            return;
        }

        // Schedule the probe after the configured delay
        Bukkit.getScheduler().runTaskLater(HackedServerPlugin.get(), () -> {
            if (!player.isOnline()) {
                return;
            }
            startProbe(player, checks);
        }, ProbingConfig.getDelayTicks());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        ProbeSession session = activeSessions.remove(event.getPlayer().getUniqueId());
        if (session != null) {
            restoreBlock(session);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        ProbeSession session = activeSessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }

        // Cancel the event so the sign is never actually updated in the world
        event.setCancelled(true);

        // Restore the original block
        restoreBlock(session);

        // Analyze responses
        HackedPlayer hackedPlayer = HackedServer.getPlayer(player.getUniqueId());

        for (int i = 0; i < session.lineChecks.size(); i++) {
            TranslationCheck check = session.lineChecks.get(i);
            Component lineComponent = event.line(i);
            String response = PLAIN.serialize(lineComponent).trim();
            String expected = check.getExpectedVanillaResponse();

            // If the response differs from what vanilla would return, the mod resolved the key
            if (!response.isEmpty() && !response.equals(expected)) {
                String probeCheckId = "probe_" + check.getId();
                if (!hackedPlayer.hasGenericCheck(probeCheckId)) {
                    hackedPlayer.addGenericCheck(probeCheckId);
                    PayloadProcessor.runActions(player, check.getName(), check.getActions());
                }
            }
        }
    }

    private void startProbe(Player player, List<TranslationCheck> checks) {
        // Only use up to 4 checks (4 sign lines)
        List<TranslationCheck> lineChecks = checks.size() > 4 ? checks.subList(0, 4) : checks;

        Location playerLoc = player.getLocation();
        Location signLoc = playerLoc.clone().add(0, ProbingConfig.getSignOffsetY(), 0);

        // Clamp Y to valid range
        int minY = signLoc.getWorld().getMinHeight();
        if (signLoc.getBlockY() < minY) {
            signLoc.setY(minY);
        }

        Block block = signLoc.getBlock();
        BlockData originalData = block.getBlockData();

        try {
            // Place sign block
            block.setType(Material.OAK_SIGN, false);
            BlockState state = block.getState();
            if (!(state instanceof Sign sign)) {
                // Shouldn't happen, but restore if it does
                block.setBlockData(originalData, false);
                return;
            }

            // Write translation checks on the back side (less visible)
            Side side = Side.BACK;
            SignSide signSide = sign.getSide(side);

            for (int i = 0; i < lineChecks.size(); i++) {
                TranslationCheck check = lineChecks.get(i);
                Component component = buildTranslationComponent(check);
                signSide.line(i, component);
            }

            sign.update(true, false);

            // Store session
            activeSessions.put(player.getUniqueId(),
                    new ProbeSession(signLoc.clone(), originalData, lineChecks));

            // Open sign editor after a short delay (client needs to receive block data first)
            Bukkit.getScheduler().runTaskLater(HackedServerPlugin.get(), () -> {
                if (!player.isOnline()) {
                    ProbeSession s = activeSessions.remove(player.getUniqueId());
                    if (s != null) restoreBlock(s);
                    return;
                }

                try {
                    player.openSign(sign, side);
                } catch (Throwable e) {
                    Logs.logWarning("Failed to open sign editor for probe: " + e.getMessage());
                    ProbeSession s = activeSessions.remove(player.getUniqueId());
                    if (s != null) restoreBlock(s);
                    return;
                }

                // Schedule cleanup in case the client never responds
                Bukkit.getScheduler().runTaskLater(HackedServerPlugin.get(), () -> {
                    ProbeSession s = activeSessions.remove(player.getUniqueId());
                    if (s != null) {
                        restoreBlock(s);
                    }
                }, 100L); // 5 second timeout
            }, 5L);

        } catch (Throwable e) {
            Logs.logWarning("Failed to start translation probe: " + e.getMessage());
            block.setBlockData(originalData, false);
        }
    }

    /**
     * Builds the Adventure component for a translation check.
     * <p>
     * With bypass_protection: uses %s substitution to wrap the key,
     * evading client-side mixins that strip known mod keys from signs.
     * Without: uses a plain translatable component.
     */
    private Component buildTranslationComponent(TranslationCheck check) {
        if (check.isBypassProtection()) {
            // Wrap in %s to bypass sign translation protection mixins
            // The outer key "%s" doesn't contain the mod name, so protection doesn't trigger
            return Component.translatable("%s", Component.translatable(check.getTranslationKey()));
        } else {
            return Component.translatable(check.getTranslationKey());
        }
    }

    private void restoreBlock(ProbeSession session) {
        try {
            Location loc = session.signLocation;
            if (loc.getWorld() != null && loc.isChunkLoaded()) {
                loc.getBlock().setBlockData(session.originalBlockData, false);
            }
        } catch (Throwable e) {
            Logs.logWarning("Failed to restore block after probe: " + e.getMessage());
        }
    }
}
