package io.robrichardson.lootingbagorganizer;

import com.google.inject.Provides;
import io.robrichardson.lootingbagorganizer.model.LayoutStore;
import io.robrichardson.lootingbagorganizer.ui.BagViewController;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
	name = "Looting Bag Organizer",
	description = "Drag items around inside the looting bag view to arrange them how you like. Client-side only.",
	tags = {"uim", "looting bag", "inventory", "items", "layout", "organize"}
)
public class LootingBagOrganizerPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private LayoutStore layoutStore;

	@Inject
	private BagViewController viewController;

	@Provides
	LootingBagOrganizerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LootingBagOrganizerConfig.class);
	}

	@Override
	protected void startUp()
	{
		// TODO: register the overlay and the positional mouse listener (position 0, exactly once),
		// then load the saved layout for the current RS profile if already logged in.
		log.debug("Looting Bag Organizer started");
	}

	@Override
	protected void shutDown()
	{
		// TODO: unregister everything registered in startUp() and restore the bag widgets to the
		// game's own order so a disabled plugin leaves no trace.
		log.debug("Looting Bag Organizer stopped");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			// TODO: load layout for the RS profile (see onRuneScapeProfileChanged).
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		// TODO: the RS profile key is only stable after this fires; (re)load the layout here.
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.WILDERNESS_LOOTINGBAG)
		{
			// TODO: the bag "View" interface just opened. Snapshot the container and apply the layout.
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.WILDERNESS_LOOTINGBAG)
		{
			// TODO: cancel any in-flight drag; the widgets are gone.
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.LOOTING_BAG)
		{
			// TODO: contents changed while the view is open (deposit, bank-all); re-apply the layout.
		}
	}
}
