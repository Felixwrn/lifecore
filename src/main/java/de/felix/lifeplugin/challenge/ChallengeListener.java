package de.felix.lifeplugin.challenge;

import de.felix.lifeplugin.Main;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

public class ChallengeListener implements Listener {

    @EventHandler
    public void onBreak(BlockBreakEvent e) {

        Player p = e.getPlayer();

        if (e.getBlock().getType() != Material.STONE) {
            return;
        }

        int progress = Main.getInstance().getConfig().getInt(
                "challenge-progress." + p.getUniqueId() + ".stone_miner",
                0
        );

        progress++;

        Main.getInstance().getConfig().set(
                "challenge-progress." + p.getUniqueId() + ".stone_miner",
                progress
        );

        Main.getInstance().saveConfig();

        Challenge challenge = ChallengeManager
                .getChallenges()
                .get("stone_miner");

        if (challenge == null) {
            return;
        }

        if (progress >= challenge.getGoal()) {

            p.sendMessage("§aChallenge completed!");

            p.sendMessage(
                    "§eReward: §6" +
                            challenge.getReward()
            );
        }
    }
}
