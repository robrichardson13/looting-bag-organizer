package io.robrichardson.lootingbagorganizer.ui;

import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.Arrays;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Covers the card 13 acceptance list for {@link BagDragOverlay}: it draws nothing unless the
 * model reports a drag in progress, it never reads {@link BagInputListener}'s AWT-only state
 * (only {@link BagViewController}'s client-thread-safe getters and its {@link BagViewModel}), and
 * it draws the ghost image plus a target highlight only when there is somewhere sensible to put
 * them.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class BagDragOverlayTest
{
	private static final Rectangle GRID_BOUNDS = new Rectangle(100, 200, 176, 224);
	private static final int ITEM_ID = 1511;

	@Mock private Client client;
	@Mock private ItemManager itemManager;
	@Mock private BagViewController controller;
	@Mock private ItemComposition composition;
	@Mock private AsyncBufferedImage image;

	private BagViewModel model;
	private BagDragOverlay overlay;
	private Graphics2D graphics;

	@Before
	public void setUp()
	{
		model = new BagViewModel();
		model.setGridBounds(GRID_BOUNDS);

		when(controller.getModel()).thenReturn(model);
		when(controller.isViewOpen()).thenReturn(true);
		when(controller.getGridBounds()).thenReturn(GRID_BOUNDS);
		when(controller.getSlots()).thenReturn(Arrays.asList(
			new BagSlot(0, ITEM_ID, 1, BagGeometry.cell(0, GRID_BOUNDS)),
			new BagSlot(1, -1, 0, BagGeometry.cell(1, GRID_BOUNDS))));

		when(client.getItemDefinition(ITEM_ID)).thenReturn(composition);
		when(composition.isStackable()).thenReturn(false);
		when(image.getWidth()).thenReturn(32);
		when(image.getHeight()).thenReturn(32);
		when(itemManager.getImage(anyInt(), anyInt(), org.mockito.ArgumentMatchers.anyBoolean()))
			.thenReturn(image);

		overlay = new BagDragOverlay(client, itemManager, controller);
		graphics = mock(Graphics2D.class);
	}

	@Test
	public void rendersNothingWhenNoDragIsInProgress()
	{
		Dimension size = overlay.render(graphics);

		assertNull(size);
		verifyNoInteractions(graphics);
	}

	@Test
	public void rendersNothingWhenTheViewIsClosedEvenIfTheModelThinksItIsDragging()
	{
		model.beginDrag(0);
		model.updateDrag(new Point(110, 210));
		when(controller.isViewOpen()).thenReturn(false);

		Dimension size = overlay.render(graphics);

		assertNull(size);
		verifyNoInteractions(graphics);
	}

	@Test
	public void drawsTheGhostAndHighlightWhileDraggingOverAnotherCell()
	{
		model.beginDrag(0);
		model.updateDrag(new Point(GRID_BOUNDS.x + 50, GRID_BOUNDS.y + 40));

		overlay.render(graphics);

		verify(graphics).drawImage(org.mockito.ArgumentMatchers.any(java.awt.Image.class),
			anyInt(), anyInt(), org.mockito.ArgumentMatchers.isNull());
		verify(graphics).drawRect(anyInt(), anyInt(), anyInt(), anyInt());
	}

	@Test
	public void drawsNothingOutsideTheGridRectangle()
	{
		model.beginDrag(0);
		// Past the 4x7 pitch rectangle: BagGeometry.slotAt resolves this to -1.
		model.updateDrag(new Point(GRID_BOUNDS.x + 1000, GRID_BOUNDS.y + 1000));

		overlay.render(graphics);

		verify(graphics, never()).drawRect(anyInt(), anyInt(), anyInt(), anyInt());
	}

	@Test
	public void drawsOnlyTheHighlightBeforeTheCursorHasMoved()
	{
		// beginDrag leaves dragPoint null until the first updateDrag; the source cell hovers itself.
		model.beginDrag(0);

		overlay.render(graphics);

		verify(graphics).drawRect(anyInt(), anyInt(), anyInt(), anyInt());
		verify(graphics, never()).drawImage(org.mockito.ArgumentMatchers.any(java.awt.Image.class),
			anyInt(), anyInt(), org.mockito.ArgumentMatchers.isNull());
	}

	@Test
	public void skipsTheGhostWhenTheItemManagerHasNoImageYet()
	{
		when(itemManager.getImage(anyInt(), anyInt(), org.mockito.ArgumentMatchers.anyBoolean()))
			.thenReturn(null);
		model.beginDrag(0);
		model.updateDrag(new Point(GRID_BOUNDS.x + 50, GRID_BOUNDS.y + 40));

		overlay.render(graphics);

		verify(graphics, never()).drawImage(org.mockito.ArgumentMatchers.any(java.awt.Image.class),
			anyInt(), anyInt(), org.mockito.ArgumentMatchers.isNull());
	}
}
