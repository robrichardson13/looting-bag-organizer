package io.robrichardson.lootingbagorganizer.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
}
