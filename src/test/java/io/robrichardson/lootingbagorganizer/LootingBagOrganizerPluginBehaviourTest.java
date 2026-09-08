package io.robrichardson.lootingbagorganizer;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.robrichardson.lootingbagorganizer.ui.BagDragOverlay;
import io.robrichardson.lootingbagorganizer.ui.BagInputListener;
import io.robrichardson.lootingbagorganizer.ui.BagViewController;
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
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.overlay.OverlayManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Drives the plugin through its real event handlers and lifecycle against a mocked client, so the
 * wiring in {@link LootingBagOrganizerPlugin} is verified without a logged-in game. Style copied
 * from {@code alch-blocker}'s {@code AlchBlockerPluginBehaviourTest}.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class LootingBagOrganizerPluginBehaviourTest
{
	private static final int BUILD_SCRIPT = 497;
	private static final String PROFILE = "rsprofile.abc";

	@Mock private Client client;
	@Mock private ClientThread clientThread;
	@Mock private ConfigManager configManager;
	@Mock private OverlayManager overlayManager;
	@Mock private MouseManager mouseManager;
	@Mock private KeyManager keyManager;
	@Mock private BagViewController viewController;
	@Mock private BagInputListener inputListener;
	@Mock private BagDragOverlay dragOverlay;

	@InjectMocks private LootingBagOrganizerPlugin plugin;

	@Before
	public void setUp()
	{
		// Client-thread work runs immediately.
		doAnswer(inv ->
		{
			((Runnable) inv.getArgument(0)).run();
			return null;
		}).when(clientThread).invoke(any(Runnable.class));

		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		when(configManager.getRSProfileKey()).thenReturn(PROFILE);
	}

	@Test
	public void startUpRegistersTheOverlayTheMouseListenerAtPositionZeroAndTheKeyListener() throws Exception
	{
		plugin.startUp();

		verify(viewController).startUp();
		verify(overlayManager).add(dragOverlay);
		verify(mouseManager).registerMouseListener(eq(0), eq(inputListener));
		verify(keyManager).registerKeyListener(inputListener);
	}

	@Test
	public void shutDownRestoresThroughTheControllerThenUnregistersEverything() throws Exception
	{
		plugin.startUp();
		plugin.shutDown();

		// The controller's shutDown flushes the pending save and posts restoreGameOrder.
		verify(viewController).shutDown();
		verify(overlayManager).remove(dragOverlay);
		verify(mouseManager).unregisterMouseListener(inputListener);
		verify(keyManager).unregisterKeyListener(inputListener);
	}

	@Test
	public void registeringShuttingDownAndReRegisteringLeavesExactlyOneMouseListener() throws Exception
	{
		plugin.startUp();
		plugin.shutDown();
		plugin.startUp();

		verify(mouseManager, times(2)).registerMouseListener(0, inputListener);
		verify(mouseManager, times(1)).unregisterMouseListener(inputListener);
		verify(keyManager, times(2)).registerKeyListener(inputListener);
		verify(keyManager, times(1)).unregisterKeyListener(inputListener);
	}

	@Test
	public void aSecondStartUpWithoutAShutDownDoesNotDoubleRegister() throws Exception
	{
		plugin.startUp();
		plugin.startUp();

		verify(mouseManager, times(1)).registerMouseListener(0, inputListener);
		verify(keyManager, times(1)).registerKeyListener(inputListener);
	}

	@Test
	public void startingWhileAlreadyLoggedInLoadsTheProfileImmediately() throws Exception
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);

		plugin.startUp();

		verify(viewController).onProfileChanged(PROFILE);
	}

	@Test
	public void startingAtTheLoginScreenDoesNotLoadAProfile() throws Exception
	{
		plugin.startUp();

		verify(viewController, never()).onProfileChanged(any());
	}

	@Test
	public void clientTickDrivesTheControllerPass()
	{
		plugin.onClientTick(new ClientTick());

		verify(viewController).tick();
	}

	@Test
	public void theGridBuildScriptMarksUsDirty()
	{
		plugin.onScriptPostFired(new ScriptPostFired(BUILD_SCRIPT));

		verify(viewController).markDirty();
	}

	@Test
	public void anUnrelatedScriptIsIgnored()
	{
		plugin.onScriptPostFired(new ScriptPostFired(BUILD_SCRIPT + 1));

		verifyNoInteractions(viewController);
	}

	@Test
	public void aLootingBagContainerChangeMarksUsDirty()
	{
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.LOOTING_BAG, null));

		verify(viewController).markDirty();
	}

	@Test
	public void anotherContainerIsIgnored()
	{
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, null));

		verifyNoInteractions(viewController);
	}

	@Test
	public void loadingGroup81MarksUsDirty()
	{
		plugin.onWidgetLoaded(widgetLoaded(InterfaceID.WILDERNESS_LOOTINGBAG));

		verify(viewController).markDirty();
	}

	@Test
	public void loadingAnotherGroupIsIgnored()
	{
		plugin.onWidgetLoaded(widgetLoaded(InterfaceID.BANKMAIN));

		verifyNoInteractions(viewController);
	}

	@Test
	public void closingGroup81CancelsAnyDrag()
	{
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.WILDERNESS_LOOTINGBAG, 3, true));

		verify(viewController).cancelDrag();
	}

	@Test
	public void closingAnotherGroupIsIgnored()
	{
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.BANKMAIN, 3, true));

		verifyNoInteractions(viewController);
	}

	@Test
	public void loggingInLoadsTheLayoutForTheRsProfile()
	{
		plugin.onGameStateChanged(gameState(GameState.LOGGED_IN));

		verify(viewController).onProfileChanged(PROFILE);
	}

	@Test
	public void otherGameStatesDoNotLoadALayout()
	{
		plugin.onGameStateChanged(gameState(GameState.LOGIN_SCREEN));
		plugin.onGameStateChanged(gameState(GameState.HOPPING));

		verify(viewController, never()).onProfileChanged(any());
	}

	@Test
	public void aProfileChangeLoadsTheNewProfileOnTheClientThread()
	{
		when(configManager.getRSProfileKey()).thenReturn("rsprofile.other");

		plugin.onRuneScapeProfileChanged(new RuneScapeProfileChanged(PROFILE, "rsprofile.other"));

		verify(clientThread).invoke(any(Runnable.class));
		verify(viewController).onProfileChanged("rsprofile.other");
	}

	@Test
	public void theBuildScriptConstantIsFourNineSeven()
	{
		// docs/RESEARCH.md section 2: script 497 is the cc_deleteall + rebuild of 81:5.
		assertEquals(497, BUILD_SCRIPT);
	}

	private static GameStateChanged gameState(GameState state)
	{
		GameStateChanged event = new GameStateChanged();
		event.setGameState(state);
		return event;
	}

	private static WidgetLoaded widgetLoaded(int groupId)
	{
		WidgetLoaded event = new WidgetLoaded();
		event.setGroupId(groupId);
		return event;
	}
}
