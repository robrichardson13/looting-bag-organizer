package io.robrichardson.lootingbagorganizer.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class BagLayoutTest
{
	@Test
	public void newLayoutIsEmpty()
	{
		BagLayout layout = new BagLayout();
		assertTrue(layout.getSlots().isEmpty());
		assertEquals(28, BagLayout.SLOTS);
	}

	// -- moveSlot -------------------------------------------------------

	@Test
	public void moveSlotSwapsTwoOccupiedSlots()
	{
		BagLayout layout = new BagLayout();
		layout.setSlot(0, 100);
		layout.setSlot(1, 200);

		layout.moveSlot(0, 1);

		assertEquals(Integer.valueOf(200), layout.slotAt(0));
		assertEquals(Integer.valueOf(100), layout.slotAt(1));
	}

	@Test
	public void moveSlotMovesIntoAnEmptySlot()
	{
		BagLayout layout = new BagLayout();
		layout.setSlot(0, 100);

		layout.moveSlot(0, 5);

		assertNull(layout.slotAt(0));
		assertEquals(Integer.valueOf(100), layout.slotAt(5));
	}

	@Test
	public void moveSlotIsNoOpWhenFromEqualsTo()
	{
		BagLayout layout = new BagLayout();
		layout.setSlot(3, 100);

		layout.moveSlot(3, 3);

		assertEquals(Integer.valueOf(100), layout.slotAt(3));
		// leading/internal nulls are not trimmed, only trailing ones, so the backing list still runs
		// through index 3.
		assertEquals(4, layout.getSlots().size());
	}

	// -- bounds checking --------------------------------------------------

	@Test(expected = IndexOutOfBoundsException.class)
	public void slotAtNegativeIndexThrows()
	{
		new BagLayout().slotAt(-1);
	}

	@Test(expected = IndexOutOfBoundsException.class)
	public void slotAtTooLargeIndexThrows()
	{
		new BagLayout().slotAt(BagLayout.SLOTS);
	}

	@Test(expected = IndexOutOfBoundsException.class)
	public void setSlotOutOfRangeThrows()
	{
		new BagLayout().setSlot(BagLayout.SLOTS, 100);
	}

	@Test(expected = IndexOutOfBoundsException.class)
	public void setSlotNegativeIndexThrows()
	{
		new BagLayout().setSlot(-1, 100);
	}

	@Test(expected = IndexOutOfBoundsException.class)
	public void clearSlotOutOfRangeThrows()
	{
		new BagLayout().clearSlot(BagLayout.SLOTS);
	}

	@Test(expected = IndexOutOfBoundsException.class)
	public void moveSlotFromOutOfRangeThrows()
	{
		new BagLayout().moveSlot(BagLayout.SLOTS, 0);
	}

	@Test(expected = IndexOutOfBoundsException.class)
	public void moveSlotToOutOfRangeThrows()
	{
		new BagLayout().moveSlot(0, BagLayout.SLOTS);
	}

	// -- normalise ----------------------------------------------------------

	@Test
	public void normaliseTruncatesOverLengthList()
	{
		// setSlot/moveSlot never let the backing list exceed SLOTS, but Gson deserialisation (see
		// LayoutStore) sets the private field directly and can hand us an over-length list, so
		// normalise() has to clamp defensively.
		List<Integer> raw = new ArrayList<>();
		for (int i = 0; i < 40; i++)
		{
			// a value at the last in-range index (27) keeps normalise() from trimming it away as a
			// trailing null, so the assertion below is purely about the 40 -> 28 clamp.
			raw.add(i == 27 ? Integer.valueOf(999) : (i >= 28 ? Integer.valueOf(i + 1) : null));
		}
		BagLayout oversized = layoutWithRawSlots(raw);

		oversized.normalise();

		assertEquals(28, oversized.getSlots().size());
		assertEquals(Integer.valueOf(999), oversized.slotAt(27));
	}

	@Test
	public void normaliseStripsTrailingNulls()
	{
		BagLayout layout = layoutWithRawSlots(Arrays.asList(100, null, 200, null, null));
		layout.normalise();
		assertEquals(Arrays.asList(100, null, 200), layout.getSlots());
	}

	@Test
	public void normaliseConvertsNonPositiveIdsToNull()
	{
		BagLayout layout = layoutWithRawSlots(Arrays.asList(100, 0, -5, 200));
		layout.normalise();
		assertEquals(Arrays.asList(100, null, null, 200), layout.getSlots());
	}

	@Test
	public void normaliseOnAlreadyCleanLayoutIsUnchanged()
	{
		BagLayout layout = new BagLayout();
		layout.setSlot(0, 100);
		layout.setSlot(2, 200);
		layout.normalise();
		assertEquals(Arrays.asList(100, null, 200), layout.getSlots());
	}

	// -- setSlot / clearSlot --------------------------------------------------

	@Test
	public void setSlotWithNonPositiveIdClearsTheSlot()
	{
		BagLayout layout = new BagLayout();
		layout.setSlot(0, 100);
		layout.setSlot(0, 0);
		assertNull(layout.slotAt(0));
		assertTrue(layout.getSlots().isEmpty());
	}

	@Test
	public void clearSlotFreesAReservation()
	{
		BagLayout layout = new BagLayout();
		layout.setSlot(0, 100);
		layout.setSlot(1, 200);
		layout.clearSlot(0);
		assertNull(layout.slotAt(0));
		assertEquals(Integer.valueOf(200), layout.slotAt(1));
	}

	@Test
	public void clearSlotPastTheBackingListIsANoOp()
	{
		BagLayout layout = new BagLayout();
		layout.clearSlot(10);
		assertTrue(layout.getSlots().isEmpty());
	}

	@Test
	public void slotAtPastTheBackingListIsFree()
	{
		BagLayout layout = new BagLayout();
		layout.setSlot(0, 100);
		assertNull(layout.slotAt(10));
	}

	// -- getSlots is unmodifiable ------------------------------------------

	@Test
	public void getSlotsIsUnmodifiable()
	{
		BagLayout layout = new BagLayout();
		layout.setSlot(0, 100);
		try
		{
			layout.getSlots().add(200);
			fail("expected UnsupportedOperationException");
		}
		catch (UnsupportedOperationException expected)
		{
			// expected
		}
	}

	// -- equals / hashCode ---------------------------------------------------

	@Test
	public void equalLayoutsAreEqualAndHaveSameHashCode()
	{
		BagLayout a = new BagLayout();
		a.setSlot(0, 100);
		a.setSlot(2, 200);

		BagLayout b = new BagLayout();
		b.setSlot(0, 100);
		b.setSlot(2, 200);

		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
	}

	@Test
	public void equalsComparesNormalisedContent()
	{
		BagLayout a = layoutWithRawSlots(Arrays.asList(100, null, 200, null, null));
		BagLayout b = layoutWithRawSlots(Arrays.asList(100, null, 200));

		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
	}

	@Test
	public void differentLayoutsAreNotEqual()
	{
		BagLayout a = new BagLayout();
		a.setSlot(0, 100);

		BagLayout b = new BagLayout();
		b.setSlot(0, 200);

		assertFalse(a.equals(b));
	}

	// -- helpers --------------------------------------------------------------

	/**
	 * Builds a layout whose private backing list is exactly {@code raw}, bypassing setSlot's
	 * canonicalisation/trimming entirely. This is what Gson deserialisation does in practice
	 * ({@link LayoutStore}), and is the only way an un-normalised {@link BagLayout} can exist, since
	 * every public mutator keeps the invariant intact.
	 */
	private static BagLayout layoutWithRawSlots(List<Integer> raw)
	{
		BagLayout layout = new BagLayout();
		try
		{
			Field field = BagLayout.class.getDeclaredField("slots");
			field.setAccessible(true);
			@SuppressWarnings("unchecked")
			List<Integer> backing = (List<Integer>) field.get(layout);
			backing.addAll(raw);
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError(e);
		}
		return layout;
	}
}
