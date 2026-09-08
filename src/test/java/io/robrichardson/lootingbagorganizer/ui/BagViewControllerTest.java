package io.robrichardson.lootingbagorganizer.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.robrichardson.lootingbagorganizer.model.BagLayout;
import io.robrichardson.lootingbagorganizer.model.LayoutStore;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Drives {@link BagViewController} against a mocked client, in the style of alch-blocker's
 * behaviour test: build the widgets and the container the game would build, run the real
 * client-thread pass, and assert on what was written back to the widgets.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class BagViewControllerTest
{
	private static final int ITEM_A = 1001;
	private static final int ITEM_B = 1002;
	private static final int ITEM_C = 1003;

	private static final Rectangle GRID = new Rectangle(100, 200, 176, 224);

	@Mock private Client client;
	@Mock private ClientThread clientThread;
	@Mock private LayoutStore layoutStore;

	private BagViewController controller;
	private Widget items;
	private Widget title;
	private Cell[] cells;
	private long now = 10_000L;

	/** A stateful stand-in for one dynamic child of 81:5. */
	private static class Cell
	{
		final Widget widget = mock(Widget.class);
		final int index;
		int x;
		int y;
		int revalidations;

		Cell(int index, boolean hidden)
		{
			this.index = index;
			this.x = BagGeometry.x(index);
			this.y = hidden ? 0 : BagGeometry.y(index);
			if (hidden)
			{
				this.x = 0;
			}

			when(widget.isSelfHidden()).thenReturn(hidden);
			when(widget.getOriginalX()).thenAnswer(inv -> x);
			when(widget.getOriginalY()).thenAnswer(inv -> y);
			doAnswer(inv ->
			{
				x = inv.getArgument(0);
				return widget;
			}).when(widget).setOriginalX(anyInt());
			doAnswer(inv ->
			{
				y = inv.getArgument(0);
				return widget;
			}).when(widget).setOriginalY(anyInt());
			doAnswer(inv ->
			{
				revalidations++;
				return null;
			}).when(widget).revalidate();
		}
	}

	@Before
	public void setUp()
	{
		controller = new BagViewController(client, clientThread, layoutStore);
		controller.clock = () -> now;

		items = mock(Widget.class);
		title = mock(Widget.class);
		when(items.isHidden()).thenReturn(false);
		when(items.getBounds()).thenReturn(GRID);
		when(title.getText()).thenReturn("Looting bag");
		when(client.getWidget(InterfaceID.WildernessLootingbag.ITEMS)).thenReturn(items);
		when(client.getWidget(InterfaceID.WildernessLootingbag.TITLE)).thenReturn(title);

		doAnswer(inv ->
		{
			((Runnable) inv.getArgument(0)).run();
			return null;
		}).when(clientThread).invoke(any(Runnable.class));

		when(layoutStore.load(any())).thenReturn(new BagLayout());
	}

	/** Builds 28 children, the first {@code occupied} of them visible, like script 497 does. */
	private void giveChildren(int occupied)
	{
		cells = new Cell[BagLayout.SLOTS];
		Widget[] children = new Widget[BagLayout.SLOTS];
		for (int i = 0; i < BagLayout.SLOTS; i++)
		{
			cells[i] = new Cell(i, i >= occupied);
			children[i] = cells[i].widget;
		}
		when(items.getDynamicChildren()).thenReturn(children);
	}

	private void giveContainer(int... ids)
	{
		ItemContainer container = mock(ItemContainer.class);
		Item[] contents = new Item[ids.length];
		for (int i = 0; i < ids.length; i++)
		{
			contents[i] = new Item(ids[i], 1);
		}
		when(container.getItems()).thenReturn(contents);
		when(client.getItemContainer(InventoryID.LOOTING_BAG)).thenReturn(container);
	}

	private void openBagWith(int... ids)
	{
		giveChildren(ids.length);
		giveContainer(ids);
	}

	@Test
	public void appliesAPinnedItemToItsSlot()
	{
		openBagWith(ITEM_A, ITEM_B, ITEM_C);
		BagLayout layout = new BagLayout();
		layout.setSlot(5, ITEM_B);
		controller.getModel().setLayout(layout);

		controller.startUp();
		controller.tick();

		verify(cells[1].widget).setOriginalX(44);
		verify(cells[1].widget).setOriginalY(32);
		verify(cells[1].widget).revalidate();
		// The unpinned items take the lowest free unreserved slots, 0 and 1.
		assertEquals(BagGeometry.x(0), cells[0].x);
		assertEquals(BagGeometry.y(0), cells[0].y);
		assertEquals(BagGeometry.x(1), cells[2].x);
		assertEquals(BagGeometry.y(1), cells[2].y);
	}

	@Test
	public void hiddenChildrenAreNeverTouched()
	{
		openBagWith(ITEM_A, ITEM_B);

		controller.startUp();
		controller.tick();

		for (int i = 2; i < BagLayout.SLOTS; i++)
		{
			verify(cells[i].widget, never()).setOriginalX(anyInt());
			verify(cells[i].widget, never()).setOriginalY(anyInt());
			verify(cells[i].widget, never()).revalidate();
			verify(cells[i].widget, never()).setHidden(true);
			verify(cells[i].widget, never()).setItemId(anyInt());
		}
	}

	@Test
	public void depositFlowIsLeftCompletelyAlone()
	{
		openBagWith(ITEM_A, ITEM_B, ITEM_C);
		when(title.getText()).thenReturn("Add to bag");
		BagLayout layout = new BagLayout();
		layout.setSlot(5, ITEM_B);
		controller.getModel().setLayout(layout);

		controller.startUp();
		controller.tick();

		assertFalse(controller.isViewOpen());
		for (Cell cell : cells)
		{
			verify(cell.widget, never()).setOriginalX(anyInt());
			verify(cell.widget, never()).setOriginalY(anyInt());
		}
	}

	@Test
	public void aMissingGridWidgetIsANoOp()
	{
		when(client.getWidget(InterfaceID.WildernessLootingbag.ITEMS)).thenReturn(null);

		controller.startUp();
		controller.tick();

		assertFalse(controller.isViewOpen());
		assertEquals(new Rectangle(), controller.getGridBounds());
		assertTrue(controller.getSlots().isEmpty());
	}

	@Test
	public void aNullContainerIsANoOp()
	{
		giveChildren(3);
		when(client.getItemContainer(InventoryID.LOOTING_BAG)).thenReturn(null);

		controller.startUp();
		controller.tick();

		assertTrue(controller.isViewOpen());
		for (Cell cell : cells)
		{
			verify(cell.widget, never()).setOriginalX(anyInt());
		}
	}

	@Test
	public void publishesViewOpenGridBoundsAndSlotBounds()
	{
		openBagWith(ITEM_A, ITEM_B);

		controller.startUp();
		controller.tick();

		assertTrue(controller.isViewOpen());
		assertEquals(GRID, controller.getGridBounds());

		List<BagSlot> slots = controller.getSlots();
		assertEquals(BagLayout.SLOTS, slots.size());
		assertEquals(ITEM_A, slots.get(0).itemId);
		assertEquals(ITEM_B, slots.get(1).itemId);
		assertTrue(slots.get(2).isEmpty());
		assertEquals(BagGeometry.cell(1, GRID), slots.get(1).bounds);
	}

	@Test
	public void reappliesAfterEveryRebuild()
	{
		openBagWith(ITEM_A, ITEM_B);
		controller.startUp();
		controller.tick();

		// Script 497 deleted and recreated the children, resetting every position.
		giveChildren(2);
		BagLayout layout = new BagLayout();
		layout.setSlot(3, ITEM_A);
		controller.getModel().setLayout(layout);

		controller.tick();        // not dirty yet: nothing re-positioned
		assertEquals(BagGeometry.x(0), cells[0].x);

		controller.markDirty();
		controller.tick();
		assertEquals(BagGeometry.x(3), cells[0].x);
		assertEquals(BagGeometry.y(3), cells[0].y);
	}

	@Test
	public void restoreGameOrderPutsChildrenBackAtTheGamePositions()
	{
		openBagWith(ITEM_A, ITEM_B, ITEM_C);
		BagLayout layout = new BagLayout();
		layout.setSlot(9, ITEM_A);
		controller.getModel().setLayout(layout);

		controller.startUp();
		controller.tick();
		assertEquals(BagGeometry.x(9), cells[0].x);
		assertEquals(BagGeometry.y(9), cells[0].y);

		controller.restoreGameOrder();

		for (int i = 0; i < 3; i++)
		{
			assertEquals(BagGeometry.x(i), cells[i].x);
			assertEquals(BagGeometry.y(i), cells[i].y);
		}
		assertFalse(controller.isViewOpen());
		assertTrue(controller.getSlots().isEmpty());
	}

	@Test
	public void restoreGameOrderWithoutAnApplyStillUsesTheGameGrid()
	{
		giveChildren(4);
		for (Cell cell : cells)
		{
			cell.x = 0;
			cell.y = 0;
		}

		controller.restoreGameOrder();

		for (int i = 0; i < 4; i++)
		{
			assertEquals(BagGeometry.x(i), cells[i].x);
			assertEquals(BagGeometry.y(i), cells[i].y);
		}
	}

	@Test
	public void tickRunsQueuedRunnablesInOrderAndSwallowsAThrow()
	{
		List<String> ran = new ArrayList<>();
		controller.post(() -> ran.add("first"));
		controller.post(() ->
		{
			throw new IllegalStateException("boom");
		});
		controller.post(() -> ran.add("third"));

		controller.tick();

		assertEquals(java.util.Arrays.asList("first", "third"), ran);
	}

	@Test
	public void aDirtyLayoutIsSavedAtMostOnceASecond()
	{
		openBagWith(ITEM_A, ITEM_B);
		controller.onProfileChanged("profile-1");
		controller.startUp();
		// The first pass arranges the bag; a drag can only ever follow one, since the input listener
		// arms from the slots this pass publishes.
		controller.tick();

		controller.beginDrag(0);
		controller.endDrag(6);

		controller.tick();
		verify(layoutStore, never()).save(eq("profile-1"), any());

		now += 1000L;
		controller.tick();
		verify(layoutStore).save(eq("profile-1"), any());

		now += 5000L;
		controller.tick();
		verify(layoutStore).save(eq("profile-1"), any());     // still exactly one write
	}

	@Test
	public void aDropThatChangesNothingIsNeverSaved()
	{
		openBagWith(ITEM_A, ITEM_B);
		controller.onProfileChanged("profile-1");
		controller.startUp();

		controller.beginDrag(0);
		controller.endDrag(-1);

		now += 5000L;
		controller.tick();

		verify(layoutStore, never()).save(eq("profile-1"), any());
	}

	@Test
	public void profileChangeLoadsTheNewLayoutAndFlushesTheOldOne()
	{
		openBagWith(ITEM_A, ITEM_B);
		controller.onProfileChanged("profile-1");
		controller.startUp();
		controller.tick();
		controller.beginDrag(0);
		controller.endDrag(6);

		BagLayout second = new BagLayout();
		second.setSlot(2, ITEM_C);
		when(layoutStore.load("profile-2")).thenReturn(second);

		controller.onProfileChanged("profile-2");

		verify(layoutStore).save(eq("profile-1"), any());
		assertEquals(Integer.valueOf(ITEM_C), controller.getModel().getLayout().slotAt(2));
		assertEquals("profile-2", controller.getProfileKey());
	}

	@Test
	public void shutDownSavesAndRestoresTheGameOrder()
	{
		openBagWith(ITEM_A, ITEM_B, ITEM_C);
		controller.onProfileChanged("profile-1");
		controller.startUp();
		controller.tick();
		controller.beginDrag(0);
		controller.endDrag(9);
		controller.markDirty();
		controller.tick();

		controller.shutDown();

		verify(layoutStore).save(eq("profile-1"), any());
		for (int i = 0; i < 3; i++)
		{
			assertEquals(BagGeometry.x(i), cells[i].x);
			assertEquals(BagGeometry.y(i), cells[i].y);
		}
	}

	@Test
	public void titleTextFormatsTakenOverTwentyEight()
	{
		assertEquals("Looting bag (0/28)", BagViewController.titleText(0));
		assertEquals("Looting bag (12/28)", BagViewController.titleText(12));
		assertEquals("Looting bag (28/28)", BagViewController.titleText(28));
	}

	@Test
	public void applyingSetsTheTitleToTheTakenFreeCount()
	{
		openBagWith(ITEM_A, ITEM_B, ITEM_C);

		controller.startUp();
		controller.tick();

		verify(title).setText("Looting bag (3/28)");
	}

	@Test
	public void depositFlowNeverGetsTheCountedTitle()
	{
		openBagWith(ITEM_A, ITEM_B, ITEM_C);
		when(title.getText()).thenReturn("Add to bag");

		controller.startUp();
		controller.tick();

		verify(title, never()).setText(any());
	}

	@Test
	public void aNullContainerNeverSetsTheTitle()
	{
		giveChildren(3);
		when(client.getItemContainer(InventoryID.LOOTING_BAG)).thenReturn(null);

		controller.startUp();
		controller.tick();

		verify(title, never()).setText(any());
	}

	@Test
	public void restoreGameOrderResetsTheTitle()
	{
		openBagWith(ITEM_A, ITEM_B, ITEM_C);
		controller.startUp();
		controller.tick();
		verify(title).setText("Looting bag (3/28)");

		controller.restoreGameOrder();

		verify(title).setText("Looting bag");
	}

	@Test
	public void restoreGameOrderResetsTheTitleEvenWithoutAnApply()
	{
		when(client.getWidget(InterfaceID.WildernessLootingbag.ITEMS)).thenReturn(null);

		controller.restoreGameOrder();

		verify(title).setText("Looting bag");
	}

	@Test
	public void closingTheViewCancelsAnInFlightDrag()
	{
		openBagWith(ITEM_A, ITEM_B);
		controller.startUp();
		controller.tick();
		controller.beginDrag(0);
		assertTrue(controller.getModel().isDragging());

		when(client.getWidget(InterfaceID.WildernessLootingbag.ITEMS)).thenReturn(null);
		controller.tick();

		assertFalse(controller.getModel().isDragging());
		assertFalse(controller.isViewOpen());
	}

	/**
	 * Regression: {@code tick()} used to clear the dirty flag before {@code applyLayout} ran, so a
	 * pass that bailed out (container 516 not transmitted yet, children not built yet) consumed the
	 * only signal it would get and the layout was never applied for that open window.
	 */
	@Test
	public void anApplyThatBailsOutStaysDirtyAndRetriesOnTheNextTick()
	{
		giveChildren(3);
		when(client.getItemContainer(InventoryID.LOOTING_BAG)).thenReturn(null);
		BagLayout layout = new BagLayout();
		layout.setSlot(5, ITEM_B);
		controller.getModel().setLayout(layout);

		controller.startUp();
		controller.tick();

		verify(cells[1].widget, never()).setOriginalX(anyInt());

		// The container arrives a frame later, with nothing else marking us dirty.
		giveContainer(ITEM_A, ITEM_B, ITEM_C);
		controller.tick();

		verify(cells[1].widget).setOriginalX(44);
		verify(cells[1].widget).setOriginalY(32);
	}

	/** The same, for the frame where 81:5 exists but script 497 has not built its children yet. */
	@Test
	public void anApplyWithNoDynamicChildrenYetStaysDirty()
	{
		when(items.getDynamicChildren()).thenReturn(new Widget[0]);
		giveContainer(ITEM_A, ITEM_B, ITEM_C);

		controller.startUp();
		controller.tick();

		giveChildren(3);
		controller.tick();

		verify(cells[0].widget).revalidate();
	}

	/**
	 * The end-to-end shape of the headline bug: with a fresh (empty) layout every cell is
	 * auto-placed, so a drop that did not pin what it touched swapped two nulls and the very next
	 * apply put the item straight back where the container order had it.
	 */
	@Test
	public void draggingAnAutoPlacedItemSurvivesTheNextApply()
	{
		openBagWith(ITEM_A, ITEM_B, ITEM_C);
		controller.onProfileChanged("profile-1");
		controller.startUp();
		controller.tick();

		controller.beginDrag(0);
		controller.endDrag(6);
		controller.tick();

		assertEquals(BagGeometry.x(6), cells[0].x);
		assertEquals(BagGeometry.y(6), cells[0].y);
		assertEquals(Integer.valueOf(ITEM_A), controller.getModel().getLayout().slotAt(6));

		// And it stays there across a rebuild (script 497 re-fires, we mark dirty again).
		controller.markDirty();
		controller.tick();
		assertEquals(BagGeometry.x(6), cells[0].x);
		assertEquals(BagGeometry.y(6), cells[0].y);
	}

	/** Disabling the plugin while the deposit dialog is open must not relabel its title. */
	@Test
	public void shutDownDuringTheDepositFlowLeavesTheTitleAlone()
	{
		openBagWith(ITEM_A, ITEM_B);
		when(title.getText()).thenReturn("Add to bag");

		controller.startUp();
		controller.tick();
		controller.shutDown();

		verify(title, never()).setText(any());
	}
}
