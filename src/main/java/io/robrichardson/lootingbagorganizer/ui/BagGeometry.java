package io.robrichardson.lootingbagorganizer.ui;

import java.awt.Rectangle;

/**
 * Pure grid arithmetic for the looting bag's item grid ({@code WildernessLootingbag.ITEMS},
 * group 81 child 5). Facts verified against the live cache and clientscript 497; see
 * {@code docs/RESEARCH.md} section 1.
 *
 * <p>4 columns, cells 36x32, filled top-left in container order with an 8px gutter between
 * columns:
 *
 * <pre>
 * x(slot) = (slot % 4) * 44        y(slot) = (slot / 4) * 32
 * </pre>
 *
 * <p>Never hit-tests widget children directly: empty cells are hidden at 0x0 by the game and
 * cannot be hit, so every drop target comes from this arithmetic on the canvas bounds of the
 * grid layer instead.
 */
public final class BagGeometry
{
	public static final int COLS = 4;
	public static final int ROWS = 7;
	public static final int SLOTS = 28;
	public static final int CELL_W = 36;
	public static final int CELL_H = 32;
	public static final int PITCH_X = 44;
	public static final int PITCH_Y = 32;

	private BagGeometry()
	{
	}

	/**
	 * Child-local x of the given slot's cell, i.e. what the game itself sets with
	 * {@code cc_setposition} for an occupied slot.
	 */
	public static int x(int slot)
	{
		return (slot % COLS) * PITCH_X;
	}

	/**
	 * Child-local y of the given slot's cell.
	 */
	public static int y(int slot)
	{
		return (slot / COLS) * PITCH_Y;
	}

	/**
	 * Canvas rectangle of the given slot's cell, given the canvas bounds of the grid layer
	 * ({@code 81:5}).
	 */
	public static Rectangle cell(int slot, Rectangle gridBounds)
	{
		return new Rectangle(gridBounds.x + x(slot), gridBounds.y + y(slot), CELL_W, CELL_H);
	}

	/**
	 * The slot whose pitch cell contains the given canvas point, or {@code -1} if the point is
	 * outside the 4x7 pitch rectangle. A point in the gutter between columns belongs to the
	 * column on its left, since the divide is by pitch, not by cell width.
	 */
	public static int slotAt(Rectangle gridBounds, int canvasX, int canvasY)
	{
		int relX = canvasX - gridBounds.x;
		int relY = canvasY - gridBounds.y;

		if (relX < 0 || relY < 0)
		{
			return -1;
		}

		int col = relX / PITCH_X;
		int row = relY / PITCH_Y;

		if (col >= COLS || row >= ROWS)
		{
			return -1;
		}

		return row * COLS + col;
	}
}
