package io.robrichardson.lootingbagorganizer.ui;

import static org.junit.Assert.assertEquals;

import java.awt.Rectangle;
import org.junit.Test;

public class BagGeometryTest
{
	@Test
	public void xyMatchGridArithmeticForKnownSlots()
	{
		assertEquals(0, BagGeometry.x(0));
		assertEquals(0, BagGeometry.y(0));

		assertEquals(3 * 44, BagGeometry.x(3));
		assertEquals(0, BagGeometry.y(3));

		assertEquals(0, BagGeometry.x(4));
		assertEquals(32, BagGeometry.y(4));

		assertEquals(3 * 44, BagGeometry.x(27));
		assertEquals(6 * 32, BagGeometry.y(27));
	}

	@Test
	public void xyMatchFormulaForAllSlots()
	{
		for (int i = 0; i < BagGeometry.SLOTS; i++)
		{
			assertEquals((i % 4) * 44, BagGeometry.x(i));
			assertEquals((i / 4) * 32, BagGeometry.y(i));
		}
	}

	@Test
	public void cellIsPositionedRelativeToGridBounds()
	{
		Rectangle gridBounds = new Rectangle(100, 50, 200, 300);
		Rectangle cell = BagGeometry.cell(5, gridBounds);

		assertEquals(gridBounds.x + BagGeometry.x(5), cell.x);
		assertEquals(gridBounds.y + BagGeometry.y(5), cell.y);
		assertEquals(BagGeometry.CELL_W, cell.width);
		assertEquals(BagGeometry.CELL_H, cell.height);
	}

	@Test
	public void slotAtRoundTripsCentreOfEveryCell()
	{
		Rectangle gridBounds = new Rectangle(10, 20, 176, 224);
		for (int slot = 0; slot < BagGeometry.SLOTS; slot++)
		{
			Rectangle cell = BagGeometry.cell(slot, gridBounds);
			int centreX = cell.x + cell.width / 2;
			int centreY = cell.y + cell.height / 2;

			assertEquals("slot " + slot, slot, BagGeometry.slotAt(gridBounds, centreX, centreY));
		}
	}

	@Test
	public void gutterPointMapsToColumnOnItsLeft()
	{
		Rectangle gridBounds = new Rectangle(0, 0, 176, 224);

		// Column 0 cell spans x in [0, 36), gutter is [36, 44). A point in the gutter
		// (e.g. x=40) still belongs to column 0.
		assertEquals(0, BagGeometry.slotAt(gridBounds, 40, 10));

		// Column 1 cell starts at x=44, its gutter is [80, 88).
		assertEquals(1, BagGeometry.slotAt(gridBounds, 84, 10));
	}

	@Test
	public void pointPastColumnFourIsOutside()
	{
		Rectangle gridBounds = new Rectangle(0, 0, 176, 224);

		// Column 3 pitch range is [132, 176); x=176 is past it.
		assertEquals(-1, BagGeometry.slotAt(gridBounds, 176, 10));
		assertEquals(-1, BagGeometry.slotAt(gridBounds, 300, 10));
	}

	@Test
	public void pointPastRowSevenIsOutside()
	{
		Rectangle gridBounds = new Rectangle(0, 0, 176, 224);

		// Row 6 pitch range is [192, 224); y=224 is past it.
		assertEquals(-1, BagGeometry.slotAt(gridBounds, 10, 224));
		assertEquals(-1, BagGeometry.slotAt(gridBounds, 10, 500));
	}

	@Test
	public void pointLeftOrAboveGridIsOutside()
	{
		Rectangle gridBounds = new Rectangle(50, 50, 176, 224);

		assertEquals(-1, BagGeometry.slotAt(gridBounds, 10, 60));
		assertEquals(-1, BagGeometry.slotAt(gridBounds, 60, 10));
		assertEquals(-1, BagGeometry.slotAt(gridBounds, 49, 60));
		assertEquals(-1, BagGeometry.slotAt(gridBounds, 60, 49));
	}
}
