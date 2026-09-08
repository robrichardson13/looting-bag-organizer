package io.robrichardson.lootingbagorganizer.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.robrichardson.lootingbagorganizer.model.BagLayout;
import java.util.HashSet;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

/**
 * Acceptance tests for {@link BagViewModel#arrange(int[])}. Plain JUnit 4, no Mockito, no
 * RuneLite imports.
 */
public class BagViewModelArrangeTest
{
	private static final int EMPTY = -1;

	private BagViewModel model;

	@Before
	public void setUp()
	{
		model = new BagViewModel();
	}

	@Test
	public void emptyLayoutPlacesItemsInContainerOrder()
	{
		int[] container = {100, 101, 102, EMPTY, 103};

		int[] result = model.arrange(container);

		assertEquals(0, result[0]);
		assertEquals(1, result[1]);
		assertEquals(2, result[2]);
		assertEquals(-1, result[3]);
		assertEquals(3, result[4]);
	}

	@Test
	public void pinnedIdLandsInItsSlotEvenWhenContainerIndexMoves()
	{
		BagLayout layout = new BagLayout();
		layout.setSlot(10, 200);
		model.setLayout(layout);

		// item 200 is at container index 3, not index 10
		int[] container = {100, 101, 102, 200, 103};

		int[] result = model.arrange(container);

		assertEquals(10, result[3]);
		// the other items still fill the remaining free (unreserved) slots in container order
		assertEquals(0, result[0]);
		assertEquals(1, result[1]);
		assertEquals(2, result[2]);
		assertEquals(3, result[4]);
	}

	@Test
	public void duplicateNonStackableCopiesWithOnePinnedAllPlaceDistinctly()
	{
		BagLayout layout = new BagLayout();
		layout.setSlot(5, 300);
		model.setLayout(layout);

		int[] container = {300, 300, 300};

		int[] result = model.arrange(container);

		// exactly one copy claims the pinned slot
		int pinnedCount = 0;
		for (int slot : result)
		{
			if (slot == 5)
			{
				pinnedCount++;
			}
		}
		assertEquals(1, pinnedCount);

		// all three copies placed, no duplicate slots, none dropped (-1)
		Set<Integer> usedSlots = new HashSet<>();
		for (int slot : result)
		{
			assertTrue("copy was dropped", slot >= 0);
			assertTrue("slot used twice", usedSlots.add(slot));
		}
		assertEquals(3, usedSlots.size());
	}

	@Test
	public void reservationForAbsentItemStaysFreeUntilBagNeedsIt()
	{
		BagLayout layout = new BagLayout();
		layout.setSlot(0, 999); // 999 never shows up in the container
		model.setLayout(layout);

		int[] container = {100};

		int[] result = model.arrange(container);

		// slot 0 is reserved for the absent 999, so the incoming item does not land there
		assertFalse(0 == result[0]);
		assertFalse(model.consumeLayoutChanged());
	}

	@Test
	public void reservationForAbsentItemIsConsumedWhenBagIsFullEnoughToNeedIt()
	{
		BagLayout layout = new BagLayout();
		// slot 0 reserved for an id that will never appear; slots 1..27 free
		layout.setSlot(0, 999);
		model.setLayout(layout);

		int[] container = new int[28];
		for (int i = 0; i < 28; i++)
		{
			container[i] = 500 + i;
		}

		int[] result = model.arrange(container);

		// every item placed, including into the reserved slot, since all 28 slots are needed
		Set<Integer> usedSlots = new HashSet<>();
		for (int slot : result)
		{
			assertTrue("item was dropped", slot >= 0);
			assertTrue("slot used twice", usedSlots.add(slot));
		}
		assertEquals(28, usedSlots.size());
		assertTrue(usedSlots.contains(0));

		assertTrue(model.consumeLayoutChanged());
		// consumeLayoutChanged is a one-shot flag
		assertFalse(model.consumeLayoutChanged());
	}

	@Test
	public void fullBagWithReservationsForAbsentIdsStillPlacesAllItems()
	{
		BagLayout layout = new BagLayout();
		for (int i = 0; i < 28; i++)
		{
			// pin every slot to an id that is never present in the container
			layout.setSlot(i, 9000 + i);
		}
		model.setLayout(layout);

		int[] container = new int[28];
		for (int i = 0; i < 28; i++)
		{
			container[i] = 1000 + i;
		}

		int[] result = model.arrange(container);

		Set<Integer> usedSlots = new HashSet<>();
		for (int slot : result)
		{
			assertTrue("item was dropped", slot >= 0);
			assertTrue("slot used twice", usedSlots.add(slot));
		}
		assertEquals(28, usedSlots.size());
		assertTrue(model.consumeLayoutChanged());
	}

	@Test
	public void outputIsAPermutationNoDuplicateDisplaySlots()
	{
		BagLayout layout = new BagLayout();
		layout.setSlot(7, 200);
		layout.setSlot(3, 201);
		model.setLayout(layout);

		int[] container = {201, 202, 200, 203, EMPTY, 204};

		int[] result = model.arrange(container);

		Set<Integer> usedSlots = new HashSet<>();
		for (int slot : result)
		{
			if (slot == -1)
			{
				continue;
			}
			assertTrue("slot used twice", usedSlots.add(slot));
		}
	}

	@Test
	public void zeroIsTreatedAsEmptyLikeNegativeOne()
	{
		int[] container = {0, 100, EMPTY, 101};

		int[] result = model.arrange(container);

		assertEquals(-1, result[0]);
		assertEquals(-1, result[2]);
		assertEquals(0, result[1]);
		assertEquals(1, result[3]);
	}
}
