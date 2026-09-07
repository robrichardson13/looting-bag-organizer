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
	 * Repairs anything a saved layout can get wrong: over-length lists, trailing nulls, negative
	 * ids. Always call after deserialising.
	 */
	public void normalise()
	{
		// TODO
	}

	public void moveSlot(int from, int to)
	{
		// TODO: swap or shift semantics is an open decision; see docs/RESEARCH.md.
		throw new UnsupportedOperationException("not implemented");
	}
}
