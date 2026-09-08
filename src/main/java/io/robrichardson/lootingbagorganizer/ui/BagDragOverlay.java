package io.robrichardson.lootingbagorganizer.ui;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The drag ghost: the dragged item's image following the cursor, plus a highlight outline around
 * the hovered target cell. Draws nothing unless {@link BagViewController}'s model reports a drag
 * in progress; renders the source cell's item exactly as it last stood in {@link
 * BagViewController#getSlots()}, since the source cell keeps rendering in place while a drag is
 * active (docs/PLAN.md section 3.2).
 *
 * <p>{@code render} runs on the client thread, so it is safe to read {@link BagViewController}'s
 * model and published state directly here; unlike {@link BagInputListener}, this class never
 * touches the AWT-only fields the input listener keeps for itself.
 */
@Slf4j
@Singleton
public class BagDragOverlay extends Overlay
{
	private static final float GHOST_ALPHA = 0.6f;
	private static final Color HIGHLIGHT_COLOR = new Color(255, 255, 255, 160);

	private final Client client;
	private final ItemManager itemManager;
	private final BagViewController controller;

	/** Debug-logged only on the start/stop edge, never per frame (docs/PLAN.md logging rule). */
	private boolean rendering;

	@Inject
	public BagDragOverlay(Client client, ItemManager itemManager, BagViewController controller)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.controller = controller;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		BagViewModel model = controller.getModel();
		if (!controller.isViewOpen() || !model.isDragging())
		{
			setRendering(false);
			return null;
		}

		setRendering(true);

		int hoverSlot = model.getHoverSlot();
		if (hoverSlot >= 0)
		{
			Rectangle cell = BagGeometry.cell(hoverSlot, controller.getGridBounds());
			graphics.setColor(HIGHLIGHT_COLOR);
			graphics.drawRect(cell.x, cell.y, cell.width - 1, cell.height - 1);
		}

		Point dragPoint = model.getDragPoint();
		if (dragPoint != null)
		{
			drawGhost(graphics, model.getDragSource(), dragPoint);
		}

		return null;
	}

	/** Draws the dragged item's image centred on the cursor at reduced alpha. */
	private void drawGhost(Graphics2D graphics, int sourceSlot, Point cursor)
	{
		List<BagSlot> slots = controller.getSlots();
		if (sourceSlot < 0 || sourceSlot >= slots.size())
		{
			return;
		}

		BagSlot slot = slots.get(sourceSlot);
		if (slot.isEmpty())
		{
			return;
		}

		ItemComposition composition = client.getItemDefinition(slot.itemId);
		boolean stackable = composition != null && composition.isStackable();
		BufferedImage image = itemManager.getImage(slot.itemId, slot.quantity, stackable);
		if (image == null)
		{
			return;
		}

		int x = cursor.x - image.getWidth() / 2;
		int y = cursor.y - image.getHeight() / 2;

		Composite old = graphics.getComposite();
		graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, GHOST_ALPHA));
		graphics.drawImage(image, x, y, null);
		graphics.setComposite(old);
	}

	private void setRendering(boolean now)
	{
		if (now == rendering)
		{
			return;
		}
		rendering = now;
		log.debug("Bag drag ghost {}", now ? "started rendering" : "stopped rendering");
	}
}
