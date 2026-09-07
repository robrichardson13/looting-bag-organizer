package io.robrichardson.lootingbagorganizer;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Dev launcher for {@code ./gradlew runClient}, not a unit test. */
public class LootingBagOrganizerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(LootingBagOrganizerPlugin.class);
		RuneLite.main(args);
	}
}
