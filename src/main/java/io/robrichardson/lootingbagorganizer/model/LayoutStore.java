package io.robrichardson.lootingbagorganizer.model;

import com.google.gson.Gson;
import io.robrichardson.lootingbagorganizer.LootingBagOrganizerConfig;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Loads and saves a {@link BagLayout} as one JSON value under the RS-profile-scoped key
 * {@link #KEY} in config group {@code lootingbagorganizer}. RS-profile keys always sync with a
 * signed-in RuneLite account, so the arrangement follows the character, not the machine.
 */
@Slf4j
@Singleton
public class LayoutStore
{
	static final String KEY = "layout";

	private final ConfigManager configManager;
	private final Gson gson;

	@Inject
	public LayoutStore(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	/**
	 * @param profileKey the RS profile key, from {@code configManager.getRSProfileKey()}. A
	 * {@code null} key (not yet resolved) returns a fresh, empty layout rather than throwing.
	 */
	public BagLayout load(String profileKey)
	{
		String json = profileKey == null ? null
			: configManager.getConfiguration(LootingBagOrganizerConfig.CONFIG_GROUP, profileKey, KEY);
		if (json == null || json.isEmpty())
		{
			return new BagLayout();
		}

		try
		{
			BagLayout layout = gson.fromJson(json, BagLayout.class);
			if (layout == null)
			{
				return new BagLayout();
			}
			layout.normalise();
			return layout;
		}
		catch (RuntimeException e)
		{
			// JsonSyntaxException for malformed JSON, but also anything normalise() cannot repair. A
			// fresh layout beats letting the exception escape into the render loop, where it would
			// recur every frame.
			log.warn("Discarding unreadable looting bag layout for profile {}", profileKey, e);
			return new BagLayout();
		}
	}

	public void save(String profileKey, BagLayout layout)
	{
		if (profileKey == null)
		{
			return;
		}

		configManager.setConfiguration(LootingBagOrganizerConfig.CONFIG_GROUP, profileKey, KEY, gson.toJson(layout));
	}
}
