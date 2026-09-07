package io.robrichardson.lootingbagorganizer.ui;

import java.awt.Rectangle;

/**
 * One cell of the bag view as the pure tier sees it: which slot index it is, what item (if any)
 * it currently shows, and where the game drew it. Built from the widget children on the client
 * thread; never holds a widget reference.
 */
public final class BagSlot
{
	public final int index;
	public final int itemId;
	public final int quantity;
	public final Rectangle bounds;

	public BagSlot(int index, int itemId, int quantity, Rectangle bounds)
	{
		this.index = index;
		this.itemId = itemId;
		this.quantity = quantity;
		this.bounds = bounds;
	}

	public boolean isEmpty()
	{
		return itemId <= 0;
	}
}
