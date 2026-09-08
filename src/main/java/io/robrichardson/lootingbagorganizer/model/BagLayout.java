package io.robrichardson.lootingbagorganizer.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The player's chosen arrangement of the looting bag: which item sits in which of the 28 slots.
 *
 * <p>Pure data, no RuneLite imports. Persisted as JSON per RS profile by {@link LayoutStore}.
 *
 * <p>Slots are sparse: {@code null} is a deliberate empty slot, trailing nulls are never stored.
 * The bag can hold several non-stackable copies of one id, so a slot is addressed by index, never
 * looked up by id alone (see CLAUDE.md, "Rules of thumb").
 */
public class BagLayout
{
	public static final int SLOTS = 28;

	private final List<Integer> slots = new ArrayList<>();

	public List<Integer> getSlots()
	{
		return Collections.unmodifiableList(slots);
	}

	/**
	 * @param index slot index, {@code 0 <= index < SLOTS}
	 * @return the reserved item id at {@code index}, or {@code null} if the slot is free. Reading
	 * past the end of the sparse backing list is the same as reading a free slot.
	 */
	public Integer slotAt(int index)
	{
		checkIndex(index);
		return index < slots.size() ? slots.get(index) : null;
	}

	/**
	 * Reserves {@code index} for {@code itemId}, or frees it if {@code itemId} is {@code null} or
	 * not a positive id.
	 */
	public void setSlot(int index, Integer itemId)
	{
		checkIndex(index);
		ensureCapacity(index + 1);
		slots.set(index, canonicalise(itemId));
		trimTrailingNulls();
	}

	public void clearSlot(int index)
	{
		checkIndex(index);
		if (index < slots.size())
		{
			slots.set(index, null);
			trimTrailingNulls();
		}
	}

	/**
	 * Swaps the entries at {@code from} and {@code to}. A no-op when {@code from == to}.
	 */
	public void moveSlot(int from, int to)
	{
		checkIndex(from);
		checkIndex(to);
		if (from == to)
		{
			return;
		}
		ensureCapacity(Math.max(from, to) + 1);
		Integer temp = slots.get(from);
		slots.set(from, slots.get(to));
		slots.set(to, temp);
		trimTrailingNulls();
	}

	/**
	 * Repairs anything a saved layout can get wrong: over-length lists, trailing nulls, negative or
	 * zero ids. Always call after deserialising.
	 */
	public void normalise()
	{
		List<Integer> normalised = normalisedSlots();
		slots.clear();
		slots.addAll(normalised);
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof BagLayout))
		{
			return false;
		}
		BagLayout other = (BagLayout) o;
		return normalisedSlots().equals(other.normalisedSlots());
	}

	@Override
	public int hashCode()
	{
		return normalisedSlots().hashCode();
	}

	private static void checkIndex(int index)
	{
		if (index < 0 || index >= SLOTS)
		{
			throw new IndexOutOfBoundsException("slot index " + index + " out of range [0, " + SLOTS + ")");
		}
	}

	private static Integer canonicalise(Integer itemId)
	{
		return (itemId != null && itemId <= 0) ? null : itemId;
	}

	private void ensureCapacity(int size)
	{
		while (slots.size() < size)
		{
			slots.add(null);
		}
	}

	private void trimTrailingNulls()
	{
		int end = slots.size();
		while (end > 0 && slots.get(end - 1) == null)
		{
			end--;
		}
		while (slots.size() > end)
		{
			slots.remove(slots.size() - 1);
		}
	}

	/**
	 * Clamped to {@link #SLOTS} entries, negative/zero ids converted to {@code null}, trailing
	 * nulls dropped. Does not mutate {@link #slots}.
	 */
	private List<Integer> normalisedSlots()
	{
		int end = Math.min(slots.size(), SLOTS);
		List<Integer> result = new ArrayList<>(end);
		for (int i = 0; i < end; i++)
		{
			result.add(canonicalise(slots.get(i)));
		}
		int trimmedEnd = result.size();
		while (trimmedEnd > 0 && result.get(trimmedEnd - 1) == null)
		{
			trimmedEnd--;
		}
		return new ArrayList<>(result.subList(0, trimmedEnd));
	}
}
