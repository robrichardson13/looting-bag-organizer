package io.robrichardson.lootingbagorganizer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;

@ConfigGroup(LootingBagOrganizerConfig.CONFIG_GROUP)
public interface LootingBagOrganizerConfig extends Config
{
	String CONFIG_GROUP = "lootingbagorganizer";

	// @ConfigItem settings are RuneLite-profile scoped and only sync when that profile's sync
	// toggle is on. The layout itself is RS-profile scoped and lives in LayoutStore, not here.
	// TODO: add settings only once a behaviour genuinely needs a toggle.
}
