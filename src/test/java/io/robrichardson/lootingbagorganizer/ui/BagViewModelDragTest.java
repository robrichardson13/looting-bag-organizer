package io.robrichardson.lootingbagorganizer.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.robrichardson.lootingbagorganizer.model.BagLayout;
import java.awt.Point;
import java.awt.Rectangle;
import org.junit.Before;
import org.junit.Test;

/**
 * Card C5 acceptance tests for {@link BagViewModel} hit-testing and drag state. Plain JUnit 4, no
 * Mockito, no RuneLite imports.
 */
public class BagViewModelDragTest
{
	private static final Rectangle GRID_BOUNDS = new Rectangle(10, 20, BagGeometry.PITCH_X * BagGeometry.COLS,
		BagGeometry.PITCH_Y * BagGeometry.ROWS);

	private BagViewModel model;

	@Before
	public void setUp()
	{
		model = new BagViewModel();
		model.setGridBounds(GRID_BOUNDS);
	}

	@Test
	public void slotAtDelegatesToBagGeometry()
	{
		Point centreOfSlot5 = new Point(GRID_BOUNDS.x + BagGeometry.x(5) + 1, GRID_BOUNDS.y + BagGeometry.y(5) + 1);

		assertEquals(BagGeometry.slotAt(GRID_BOUNDS, centreOfSlot5.x, centreOfSlot5.y), model.slotAt(centreOfSlot5));
		assertEquals(5, model.slotAt(centreOfSlot5));
	}

	@Test
	public void slotAtOutsideGridIsMinusOne()
	{
		assertEquals(-1, model.slotAt(new Point(GRID_BOUNDS.x - 5, GRID_BOUNDS.y - 5)));
	}

	@Test
	public void endDragWithMinusOneCancelsAndReturnsFalse()
	{
		BagLayout layout = new BagLayout();
		layout.setSlot(0, 100);
		layout.setSlot(1, 200);
		model.setLayout(layout);

		model.beginDrag(0);
		boolean changed = model.endDrag(-1);

		assertFalse(changed);
		assertFalse(model.isDragging());
		// layout untouched
		assertEquals(Integer.valueOf(100), layout.slotAt(0));
		assertEquals(Integer.valueOf(200), layout.slotAt(1));
	}

	@Test
	public void endDragOnSourceSlotCancelsAndReturnsFalse()
	{
		BagLayout layout = new BagLayout();
		layout.setSlot(0, 100);
		model.setLayout(layout);

		model.beginDrag(0);
		boolean changed = model.endDrag(0);

		assertFalse(changed);
		assertFalse(model.isDragging());
		assertEquals(Integer.valueOf(100), layout.slotAt(0));
	}

	@Test
	public void endDragOnAnotherSlotSwapsInLayoutAndReturnsTrue()
	{
		BagLayout layout = new BagLayout();
		layout.setSlot(0, 100);
		layout.setSlot(1, 200);
		model.setLayout(layout);

		model.beginDrag(0);
		boolean changed = model.endDrag(1);

		assertTrue(changed);
		assertFalse(model.isDragging());
		assertEquals(Integer.valueOf(200), layout.slotAt(0));
		assertEquals(Integer.valueOf(100), layout.slotAt(1));
	}

	@Test
	public void droppingOntoEmptySlotMoves()
	{
		BagLayout layout = new BagLayout();
		layout.setSlot(0, 100);
		model.setLayout(layout);

		model.beginDrag(0);
		boolean changed = model.endDrag(5);

		assertTrue(changed);
		assertNull(layout.slotAt(0));
		assertEquals(Integer.valueOf(100), layout.slotAt(5));
	}

	@Test
	public void droppingOntoOccupiedSlotSwaps()
	{
		BagLayout layout = new BagLayout();
		layout.setSlot(0, 100);
		layout.setSlot(5, 200);
		model.setLayout(layout);

		model.beginDrag(0);
		boolean changed = model.endDrag(5);

		assertTrue(changed);
		assertEquals(Integer.valueOf(200), layout.slotAt(0));
		assertEquals(Integer.valueOf(100), layout.slotAt(5));
	}

	@Test
	public void afterSwapBothAffectedSlotsArePinnedInTheLayout()
	{
		BagLayout layout = new BagLayout();
		layout.setSlot(0, 100);
		layout.setSlot(5, 200);
		model.setLayout(layout);

		model.beginDrag(0);
		model.endDrag(5);

		assertTrue(layout.slotAt(0) != null);
		assertTrue(layout.slotAt(5) != null);
	}

	/**
	 * Regression: the layout stores only deliberate placements, so a freshly-arranged bag has an
	 * empty layout. A drag has to pin what it touches, otherwise {@code moveSlot} swaps two nulls
	 * and the next arrange puts the item straight back in container order.
	 */
	@Test
	public void draggingAnUnpinnedItemPinsItIntoItsNewSlot()
	{
		BagLayout layout = new BagLayout();
		model.setLayout(layout);
		// Two items, nothing pinned: they auto-place into slots 0 and 1.
		model.arrange(containerOf(100, 200));

		model.beginDrag(0);
		boolean changed = model.endDrag(5);

		assertTrue(changed);
		assertEquals(Integer.valueOf(100), layout.slotAt(5));
		assertNull(layout.slotAt(0));
	}

	/** Swapping two auto-placed items pins both, so neither springs back. */
	@Test
	public void draggingOneUnpinnedItemOntoAnotherPinsBoth()
	{
		BagLayout layout = new BagLayout();
		model.setLayout(layout);
		model.arrange(containerOf(100, 200));

		model.beginDrag(0);
		boolean changed = model.endDrag(1);

		assertTrue(changed);
		assertEquals(Integer.valueOf(100), layout.slotAt(1));
		assertEquals(Integer.valueOf(200), layout.slotAt(0));
	}

	/** A pinned item dropped on a slot reserved for an absent item still swaps the reservations. */
	@Test
	public void droppingOnAReservedButEmptySlotSwapsTheReservation()
	{
		BagLayout layout = new BagLayout();
		layout.setSlot(0, 100);
		layout.setSlot(5, 999); // reserved for an item that is not in the bag
		model.setLayout(layout);
		model.arrange(containerOf(100));

		model.beginDrag(0);
		boolean changed = model.endDrag(5);

		assertTrue(changed);
		assertEquals(Integer.valueOf(100), layout.slotAt(5));
		assertEquals(Integer.valueOf(999), layout.slotAt(0));
	}

	/** Dragging one genuinely empty, unreserved cell onto another changes nothing. */
	@Test
	public void dragBetweenTwoEmptyUnreservedSlotsChangesNothing()
	{
		BagLayout layout = new BagLayout();
		model.setLayout(layout);
		model.arrange(containerOf(100));

		model.beginDrag(20);
		boolean changed = model.endDrag(21);

		assertFalse(changed);
		assertTrue(layout.getSlots().isEmpty());
	}

	/**
	 * Regression for card 19: on a fresh (empty) layout, 4 items auto-place into slots 0-3.
	 * Dragging slot 1's item into empty slot 6 leaves slot 1 unpinned, so the next {@code arrange}
	 * treats it as free again and every item after it in container order slides forward to fill
	 * the gap: item2 (originally slot 2) lands at slot 1, item3 (originally slot 3) lands at
	 * slot 2. Only the dragged item should move; everything else must stay put.
	 */
	@Test
	public void draggingIntoAnEmptySlotDoesNotShiftUntouchedItems()
	{
		BagLayout layout = new BagLayout();
		model.setLayout(layout);
		int[] container = containerOf(100, 200, 300, 400);
		model.arrange(container);

		model.beginDrag(1);
		boolean changed = model.endDrag(6);
		assertTrue(changed);

		int[] result = model.arrange(container);

		assertEquals(0, result[0]);
		assertEquals(6, result[1]);
		assertEquals(2, result[2]);
		assertEquals(3, result[3]);
	}

	/** After the freeze, the slot the dragged item vacated is free again for a genuinely new item. */
	@Test
	public void aNewItemAfterTheDragLandsInTheVacatedSlot()
	{
		BagLayout layout = new BagLayout();
		model.setLayout(layout);
		int[] container = containerOf(100, 200, 300, 400);
		model.arrange(container);

		model.beginDrag(1);
		model.endDrag(6);

		int[] withNewItem = containerOf(100, 200, 300, 400, 500);
		int[] result = model.arrange(withNewItem);

		assertEquals(0, result[0]);
		assertEquals(6, result[1]);
		assertEquals(2, result[2]);
		assertEquals(3, result[3]);
		assertEquals(1, result[4]);
	}

	/** Re-opening the view with the same snapshot after a drag is stable: arranging twice agrees. */
	@Test
	public void reArrangingTheSameSnapshotAfterADragIsStable()
	{
		BagLayout layout = new BagLayout();
		model.setLayout(layout);
		int[] container = containerOf(100, 200, 300, 400);
		model.arrange(container);

		model.beginDrag(1);
		model.endDrag(6);

		int[] first = model.arrange(container);
		int[] second = model.arrange(container);

		assertArrayEquals(first, second);
	}

	private static int[] containerOf(int... ids)
	{
		int[] container = new int[BagLayout.SLOTS];
		for (int i = 0; i < container.length; i++)
		{
			container[i] = i < ids.length ? ids[i] : -1;
		}
		return container;
	}

	@Test
	public void cancelDragClearsState()
	{
		BagLayout layout = new BagLayout();
		layout.setSlot(0, 100);
		layout.setSlot(1, 200);
		model.setLayout(layout);

		model.beginDrag(0);
		model.updateDrag(new Point(50, 50));
		model.cancelDrag();

		assertFalse(model.isDragging());
		assertEquals(-1, model.getDragSource());
		assertEquals(-1, model.getHoverSlot());

		boolean changed = model.endDrag(1);
		assertFalse(changed);
		assertEquals(Integer.valueOf(100), layout.slotAt(0));
		assertEquals(Integer.valueOf(200), layout.slotAt(1));
	}

	@Test
	public void endDragWithoutBeginDragIsHarmlessFalse()
	{
		boolean changed = model.endDrag(3);

		assertFalse(changed);
		assertFalse(model.isDragging());
	}

	@Test
	public void beginDragSetsDraggingSourceAndHoverSlot()
	{
		model.beginDrag(7);

		assertTrue(model.isDragging());
		assertEquals(7, model.getDragSource());
		assertEquals(7, model.getHoverSlot());
	}

	@Test
	public void updateDragTracksPointAndHoverSlot()
	{
		model.beginDrag(0);

		Point p = new Point(GRID_BOUNDS.x + BagGeometry.x(9) + 1, GRID_BOUNDS.y + BagGeometry.y(9) + 1);
		model.updateDrag(p);

		assertEquals(p, model.getDragPoint());
		assertEquals(9, model.getHoverSlot());
	}
}
