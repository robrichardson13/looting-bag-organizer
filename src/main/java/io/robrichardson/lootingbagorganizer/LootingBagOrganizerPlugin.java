package io.robrichardson.lootingbagorganizer;

import com.google.inject.Provides;
import io.robrichardson.lootingbagorganizer.ui.BagDragOverlay;
import io.robrichardson.lootingbagorganizer.ui.BagInputListener;
import io.robrichardson.lootingbagorganizer.ui.BagViewController;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * DI wiring, lifecycle and event fan-out. Everything this class does is either registration or
 * forwarding: the arrangement itself lives in {@link BagViewController} (client thread) and
 * {@link BagInputListener} (AWT thread).
 *
 * <p>Nothing here changes what the bag contains and nothing fires a game action; the layout is
 * cosmetic and lives in our own config (see {@code docs/RESEARCH.md} section 1: "There is no
 * server-side reorder action. Every arrangement is cosmetic and lives in our config.").
 *
 * <p>Logging is deliberately verbose at debug level per the board decision: {@code runClient} runs
 * with {@code --debug}, so a manual test session leaves a log we can debug from. Every lifecycle
 * and event edge logs its identifying detail (script id, container id, group id, modal mode).
 */
@Slf4j
@PluginDescriptor(
	name = "Looting Bag Organizer",
	description = "Drag items around inside the looting bag view to arrange them how you like. Client-side only.",
	tags = {"uim", "looting bag", "inventory", "items", "layout", "organize"}
)
public class LootingBagOrganizerPlugin extends Plugin
{
	/**
	 * The clientscript that rebuilds the bag grid: {@code cc_deleteall} on 81:5 then one dynamic
	 * child per container slot, which resets every position we set. It runs on every transmit of
	 * container 516, so the layout is re-applied after every firing rather than once on open.
	 * There is no {@code ScriptID} constant for it; see {@code docs/RESEARCH.md} section 2, which
	 * dumped it out of the live cache. Verify with the Script Inspector under
	 * {@code --developer-mode} (see {@code docs/TESTING.md}).
	 */
	private static final int SCRIPT_LOOTING_BAG_BUILD_ITEMS = 497;

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
	private KeyManager keyManager;

	@Inject
	private BagViewController viewController;

	@Inject
	private BagInputListener inputListener;

	@Inject
	private BagDragOverlay dragOverlay;

	/**
	 * {@code MouseManager.registerMouseListener} does not de-duplicate, so registration is guarded
	 * and always torn down in {@link #shutDown()}.
	 */
	private boolean listenersRegistered;

	@Provides
	LootingBagOrganizerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LootingBagOrganizerConfig.class);
	}

	@Override
	protected void startUp()
	{
		viewController.startUp();

		overlayManager.add(dragOverlay);
		if (!listenersRegistered)
		{
			mouseManager.registerMouseListener(0, inputListener);
			keyManager.registerKeyListener(inputListener);
			listenersRegistered = true;
		}

		// Enabled mid-session: no RuneScapeProfileChanged or LOGGED_IN is coming, so load now.
		GameState gameState = client.getGameState();
		if (gameState == GameState.LOGGED_IN)
		{
			loadProfile("startUp");
		}

		log.debug("Looting Bag Organizer started (gameState={}, listeners registered={})",
			gameState, listenersRegistered);
	}

	@Override
	protected void shutDown()
	{
		// Restore first: the controller flushes a pending save and puts the game's own cell order
		// and title back on the client thread, so a disabled plugin leaves no trace.
		viewController.shutDown();

		overlayManager.remove(dragOverlay);
		if (listenersRegistered)
		{
			mouseManager.unregisterMouseListener(inputListener);
			keyManager.unregisterKeyListener(inputListener);
			listenersRegistered = false;
		}

		log.debug("Looting Bag Organizer stopped");
	}

	/**
	 * The client-thread pass: drains the queue the AWT listener posts to, re-applies the layout if
	 * anything marked it dirty, publishes the volatiles the AWT tier reads. Uses {@code ClientTick}
	 * rather than the overlay's render so the queue is drained even on the frame the window closes.
	 * Not logged: it fires every frame.
	 */
	@Subscribe
	public void onClientTick(ClientTick event)
	{
		viewController.tick();
	}

	/**
	 * The precise "the grid was just rebuilt" signal. Every firing wipes the positions we set, so
	 * every firing marks us dirty.
	 */
	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == SCRIPT_LOOTING_BAG_BUILD_ITEMS)
		{
			log.debug("Script {} fired: looting bag grid rebuilt, re-applying layout",
				event.getScriptId());
			viewController.markDirty();
		}
	}

	/** Belt and braces alongside script 497: the container transmit that makes 497 run. */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.LOOTING_BAG)
		{
			log.debug("Item container {} changed: re-applying layout", event.getContainerId());
			viewController.markDirty();
		}
	}

	/** Belt and braces: the interface opened, whether or not the script id is still 497. */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.WILDERNESS_LOOTINGBAG)
		{
			log.debug("Widget group {} loaded: re-applying layout", event.getGroupId());
			viewController.markDirty();
		}
	}

	/**
	 * The widgets are going away, so any in-flight drag is meaningless. The modal mode is logged
	 * because {@code docs/TESTING.md} section 2 needs it confirmed at runtime (expected 3,
	 * MODAL_CLICKTHROUGH).
	 */
	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.WILDERNESS_LOOTINGBAG)
		{
			log.debug("Widget group {} closed (modalMode={}, unload={}): cancelling any drag",
				event.getGroupId(), event.getModalMode(), event.isUnload());
			viewController.cancelDrag();
		}
	}

	/**
	 * The RS profile key is only stable once logged in; loading on both this and
	 * {@code RuneScapeProfileChanged} is safe because the controller ignores a repeat of the key
	 * it already has.
	 */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		log.debug("Game state changed to {}", event.getGameState());
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			loadProfile("GameStateChanged");
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		log.debug("RS profile changed: {} -> {}", event.getPreviousProfile(), event.getNewProfile());
		loadProfile("RuneScapeProfileChanged");
	}

	/**
	 * Loads (and saves the outgoing) layout for whatever RS profile the config manager reports.
	 * Routed through the client thread because the model is client-thread only and
	 * {@code RuneScapeProfileChanged} can be posted from a config write on any thread.
	 */
	private void loadProfile(String reason)
	{
		String profileKey = configManager.getRSProfileKey();
		log.debug("Profile load requested by {}: rsProfileKey={}", reason, profileKey);
		clientThread.invoke(() -> viewController.onProfileChanged(profileKey));
	}
}
