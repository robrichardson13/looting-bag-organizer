package io.robrichardson.lootingbagorganizer.model;

import io.robrichardson.lootingbagorganizer.LootingBagOrganizerConfig;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

/**
 * Loads and saves a {@link BagLayout} as one JSON value under the RS-profile-scoped key
 * {@link #KEY} in config group {@code lootingbagorganizer}. RS-profile keys always sync with a
 * signed-in RuneLite account, so the arrangement follows the character, not the machine.
 */
@Singleton
public class LayoutStore
{
	static final String KEY = "layout";

	private final ConfigManager configManager;

	@Inject
	public LayoutStore(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	public BagLayout load()
	{
		// TODO: configManager.getRSProfileConfiguration(CONFIG_GROUP, KEY) -> fromJson -> normalise
		return new BagLayout();
	}

	public void save(BagLayout layout)
	{
		// TODO: configManager.setRSProfileConfiguration(CONFIG_GROUP, KEY, toJson(layout))
	}

	static String toJson(BagLayout layout)
	{
		throw new UnsupportedOperationException("not implemented");
	}

	static BagLayout fromJson(String json)
	{
		throw new UnsupportedOperationException("not implemented");
	}

	@SuppressWarnings("unused")
	private static String group()
	{
		return LootingBagOrganizerConfig.CONFIG_GROUP;
	}
}
