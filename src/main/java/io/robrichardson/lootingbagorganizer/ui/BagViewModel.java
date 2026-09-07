package io.robrichardson.lootingbagorganizer.ui;

import io.robrichardson.lootingbagorganizer.model.BagLayout;
import java.awt.Point;
import java.util.List;

/**
 * Pure tier. Owns a {@link BagLayout} and the current bag snapshot, resolves which item lands in
 * which slot, hit-tests the mouse against {@link BagSlot} bounds, and tracks drag state.
 *
 * <p>No RuneLite, Swing or Graphics2D imports: only {@code java.util}, {@code java.awt.Point} /
 * {@code Rectangle} as headless value types, and {@code model.*}. Unit tested with plain JUnit 4.
 */
public class BagViewModel
{
	private BagLayout layout = new BagLayout();

	public BagLayout getLayout()
	{
		return layout;
	}

	public void setLayout(BagLayout layout)
	{
		this.layout = layout;
	}

	/** Rebuild the 28 display slots from the raw container contents plus the saved layout. */
	public List<BagSlot> build(List<BagSlot> containerOrder)
	{
		// TODO: place each container item at its laid-out slot; unplaced items take the first free
		// slots in container order; placeholders are an open decision (docs/RESEARCH.md).
		throw new UnsupportedOperationException("not implemented");
	}

	public int slotAt(Point p)
	{
		// TODO: hit-test against the last built slots' bounds; -1 for none.
		return -1;
	}

	public void beginDrag(int slotIndex)
	{
		// TODO
	}

	public void endDrag(int targetSlotIndex)
	{
		// TODO: layout.moveSlot(source, target); the controller saves and re-applies.
	}
}
