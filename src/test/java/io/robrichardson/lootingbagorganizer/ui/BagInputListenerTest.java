package io.robrichardson.lootingbagorganizer.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import net.runelite.api.Client;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Covers the full acceptance list for {@link BagInputListener}: consumption is decided purely
 * from {@link BagViewController}'s published volatiles (never the model), a drag only arms from a
 * left press on an occupied slot inside the grid, and every mutation is posted to the controller's
 * queue rather than applied directly.
 *
 * <h2>The client model this harness simulates</h2>
 *
 * <p>Every test writes its coordinates in <b>game-canvas space</b> -- the space widget bounds and
 * therefore {@link BagViewController#getGridBounds()} live in. The harness then decides what the
 * {@link MouseEvent} actually carries, from two switches that reproduce the two real variables:
 *
 * <ul>
 *   <li>{@link #deliverPreTranslate} -- whether we sit ahead of core's
 *       {@code TranslateMouseListener} in the {@code MouseManager} list. Ahead of it, the event
 *       carries raw stretched-canvas pixels; behind it, game space.</li>
 *   <li>{@link #stretchBy} / {@link #noStretch} -- the stretched-mode scale, published through the
 *       mocked {@link Client#isStretchedEnabled()}, {@link Client#getStretchedDimensions()} and
 *       {@link Client#getRealDimensions()} exactly as the real client publishes it, i.e. as two
 *       integer {@link Dimension}s whose ratio is the scale.</li>
 * </ul>
 *
 * <p>Crucially, {@link #dispatch} models the rule established from the shipped injected-client
 * bytecode: the client's tracked pointer position ({@link Client#getMouseCanvasPosition()}) is
 * written only by its {@code mouseMoved} handler and by the <b>unconsumed</b> path of its
 * {@code mouseDragged} handler, both of which run <b>after</b> the whole {@code MouseManager}
 * chain. Presses, releases and clicks never write it. So a listener that consumes drags -- as this
 * one must, to keep drag events from reaching the game -- sees that position frozen at the press
 * point for the whole gesture. An earlier harness re-stubbed the position on every event, modelling
 * a client that does not exist, which is exactly why a fully green suite still missed a total drag
 * freeze.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class BagInputListenerTest
{
	private static final Component SOURCE = new Component()
	{
	};

	private static final Rectangle GRID = new Rectangle(100, 200, 176, 224);

	/** The game raster of a {@code uim} profile the bug was reproduced against, derived numerically. */
	private static final int REAL_W = 1656;
	private static final int REAL_H = 1232;

	@Mock private BagViewController controller;
	@Mock private Client client;

	private BagInputListener listener;
	private Deque<Runnable> posted;

	/** The client's tracked pointer position. Only {@link #dispatch} may advance it. */
	private net.runelite.api.Point tracked;

	private boolean stretchedEnabled;
	private int stretchedW;
	private int stretchedH;
	private boolean deliverPreTranslate;

	@Before
	public void setUp()
	{
		listener = new BagInputListener(controller, client);
		posted = new ArrayDeque<>();
		doAnswer(inv ->
		{
			posted.add(inv.getArgument(0));
			return null;
		}).when(controller).post(any(Runnable.class));

		when(controller.isViewOpen()).thenReturn(true);
		when(controller.getGridBounds()).thenReturn(new Rectangle(GRID));
		when(controller.getSlots()).thenReturn(occupiedAt(0));

		noStretch();
		deliverPreTranslate = false;
		tracked = new net.runelite.api.Point(-1, -1);

		when(client.getMouseCanvasPosition()).thenAnswer(inv -> tracked);
		when(client.isStretchedEnabled()).thenAnswer(inv -> stretchedEnabled);
		when(client.getStretchedDimensions()).thenAnswer(inv -> new Dimension(stretchedW, stretchedH));
		when(client.getRealDimensions()).thenAnswer(inv -> new Dimension(REAL_W, REAL_H));
	}

	/** 28 slots, all empty except the given index which holds an item. */
	private static List<BagSlot> occupiedAt(int occupiedIndex)
	{
		BagSlot[] slots = new BagSlot[28];
		for (int i = 0; i < slots.length; i++)
		{
			int itemId = i == occupiedIndex ? 1001 : -1;
			int qty = i == occupiedIndex ? 1 : 0;
			slots[i] = new BagSlot(i, itemId, qty, BagGeometry.cell(i, GRID));
		}
		return java.util.Arrays.asList(slots);
	}

	// ---- the simulated client ---------------------------------------------------------------

	private void noStretch()
	{
		stretchedEnabled = false;
		stretchedW = REAL_W;
		stretchedH = REAL_H;
	}

	/**
	 * Turns stretched mode on at the given factor, published the way the client publishes it: two
	 * integer dimensions. A non-integer factor therefore yields a slightly different effective
	 * scale on each axis, exactly as it does live.
	 */
	private void stretchBy(double factor)
	{
		stretchedEnabled = true;
		stretchedW = (int) Math.round(REAL_W * factor);
		stretchedH = (int) Math.round(REAL_H * factor);
	}

	private double scaleX()
	{
		return stretchedEnabled ? stretchedW / (double) REAL_W : 1.0d;
	}

	private double scaleY()
	{
		return stretchedEnabled ? stretchedH / (double) REAL_H : 1.0d;
	}

	/** What the {@link MouseEvent} carries for a pointer physically over game-space (gx, gy). */
	private Point delivered(int gx, int gy)
	{
		if (!deliverPreTranslate)
		{
			return new Point(gx, gy);
		}
		return new Point((int) Math.round(gx * scaleX()), (int) Math.round(gy * scaleY()));
	}

	/**
	 * What the client itself would see once the event has been through the whole chain: translated
	 * if Translate sits behind us, already translated if it sat ahead of us. Either way game space.
	 */
	private Point clientVisible(Point deliveredCoords)
	{
		if (!deliverPreTranslate)
		{
			return deliveredCoords;
		}
		return new Point((int) (deliveredCoords.x / scaleX()), (int) (deliveredCoords.y / scaleY()));
	}

	/**
	 * Delivers one event to the listener and then advances the client's tracked pointer position
	 * only if the real client would have: on a move, or on a drag the chain returned unconsumed.
	 */
	private MouseEvent dispatch(int id, int button, int modifiers, int gx, int gy)
	{
		Point raw = delivered(gx, gy);
		MouseEvent e = new MouseEvent(SOURCE, id, System.currentTimeMillis(), modifiers,
			raw.x, raw.y, 1, false, button);

		MouseEvent out;
		switch (id)
		{
			case MouseEvent.MOUSE_MOVED:
				out = listener.mouseMoved(e);
				break;
			case MouseEvent.MOUSE_PRESSED:
				out = listener.mousePressed(e);
				break;
			case MouseEvent.MOUSE_DRAGGED:
				out = listener.mouseDragged(e);
				break;
			case MouseEvent.MOUSE_RELEASED:
				out = listener.mouseReleased(e);
				break;
			case MouseEvent.MOUSE_CLICKED:
				out = listener.mouseClicked(e);
				break;
			default:
				throw new IllegalArgumentException("unhandled event id " + id);
		}

		boolean writesTrackedPosition = id == MouseEvent.MOUSE_MOVED || id == MouseEvent.MOUSE_DRAGGED;
		if (writesTrackedPosition && !out.isConsumed())
		{
			Point visible = clientVisible(raw);
			tracked = new net.runelite.api.Point(visible.x, visible.y);
		}

		return out;
	}

	private MouseEvent move(int gx, int gy)
	{
		return dispatch(MouseEvent.MOUSE_MOVED, MouseEvent.NOBUTTON, 0, gx, gy);
	}

	/** AWT always delivers an unconsumed move before a press, so the harness does too. */
	private MouseEvent press(int gx, int gy)
	{
		move(gx, gy);
		return dispatch(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1, MouseEvent.BUTTON1_DOWN_MASK, gx, gy);
	}

	private MouseEvent rightPress(int gx, int gy)
	{
		move(gx, gy);
		return dispatch(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON3, MouseEvent.BUTTON3_DOWN_MASK, gx, gy);
	}

	private MouseEvent altPress(int gx, int gy)
	{
		move(gx, gy);
		return dispatch(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1,
			MouseEvent.BUTTON1_DOWN_MASK | InputEvent.ALT_DOWN_MASK, gx, gy);
	}

	private MouseEvent drag(int gx, int gy)
	{
		return dispatch(MouseEvent.MOUSE_DRAGGED, MouseEvent.BUTTON1, MouseEvent.BUTTON1_DOWN_MASK, gx, gy);
	}

	private MouseEvent release(int gx, int gy)
	{
		return dispatch(MouseEvent.MOUSE_RELEASED, MouseEvent.BUTTON1, MouseEvent.BUTTON1_DOWN_MASK, gx, gy);
	}

	private MouseEvent click(int gx, int gy)
	{
		return dispatch(MouseEvent.MOUSE_CLICKED, MouseEvent.BUTTON1, MouseEvent.BUTTON1_DOWN_MASK, gx, gy);
	}

	private void drain()
	{
		while (!posted.isEmpty())
		{
			posted.poll().run();
		}
	}

	// slot 0's cell, from BagGeometry: (GRID.x, GRID.y) .. + CELL_W/CELL_H
	private static final int SLOT0_X = GRID.x + 5;
	private static final int SLOT0_Y = GRID.y + 5;

	/** A point 5px inside the given slot's cell, in game space. */
	private static Point inSlot(int slot)
	{
		Rectangle cell = BagGeometry.cell(slot, GRID);
		return new Point(cell.x + 5, cell.y + 5);
	}

	/**
	 * Press slot 0, drag to slot 5, release there, and assert the whole gesture resolved. Shared by
	 * every coordinate-space test so they differ only in the space the events are delivered in.
	 */
	private void assertFullGestureFromSlot0ToSlot5()
	{
		Point target = inSlot(5);

		assertTrue(press(SLOT0_X, SLOT0_Y).isConsumed());
		assertTrue(drag(target.x, target.y).isConsumed());
		assertTrue(release(target.x, target.y).isConsumed());

		drain();

		InOrder order = Mockito.inOrder(controller);
		order.verify(controller).beginDrag(eq(0));
		order.verify(controller).updateDrag(any(Point.class));
		order.verify(controller).endDrag(eq(5));
	}

	// ---- consumption rules -------------------------------------------------------------------

	@Test
	public void pressOutsideGridBoundsIsNotConsumed()
	{
		MouseEvent press = press(GRID.x + GRID.width + 50, GRID.y + 50);

		assertFalse(press.isConsumed());
		verify(controller, never()).post(any());
	}

	@Test
	public void pressWhileViewClosedIsNotConsumed()
	{
		when(controller.isViewOpen()).thenReturn(false);

		assertFalse(press(SLOT0_X, SLOT0_Y).isConsumed());
	}

	@Test
	public void altHeldPressInsideGridIsNotConsumed()
	{
		assertFalse("alt is reserved for RuneLite's own overlay dragging",
			altPress(SLOT0_X, SLOT0_Y).isConsumed());
	}

	@Test
	public void rightPressInsideGridIsNotConsumed()
	{
		assertFalse("right click must reach the game so Examine keeps working",
			rightPress(SLOT0_X, SLOT0_Y).isConsumed());
	}

	@Test
	public void pressOnOccupiedSlotArmsAndIsConsumed()
	{
		assertTrue(press(SLOT0_X, SLOT0_Y).isConsumed());
		// arming is not itself a mutation; nothing is posted until the drag actually starts
		verify(controller, never()).post(any());
	}

	@Test
	public void pressOnEmptySlotInsideGridIsConsumedButNeverArmsADrag()
	{
		when(controller.getSlots()).thenReturn(Collections.emptyList());

		assertTrue("a press inside the grid never reaches the game either way",
			press(SLOT0_X, SLOT0_Y).isConsumed());

		assertTrue(drag(SLOT0_X + 40, SLOT0_Y + 40).isConsumed());
		verify(controller, never()).beginDrag(Mockito.anyInt());
	}

	@Test
	public void pressMoveWithinSlopThenReleaseIsConsumedWithNoBeginDragPosted()
	{
		press(SLOT0_X, SLOT0_Y);

		assertTrue(drag(SLOT0_X + 2, SLOT0_Y).isConsumed());
		assertTrue(release(SLOT0_X + 2, SLOT0_Y).isConsumed());

		drain();
		verify(controller, never()).beginDrag(Mockito.anyInt());
		verify(controller, never()).endDrag(Mockito.anyInt());
	}

	@Test
	public void pressMovePastSlopAndReleaseOverAnotherCellPostsBeginUpdateEndInOrderAllConsumed()
	{
		assertFullGestureFromSlot0ToSlot5();
	}

	@Test
	public void releaseOutsideGridAfterAnActiveDragPostsEndDragMinusOneAndIsConsumed()
	{
		press(SLOT0_X, SLOT0_Y);
		drag(SLOT0_X + 40, SLOT0_Y + 40);

		MouseEvent release = release(GRID.x + GRID.width + 50, GRID.y + GRID.height + 50);

		assertTrue("the release that ends an active drag is ours even outside the grid", release.isConsumed());

		drain();
		verify(controller).endDrag(eq(-1));
	}

	@Test
	public void escapeWhileDraggingPostsCancelAndConsumesTheKey()
	{
		press(SLOT0_X, SLOT0_Y);
		drag(SLOT0_X + 40, SLOT0_Y + 40);

		KeyEvent escape = new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0,
			KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED);
		listener.keyPressed(escape);

		assertTrue(escape.isConsumed());
		drain();
		verify(controller).cancelDrag();
	}

	@Test
	public void escapeWhenNotDraggingIsLeftToTheGame()
	{
		KeyEvent escape = new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0,
			KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED);
		listener.keyPressed(escape);

		assertFalse(escape.isConsumed());
		verify(controller, never()).cancelDrag();
	}

	@Test
	public void focusLostWhileDraggingCancelsTheDrag()
	{
		press(SLOT0_X, SLOT0_Y);
		drag(SLOT0_X + 40, SLOT0_Y + 40);

		listener.focusLost();

		drain();
		verify(controller).cancelDrag();
	}

	@Test
	public void mouseMovedIsNeverConsumed()
	{
		assertFalse("hover tooltips must keep working untouched", move(SLOT0_X, SLOT0_Y).isConsumed());
	}

	/**
	 * Regression: AWT delivers pressed, released, clicked. If the click that follows a press we
	 * consumed is not consumed too, it reaches the client's mouse buffer and can drive the world
	 * interaction that dismisses the modal-clickthrough window (docs/RESEARCH.md section 1, "Why
	 * the window closes on the next interaction").
	 */
	@Test
	public void clickAfterAConsumedPressIsAlsoConsumed()
	{
		press(SLOT0_X, SLOT0_Y);
		release(SLOT0_X, SLOT0_Y);

		assertTrue("the click after a consumed press must not reach the client",
			click(SLOT0_X, SLOT0_Y).isConsumed());
	}

	/** A click whose press we never consumed belongs to the game. */
	@Test
	public void clickAfterAnUnconsumedPressIsNotConsumed()
	{
		when(controller.isViewOpen()).thenReturn(false);

		press(SLOT0_X, SLOT0_Y);
		release(SLOT0_X, SLOT0_Y);

		assertFalse(click(SLOT0_X, SLOT0_Y).isConsumed());
	}

	// ---- the drag freeze -----------------------------------------------------------------------

	/**
	 * The headline regression this harness exists to express: nothing is stretched and the events
	 * already arrive in game space, so this is the simplest possible configuration -- the only thing
	 * that makes it hard is that the client's tracked pointer position never advances, because we
	 * consume every drag (verified from the shipped injected-client bytecode). Against an
	 * implementation that hit-tested that frozen position, the drag never crosses the 4px slop and
	 * no {@code beginDrag} is ever posted: drag-to-rearrange is dead. Resolving from the event's own
	 * coordinates is what fixes it.
	 */
	@Test
	public void dragStillResolvesWhenTheClientNeverAdvancesItsTrackedPositionForConsumedEvents()
	{
		Point target = inSlot(5);

		press(SLOT0_X, SLOT0_Y);
		drag(target.x, target.y);
		release(target.x, target.y);

		// The freeze itself: the client saw only the pre-press move, so its position is still there.
		assertTrue("the harness must model the real client: consumed events never advance it",
			tracked.getX() == SLOT0_X && tracked.getY() == SLOT0_Y);

		drain();

		InOrder order = Mockito.inOrder(controller);
		order.verify(controller).beginDrag(eq(0));
		order.verify(controller).updateDrag(any(Point.class));
		order.verify(controller).endDrag(eq(5));
	}

	// ---- coordinate space ------------------------------------------------------------------

	/**
	 * Stretched mode off entirely: no scale, no translator in the chain, event space is game space.
	 * The latch must come out "post-translate" and {@code gamePoint} must be the identity.
	 */
	@Test
	public void fullGestureWithStretchedModeOff()
	{
		noStretch();
		deliverPreTranslate = false;

		assertFullGestureFromSlot0ToSlot5();
	}

	/**
	 * The ordering that broke live: we re-enabled after {@code StretchedModePlugin}, so we sit ahead
	 * of its {@code TranslateMouseListener} and every event carries raw stretched pixels at a
	 * {@code uim} scale of 1.25. The latch must detect that at press and translate every later event
	 * ourselves.
	 */
	@Test
	public void fullGestureWhenDeliveredPreTranslateAt125()
	{
		stretchBy(1.25);
		deliverPreTranslate = true;

		assertFullGestureFromSlot0ToSlot5();
	}

	/**
	 * The other ordering, and the trap that sank the approach of translating whenever stretched mode
	 * is simply enabled: stretched mode is on at the same 1.25, but Translate ran ahead of us so the
	 * events already arrive in game space. Translating again would double-translate and land the
	 * gesture on the wrong slots, so the latch must come out "post-translate" despite
	 * {@code isStretchedEnabled()} being true.
	 */
	@Test
	public void fullGestureWhenDeliveredPostTranslateWhileStretchedIsOn()
	{
		stretchBy(1.25);
		deliverPreTranslate = false;

		assertFullGestureFromSlot0ToSlot5();
	}

	/**
	 * A non-integer scale, delivered pre-Translate. Because the client publishes two integer
	 * dimensions, 4:3 stretching gives 2208/1656 on x and 1643/1232 on y -- a different, irrational
	 * -looking factor per axis. Both must be taken from the client, never from a ratio of observed
	 * coordinates.
	 */
	@Test
	public void fullGestureAtANonIntegerScaleDeliveredPreTranslate()
	{
		stretchBy(4.0d / 3.0d);
		deliverPreTranslate = true;

		assertFullGestureFromSlot0ToSlot5();
	}

	/** Same non-integer scale, other ordering. */
	@Test
	public void fullGestureAtANonIntegerScaleDeliveredPostTranslate()
	{
		stretchBy(4.0d / 3.0d);
		deliverPreTranslate = false;

		assertFullGestureFromSlot0ToSlot5();
	}

	/**
	 * The exact live symptom, from the other end: pressing an item whose <i>raw</i> coordinates fall
	 * outside the published grid rectangle entirely. Slot 27 sits at the bottom-right of the grid, so
	 * at 1.25 its raw coordinates are past both the right and bottom edges. The press must still arm
	 * a drag on slot 27.
	 */
	@Test
	public void pressWhoseRawCoordinatesFallOutsideTheGridStillResolvesTheCorrectSlot()
	{
		when(controller.getSlots()).thenReturn(occupiedAt(27));
		stretchBy(1.25);
		deliverPreTranslate = true;

		Point source = inSlot(27);
		Point raw = delivered(source.x, source.y);
		assertFalse("this test is only meaningful if the raw coordinates miss the grid",
			GRID.contains(raw));

		assertTrue(press(source.x, source.y).isConsumed());

		Point target = inSlot(5);
		assertTrue(drag(target.x, target.y).isConsumed());
		assertTrue(release(target.x, target.y).isConsumed());

		drain();
		InOrder order = Mockito.inOrder(controller);
		order.verify(controller).beginDrag(eq(27));
		order.verify(controller).endDrag(eq(5));
	}

	/**
	 * The converse, which rules out any partial fallback to the raw event: the pointer is really
	 * outside the grid, but its raw stretched coordinates land inside slot 0's cell by coincidence.
	 * Nothing may be consumed.
	 */
	@Test
	public void pressWhoseRawCoordinatesLandInsideASlotByCoincidenceIsNotConsumed()
	{
		stretchBy(1.25);
		deliverPreTranslate = true;

		int outsideX = 80;
		int outsideY = 160;
		assertFalse("the real pointer must be outside the grid", GRID.contains(outsideX, outsideY));
		assertTrue("...while the raw coordinates land inside it", GRID.contains(delivered(outsideX, outsideY)));

		assertFalse(press(outsideX, outsideY).isConsumed());
		verify(controller, never()).post(any());
	}

	/**
	 * The window is resized in the middle of a drag, so the stretched-to-real ratio changes between
	 * the press and the release. The scale is re-read from the client on every event -- the way core
	 * {@code TranslateMouseListener} reads it -- so only the latched orientation carries over, and
	 * the release still resolves to the slot the pointer is really over.
	 */
	@Test
	public void scaleChangingMidGestureIsPickedUpBecauseItIsReReadPerEvent()
	{
		stretchBy(1.25);
		deliverPreTranslate = true;

		assertTrue(press(SLOT0_X, SLOT0_Y).isConsumed());

		stretchBy(1.5);

		Point target = inSlot(5);
		assertTrue(drag(target.x, target.y).isConsumed());
		assertTrue(release(target.x, target.y).isConsumed());

		drain();
		InOrder order = Mockito.inOrder(controller);
		order.verify(controller).beginDrag(eq(0));
		order.verify(controller).endDrag(eq(5));
	}

	/**
	 * Stretched mode being switched off in the middle of a drag is self-correcting for the same
	 * reason: the latch says "translate", but the scale we read is now 1, so the transform becomes
	 * the identity -- which is what an untranslated event now needs.
	 */
	@Test
	public void stretchedModeTurnedOffMidGestureIsSelfCorrecting()
	{
		stretchBy(1.25);
		deliverPreTranslate = true;

		assertTrue(press(SLOT0_X, SLOT0_Y).isConsumed());

		noStretch();
		deliverPreTranslate = false;

		Point target = inSlot(5);
		assertTrue(drag(target.x, target.y).isConsumed());
		assertTrue(release(target.x, target.y).isConsumed());

		drain();
		InOrder order = Mockito.inOrder(controller);
		order.verify(controller).beginDrag(eq(0));
		order.verify(controller).endDrag(eq(5));
	}

	/**
	 * The one known way the latch can be fed a bad sample: another plugin consuming
	 * {@code MOUSE_MOVED} would leave the client's tracked position stale, so it no longer matches
	 * either candidate at press time. The failure is safe rather than silent -- the press hit-test
	 * uses that same position, so it simply falls outside the grid and nothing is consumed. The
	 * press reaches the game exactly as it would with our plugin disabled.
	 */
	@Test
	public void pressWithAStaleTrackedPositionIsNotConsumedAtAll()
	{
		// no preceding move: the client still reports the off-canvas sentinel
		MouseEvent press = dispatch(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1,
			MouseEvent.BUTTON1_DOWN_MASK, SLOT0_X, SLOT0_Y);

		assertFalse(press.isConsumed());
		verify(controller, never()).post(any());
	}
}
