package io.robrichardson.lootingbagorganizer.ui;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.MouseListener;

/**
 * All mouse and Escape-key input for the looting bag grid. Runs on the AWT event thread and never
 * touches {@link BagViewModel} directly: it decides whether to consume purely from the volatiles
 * {@link BagViewController} publishes each client-thread pass ({@link
 * BagViewController#isViewOpen()}, {@link BagViewController#getGridBounds()}, {@link
 * BagViewController#getSlots()}), and posts every mutation with {@link
 * BagViewController#post(Runnable)} so it runs on the client thread at the top of the next frame.
 *
 * <p>Registered at mouse-listener position 0, ahead of the game's own handling, so a consumed
 * press never reaches the client's mouse buffer: nothing is written into the world, so nothing
 * triggers the modal close that would otherwise dismiss the interface.
 *
 * <p>Right button and mouse-move are never consumed, so {@code Examine}, the value tooltip and
 * hover tooltips keep working exactly as they do on an unmodified client. Alt is respected too, so
 * alt-dragging other RuneLite overlays over the bag keeps working.
 *
 * <h2>Coordinate space</h2>
 *
 * <p>Position 0 is also where core's {@code StretchedModePlugin} inserts its
 * {@code TranslateMouseListener}, and {@code MouseManager.registerMouseListener(int, l)} is a list
 * insert, so whichever of us registered there most recently runs first. We do not control that
 * ordering. When Translate runs ahead of us the {@link MouseEvent} we receive is already
 * game-canvas space; when it runs behind us (e.g. immediately after we re-enable) the event still
 * carries raw, stretched canvas pixels. Widget bounds are always game space, so hit-testing the
 * raw event is wrong in the second ordering and scaling it unconditionally is wrong in the first.
 *
 * <p>Hit-testing {@link Client#getMouseCanvasPosition()} instead looked like a way to sidestep the
 * question entirely, but the shipped client's bytecode disproves it: the client updates that
 * tracked position <b>after</b> the whole {@code MouseManager} chain has run and <b>only when the
 * event comes back unconsumed</b>. We consume every {@code MOUSE_DRAGGED}, so during our own drag
 * the getter freezes at the press point, the 4px slop is never crossed and the drag silently does
 * nothing.
 *
 * <p>So the space is measured once per gesture instead of assumed:
 *
 * <ul>
 *   <li>At {@link #mousePressed} the tracked position <i>is</i> fresh, because AWT always delivers
 *       an unconsumed {@code MOUSE_MOVED} before a press and a press does not move the pointer.
 *       We compare the raw event coordinates and their translated counterpart against it and latch
 *       whichever is closer into {@link #eventsArePreTranslate} for the rest of the gesture. One
 *       boolean, nothing more: a single sample cannot separate scale from offset, so no scale is
 *       ever inferred from the comparison.</li>
 *   <li>The scale itself is read from the client
 *       ({@link Client#isStretchedEnabled()}, {@link Client#getStretchedDimensions()},
 *       {@link Client#getRealDimensions()}) with exactly the formula core's
 *       {@code TranslateMouseListener.translateEvent} uses, and re-read on every event the same
 *       way core does, so a window resized mid-gesture is handled with the scale current at each
 *       event rather than a stale one.</li>
 *   <li>Every subsequent drag and release resolves its game-space point from its own event
 *       coordinates via {@link #gamePoint}, so nothing depends on the client updating a value our
 *       own consumption prevents it from updating.</li>
 * </ul>
 *
 * <p>Reading these {@code Client} getters from the AWT thread is a deliberate, narrow exception to
 * this codebase's "Client access on the client thread" rule of thumb. They are stateless value
 * reads with no side effects, and core reads the same three dimension getters from this very
 * thread, in this very listener chain, in {@code TranslateMouseListener}.
 *
 * <p>Every hit-test decision below logs both coordinate spaces, and the press logs the latched
 * orientation and the scale it used. That logging is how this ordering bug was originally caught.
 */
@Slf4j
@Singleton
public class BagInputListener implements MouseListener, KeyListener
{
	private static final int DRAG_SLOP = 4;

	/** Scale factors when nothing is being stretched: {@code x}, then {@code y}. */
	private static final double[] NO_STRETCH = {1.0d, 1.0d};

	private final BagViewController controller;
	private final Client client;

	// AWT thread only. Armed by a left press on an occupied slot inside the grid; becomes active
	// once the drag crosses the slop, at which point beginDrag has been posted to the controller.
	private boolean dragArmed;
	private boolean dragActive;
	private int sourceSlot = -1;
	private Point pressPoint;

	/**
	 * Latched at every press, for the duration of that one gesture: true when this event stream is
	 * being delivered to us <i>before</i> core's {@code TranslateMouseListener} has run, i.e. the
	 * coordinates are raw stretched-canvas pixels and we must translate them ourselves. False when
	 * the events already arrive in game-canvas space (Translate ran ahead of us, or nothing is
	 * being stretched), in which case {@link #gamePoint} is the identity. See the class javadoc.
	 */
	private boolean eventsArePreTranslate;

	/** True while a press we consumed is outstanding, so its release is consumed too. */
	private boolean pressConsumed;

	/**
	 * True when the press+release that precede the next click were consumed. AWT delivers pressed,
	 * released, then clicked, so {@link #pressConsumed} has already been cleared by the time the
	 * click arrives; an unconsumed click reaches the client's mouse buffer and can drive the world
	 * interaction that dismisses this modal-clickthrough window (docs/PLAN.md section 3.4). Same
	 * two-flag pattern as bankless-bank's {@code BankInputListener}.
	 */
	private boolean clickConsumed;

	@Inject
	public BagInputListener(BagViewController controller, Client client)
	{
		this.controller = controller;
		this.client = client;
	}

	/**
	 * The client's own tracked pointer position, in game-canvas space. Fresh at press time only
	 * (see the class javadoc); never used for a drag or a release, where our own consumption stops
	 * the client from advancing it. Cannot be null in the real client -- the getter allocates a
	 * fresh {@code Point} from two volatile ints -- but it reports {@code (-1, -1)} while the
	 * pointer is off the canvas, which simply fails every grid hit test.
	 */
	private Point canvasPoint()
	{
		net.runelite.api.Point p = client.getMouseCanvasPosition();
		return p == null ? new Point(-1, -1) : new Point(p.getX(), p.getY());
	}

	/**
	 * The stretched-canvas to game-canvas scale, read live from the client, exactly as core's
	 * {@code TranslateMouseListener.translateEvent} computes it. Never inferred from a ratio of
	 * observed coordinates: one sample cannot separate a scale from an offset, and the ratio
	 * degenerates near the origin. Returns {@link #NO_STRETCH} whenever nothing is being stretched
	 * or the client's dimensions are unusable.
	 */
	private double[] stretchScale()
	{
		if (!client.isStretchedEnabled())
		{
			return NO_STRETCH;
		}

		Dimension stretched = client.getStretchedDimensions();
		Dimension real = client.getRealDimensions();

		if (stretched == null || real == null
			|| stretched.width <= 0 || stretched.height <= 0
			|| real.getWidth() <= 0 || real.getHeight() <= 0)
		{
			return NO_STRETCH;
		}

		return new double[]{stretched.width / real.getWidth(), stretched.height / real.getHeight()};
	}

	/** The event's coordinates put through {@code scale}, i.e. what Translate would make of them. */
	private static Point translate(MouseEvent e, double[] scale)
	{
		return new Point((int) (e.getX() / scale[0]), (int) (e.getY() / scale[1]));
	}

	private static int manhattan(Point a, Point b)
	{
		return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
	}

	/**
	 * Game-space coordinates of a drag or release event, using the orientation latched at press and
	 * the scale current at this event. Identity whenever the events already arrive translated.
	 */
	private Point gamePoint(MouseEvent e)
	{
		return eventsArePreTranslate ? translate(e, stretchScale()) : new Point(e.getX(), e.getY());
	}

	private boolean insideGrid(Point game, boolean altDown)
	{
		return controller.isViewOpen() && !altDown && controller.getGridBounds().contains(game);
	}

	private void clearDragState()
	{
		dragArmed = false;
		dragActive = false;
		sourceSlot = -1;
		pressPoint = null;
	}

	private static MouseEvent consume(MouseEvent e)
	{
		e.consume();
		return e;
	}

	// ---- mouse -----------------------------------------------------------------------------

	@Override
	public MouseEvent mouseMoved(MouseEvent e)
	{
		// Never consumed: hover tooltips (qty x price = total) are untouched (docs/PLAN.md 3.2.5),
		// and it is the unconsumed move stream that keeps the client's tracked pointer position
		// fresh for the next press, which is what we latch the coordinate orientation from.
		return e;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent e)
	{
		pressConsumed = false;
		clickConsumed = false;
		clearDragState();

		// The one point in the gesture where the client's tracked position is provably fresh, so
		// the one point where we can tell which space we are being handed (class javadoc).
		Point canvas = canvasPoint();
		double[] scale = stretchScale();
		Point translated = translate(e, scale);
		int rawDelta = manhattan(new Point(e.getX(), e.getY()), canvas);
		int translatedDelta = manhattan(translated, canvas);
		eventsArePreTranslate = translatedDelta < rawDelta;

		Point game = eventsArePreTranslate ? translated : new Point(e.getX(), e.getY());

		log.debug("Bag press latched preTranslate={} (stretched={}, scale={}x{}): awt=({}, {}) "
				+ "translated=({}, {}) canvas=({}, {}) rawDelta={} translatedDelta={}",
			eventsArePreTranslate, client.isStretchedEnabled(), scale[0], scale[1],
			e.getX(), e.getY(), translated.x, translated.y, canvas.x, canvas.y,
			rawDelta, translatedDelta);

		boolean insideGrid = insideGrid(canvas, e.isAltDown());

		if (!SwingUtilities.isLeftMouseButton(e) || !insideGrid)
		{
			log.debug("Bag press at awt=({}, {}) game=({}, {}) canvas=({}, {}) not consumed (leftButton={}, insideGrid={})",
				e.getX(), e.getY(), game.x, game.y, canvas.x, canvas.y,
				SwingUtilities.isLeftMouseButton(e), insideGrid);
			return e;
		}

		Rectangle bounds = controller.getGridBounds();
		int slot = BagGeometry.slotAt(bounds, canvas.x, canvas.y);
		boolean occupied = slot >= 0 && slot < controller.getSlots().size()
			&& !controller.getSlots().get(slot).isEmpty();

		pressConsumed = true;
		if (occupied)
		{
			dragArmed = true;
			sourceSlot = slot;
			// Baseline for the slop in the same space every later event resolves into, so the two
			// can never differ by a translation-rounding pixel.
			pressPoint = game;
		}

		log.debug("Bag press at awt=({}, {}) game=({}, {}) canvas=({}, {}) resolved to slot {} (occupied={}), consumed=true",
			e.getX(), e.getY(), game.x, game.y, canvas.x, canvas.y, slot, occupied);

		return consume(e);
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent e)
	{
		if (!dragArmed || pressPoint == null)
		{
			// Not our press (outside the grid, alt held, right button, or an empty-slot press with
			// nothing to drag): consumed only if the press that started it was.
			return pressConsumed ? consume(e) : e;
		}

		// Never the tracked canvas position here: we consume drags, so the client never advances
		// it and it would stay frozen at the press point for the whole gesture.
		Point game = gamePoint(e);

		if (!dragActive)
		{
			if (Math.abs(game.x - pressPoint.x) < DRAG_SLOP && Math.abs(game.y - pressPoint.y) < DRAG_SLOP)
			{
				log.debug("Bag drag from slot {} still within the slop at awt=({}, {}) game=({}, {}) (press was at ({}, {}))",
					sourceSlot, e.getX(), e.getY(), game.x, game.y, pressPoint.x, pressPoint.y);
				return consume(e);
			}

			dragActive = true;
			int source = sourceSlot;
			log.debug("Bag drag from slot {} crossed the slop at awt=({}, {}) game=({}, {}); posting beginDrag",
				source, e.getX(), e.getY(), game.x, game.y);
			controller.post(() -> controller.beginDrag(source));
		}

		int hoverSlot = BagGeometry.slotAt(controller.getGridBounds(), game.x, game.y);
		log.debug("Bag drag from slot {} moved to awt=({}, {}) game=({}, {}), resolved to slot {}, consumed=true",
			sourceSlot, e.getX(), e.getY(), game.x, game.y, hoverSlot);
		controller.post(() -> controller.updateDrag(game));

		return consume(e);
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent e)
	{
		boolean consumed = pressConsumed;

		if (dragActive)
		{
			Point game = gamePoint(e);
			int target = BagGeometry.slotAt(controller.getGridBounds(), game.x, game.y);
			log.debug("Bag drag from slot {} released at awt=({}, {}) game=({}, {}), resolved to slot {}, consumed={}",
				sourceSlot, e.getX(), e.getY(), game.x, game.y, target, consumed);
			controller.post(() -> controller.endDrag(target));
		}
		else if (dragArmed)
		{
			// Armed but never crossed the slop: nothing was ever begun, so there is nothing to end.
			Point game = gamePoint(e);
			log.debug("Bag press on slot {} released at awt=({}, {}) game=({}, {}) without a drag, consumed={}",
				sourceSlot, e.getX(), e.getY(), game.x, game.y, consumed);
		}

		clearDragState();
		clickConsumed = consumed;
		pressConsumed = false;

		return consumed ? consume(e) : e;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent e)
	{
		boolean consumed = clickConsumed;
		clickConsumed = false;
		return consumed ? consume(e) : e;
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent e)
	{
		return e;
	}

	@Override
	public MouseEvent mouseExited(MouseEvent e)
	{
		return e;
	}

	// ---- keyboard --------------------------------------------------------------------------

	@Override
	public void keyTyped(KeyEvent e)
	{
		// Nothing of ours is ever typed into.
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		if (e.getKeyCode() != KeyEvent.VK_ESCAPE || !dragArmed)
		{
			// Not dragging: left to the game, which closes the interface on Escape as normal.
			return;
		}

		log.debug("Escape cancelled the bag drag from slot {}", sourceSlot);
		controller.post(controller::cancelDrag);
		clearDragState();
		pressConsumed = false;
		clickConsumed = false;
		e.consume();
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
		// Nothing: the cancel already happened on keyPressed.
	}

	@Override
	public void focusLost()
	{
		if (dragArmed)
		{
			log.debug("Focus lost; cancelling the bag drag from slot {}", sourceSlot);
			controller.post(controller::cancelDrag);
		}
		clearDragState();
		pressConsumed = false;
		clickConsumed = false;
	}
}
