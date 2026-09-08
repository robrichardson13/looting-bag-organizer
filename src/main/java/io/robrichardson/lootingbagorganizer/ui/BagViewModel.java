package io.robrichardson.lootingbagorganizer.ui;

import io.robrichardson.lootingbagorganizer.model.BagLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.Arrays;

/**
 * Pure tier. Owns a {@link BagLayout} and the current bag snapshot, resolves which item lands in
 * which slot, hit-tests the mouse against {@link BagSlot} bounds, and tracks drag state.
 *
 * <p>No RuneLite, Swing or Graphics2D imports: only {@code java.util}, {@code java.awt.Point} /
 * {@code Rectangle} as headless value types, and {@code model.*}. Unit tested with plain JUnit 4.
 */
public class BagViewModel
{
	private static final int NONE = -1;

	private BagLayout layout = new BagLayout();
	private boolean layoutChanged;

	/**
	 * What the last {@link #arrange(int[])} put in each display slot, {@code -1} for an empty cell.
	 * The layout only ever stores deliberate placements, so this is the only record of which item
	 * an auto-placed cell is showing, and {@link #endDrag(int)} needs it to pin what a drag touches.
	 */
	private final int[] displayItemIds = newEmptyDisplay();

	private Rectangle gridBounds;
	private boolean dragging;
	private int dragSource = NONE;
	private Point dragPoint;
	private int hoverSlot = NONE;
	private int lastFreezeCount;

	public BagLayout getLayout()
	{
		return layout;
	}

	public void setLayout(BagLayout layout)
	{
		this.layout = layout;
	}

	/**
	 * Resolves each container slot to a display slot per the saved layout's placement rules.
	 *
	 * <p>Pass 1 places pinned ids: for each layout slot in ascending index order that reserves an
	 * item id, the first unclaimed container copy of that id (ascending container index) claims it.
	 * Pass 2 places every remaining container item, in ascending container index order, into the
	 * lowest-index display slot that is neither taken nor reserved by an as-yet-unfulfilled pin; if
	 * every remaining slot is reserved, the lowest-index reserved-but-unfulfilled slot is used
	 * instead and that reservation is dropped from the layout (see {@link #consumeLayoutChanged()}).
	 *
	 * @param containerItemIds raw container 516 contents; {@code <= 0} means an empty container slot
	 * @return one entry per input entry: the display slot it resolves to, or {@code -1} for an empty
	 * container slot. The non -1 entries are a permutation: no display slot is used twice.
	 */
	public int[] arrange(int[] containerItemIds)
	{
		int length = containerItemIds.length;
		int[] result = new int[length];
		boolean[] taken = new boolean[BagLayout.SLOTS];
		boolean[] reserved = new boolean[BagLayout.SLOTS];
		boolean[] containerClaimed = new boolean[length];

		Arrays.fill(displayItemIds, -1);

		for (int slot = 0; slot < BagLayout.SLOTS; slot++)
		{
			if (layout.slotAt(slot) != null)
			{
				reserved[slot] = true;
			}
		}

		// Pass 1: pinned ids claim the first unclaimed container copy of their id.
		for (int slot = 0; slot < BagLayout.SLOTS; slot++)
		{
			Integer pinnedId = layout.slotAt(slot);
			if (pinnedId == null)
			{
				continue;
			}
			for (int i = 0; i < length; i++)
			{
				if (!containerClaimed[i] && !isEmpty(containerItemIds[i]) && containerItemIds[i] == pinnedId)
				{
					containerClaimed[i] = true;
					taken[slot] = true;
					result[i] = slot;
					displayItemIds[slot] = containerItemIds[i];
					break;
				}
			}
		}

		// Pass 2: everything else, in container order, into the lowest free unreserved slot; falls
		// back to the lowest free reserved slot (dropping that reservation) when the bag needs it.
		for (int i = 0; i < length; i++)
		{
			if (isEmpty(containerItemIds[i]))
			{
				result[i] = -1;
				continue;
			}
			if (containerClaimed[i])
			{
				continue;
			}

			int target = firstFree(taken, reserved, false);
			if (target == -1)
			{
				target = firstFree(taken, reserved, true);
				if (target != -1)
				{
					layout.clearSlot(target);
					reserved[target] = false;
					layoutChanged = true;
				}
			}

			// Every input slot beyond BagLayout.SLOTS has nowhere left to go; leave it unplaced.
			result[i] = target;
			if (target != -1)
			{
				taken[target] = true;
				containerClaimed[i] = true;
				displayItemIds[target] = containerItemIds[i];
			}
		}

		return result;
	}

	/**
	 * Reports whether the most recent {@link #arrange(int[])} had to drop a stale reservation to
	 * fit every item in, then resets the flag. One-shot: a second call in a row returns
	 * {@code false} unless another {@code arrange} call has run in between.
	 */
	public boolean consumeLayoutChanged()
	{
		boolean changed = layoutChanged;
		layoutChanged = false;
		return changed;
	}

	private static boolean isEmpty(int itemId)
	{
		return itemId <= 0;
	}

	private static int firstFree(boolean[] taken, boolean[] reserved, boolean allowReserved)
	{
		for (int slot = 0; slot < BagLayout.SLOTS; slot++)
		{
			if (taken[slot])
			{
				continue;
			}
			if (!allowReserved && reserved[slot])
			{
				continue;
			}
			return slot;
		}
		return -1;
	}

	/**
	 * Canvas bounds of the grid layer ({@code 81:5}), used to resolve {@link #slotAt(Point)} and
	 * drag hover. Published by the controller each client-thread pass (docs/PLAN.md section 3.1).
	 */
	public void setGridBounds(Rectangle gridBounds)
	{
		this.gridBounds = gridBounds;
	}

	/**
	 * The slot whose pitch cell contains the given canvas point, or {@code -1} if the point is
	 * outside the grid or no grid bounds have been published yet. Pure delegation to
	 * {@link BagGeometry#slotAt(Rectangle, int, int)}; empty cells are hidden by the game at 0x0
	 * and cannot be hit-tested directly (docs/PLAN.md section 3.1).
	 */
	public int slotAt(Point p)
	{
		if (gridBounds == null)
		{
			return NONE;
		}
		return BagGeometry.slotAt(gridBounds, p.x, p.y);
	}

	/**
	 * Arms a drag from an occupied slot. Called once the AWT listener's press has moved past the
	 * slop threshold (docs/PLAN.md section 3.2). The source cell keeps rendering in place; only the
	 * overlay reacts to this state.
	 */
	public void beginDrag(int sourceSlot)
	{
		dragging = true;
		dragSource = sourceSlot;
		dragPoint = null;
		hoverSlot = sourceSlot;
	}

	/** Tracks the cursor while a drag is active, updating the hovered target slot. */
	public void updateDrag(Point canvas)
	{
		if (!dragging)
		{
			return;
		}
		dragPoint = canvas;
		hoverSlot = slotAt(canvas);
	}

	/**
	 * Resolves a drop. Dropping outside the grid ({@code targetSlot == -1}) or back onto the
	 * source slot cancels with no change. Otherwise the two slots are swapped via
	 * {@link BagLayout#moveSlot(int, int)} — a move when the target was empty, a swap when it was
	 * occupied — and both slots end up pinned in the layout, since a drag is always a deliberate
	 * placement (docs/PLAN.md sections 2 and 3.2).
	 *
	 * @return {@code true} if the layout changed; {@code false} for a cancel, an out-of-grid drop,
	 * or a call with no drag in progress
	 */
	public boolean endDrag(int targetSlot)
	{
		if (!dragging)
		{
			return false;
		}

		int source = dragSource;
		cancelDrag();

		if (targetSlot == NONE || targetSlot == source)
		{
			lastFreezeCount = 0;
			return false;
		}

		if (isGenuinelyEmpty(source) && isGenuinelyEmpty(targetSlot))
		{
			// Two genuinely empty, unreserved cells: nothing to move, nothing to freeze.
			lastFreezeCount = 0;
			return false;
		}

		// A drag is always a deliberate placement. Editing the layout for the first time turns the
		// bag from "auto-arranged" to "player-arranged": every currently displayed item has to be
		// pinned to its display slot first, not just the two slots the drag touches. Otherwise the
		// slot the dragged item vacated stays unreserved, and the next arrange's free-slot pass
		// (docs/PLAN.md section 2, "new items: first free slot in container order") slides every
		// later, untouched item forward to fill the gap.
		lastFreezeCount = freezeDisplayedArrangement();

		layout.moveSlot(source, targetSlot);
		return true;
	}

	/** A slot with no reservation and nothing currently displayed in it. */
	private boolean isGenuinelyEmpty(int slot)
	{
		return layout.slotAt(slot) == null && displayItemIds[slot] <= 0;
	}

	/**
	 * Pins every currently displayed item to the slot it is showing in, so the imminent move/swap
	 * cannot leave a gap that {@link #arrange(int[])} would fill by shifting later items forward.
	 * A no-op for slots that are already a deliberate placement or reservation, or that are
	 * genuinely empty. Called once per {@link #endDrag(int)} that goes on to change the layout.
	 *
	 * @return the number of slots this call newly pinned, for logging
	 */
	private int freezeDisplayedArrangement()
	{
		int frozen = 0;
		for (int slot = 0; slot < BagLayout.SLOTS; slot++)
		{
			if (layout.slotAt(slot) != null)
			{
				continue;
			}
			int shown = displayItemIds[slot];
			if (shown > 0)
			{
				layout.setSlot(slot, shown);
				frozen++;
			}
		}
		return frozen;
	}

	private static int[] newEmptyDisplay()
	{
		int[] display = new int[BagLayout.SLOTS];
		Arrays.fill(display, -1);
		return display;
	}

	/** Clears any in-flight drag without touching the layout. */
	public void cancelDrag()
	{
		dragging = false;
		dragSource = NONE;
		dragPoint = null;
		hoverSlot = NONE;
	}

	public boolean isDragging()
	{
		return dragging;
	}

	public int getDragSource()
	{
		return dragSource;
	}

	public Point getDragPoint()
	{
		return dragPoint;
	}

	public int getHoverSlot()
	{
		return hoverSlot;
	}

	/**
	 * How many previously auto-placed slots the most recent layout-changing {@link #endDrag(int)}
	 * had to freeze into deliberate placements before applying the move/swap. {@code 0} after a
	 * cancelled or no-op drag. For the controller's drag log, not behaviour.
	 */
	public int getLastFreezeCount()
	{
		return lastFreezeCount;
	}
}
