package io.robrichardson.lootingbagorganizer.ui;

import io.robrichardson.lootingbagorganizer.model.BagLayout;
import io.robrichardson.lootingbagorganizer.model.LayoutStore;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

/**
 * The seam between RuneLite and the pure tier. Client-thread owner of {@link BagViewModel}.
 *
 * <p>Every pass ({@link #tick()}, driven by {@code ClientTick}) drains the action queue the AWT
 * input listener posts to, then, if something marked us dirty, reads container 516 and the
 * dynamic children of {@code WildernessLootingbag.ITEMS} (81:5), asks the model where each item
 * goes, and re-positions the children the game built with {@code setOriginalX/Y} +
 * {@code revalidate()}. That is the core {@code banktags} {@code LayoutManager} pattern: no
 * widget is ever created, no item is moved in the container, no game action is fired.
 *
 * <p>Script 497 rebuilds the grid from scratch on every transmit of container 516, resetting all
 * positions, so the layout is re-applied after every rebuild rather than once on open (see
 * {@code docs/RESEARCH.md} section 2 and {@code docs/PLAN.md} section 5).
 *
 * <p>Threading: the model and every {@code Client}/{@code Widget} access is client thread only.
 * The AWT tier reads the {@code volatile} fields published here ({@link #isViewOpen()},
 * {@link #getGridBounds()}, {@link #getSlots()}) and writes nothing but {@link #post(Runnable)}.
 *
 * <p>Logging is deliberately verbose at debug level: {@code runClient} runs with {@code --debug},
 * so a manual test session leaves a log we can debug the arrangement from.
 */
@Slf4j
@Singleton
public class BagViewController
{
	/**
	 * The deposit flow ("Add to bag") is the same interface group as the View window; only the
	 * TITLE (81:1) text tells them apart, as DWMS does. Compared lower-case and as a prefix so a
	 * colour tag or a trailing count cannot fool it.
	 */
	static final String DEPOSIT_TITLE = "add to bag";

	/** The game's own title, restored on {@link #restoreGameOrder()} so a disabled plugin leaves no trace. */
	static final String BASE_TITLE = "Looting bag";

	private static final int SLOTS = BagLayout.SLOTS;
	private static final long SAVE_INTERVAL_MS = 1000L;
	private static final Rectangle NO_BOUNDS = new Rectangle();

	private final Client client;
	private final ClientThread clientThread;
	private final LayoutStore layoutStore;
	private final BagViewModel model = new BagViewModel();
	private final ConcurrentLinkedQueue<Runnable> actions = new ConcurrentLinkedQueue<>();

	/**
	 * The positions the game gave each cell, remembered per widget instance. Script 497 deletes
	 * and recreates the children on every rebuild, so a changed instance at an index is how we
	 * know a capture is stale.
	 */
	private final Widget[] capturedChildren = new Widget[SLOTS];
	private final int[] capturedX = new int[SLOTS];
	private final int[] capturedY = new int[SLOTS];

	private boolean started;
	private boolean dirty;
	private boolean layoutDirty;
	private long lastSaveMs;
	private String profileKey;
	private String lastSkipReason;

	/** Package-private seam so the save-coalescing test does not have to sleep. */
	LongSupplier clock = System::currentTimeMillis;

	private volatile boolean viewOpen;
	private volatile Rectangle gridBounds = NO_BOUNDS;
	private volatile List<BagSlot> slots = Collections.emptyList();

	@Inject
	public BagViewController(Client client, ClientThread clientThread, LayoutStore layoutStore)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.layoutStore = layoutStore;
	}

	public BagViewModel getModel()
	{
		return model;
	}

	// ---- lifecycle -----------------------------------------------------------------------------

	/** Client thread. Arms the controller; the first {@link #tick()} applies whatever is on screen. */
	public void startUp()
	{
		started = true;
		dirty = true;
		lastSaveMs = clock.getAsLong();
		log.debug("BagViewController started");
	}

	/**
	 * Saves any pending layout change, then restores the game's own cell order on the client
	 * thread so a disabled plugin leaves no trace ({@code docs/PLAN.md} section 5).
	 */
	public void shutDown()
	{
		log.debug("BagViewController stopping (layoutDirty={}, profile={})", layoutDirty, profileKey);
		started = false;
		actions.clear();
		saveNow();
		clientThread.invoke(this::restoreGameOrder);
	}

	// ---- action queue --------------------------------------------------------------------------

	/** Any thread (in practice AWT). Runs on the client thread at the top of the next pass. */
	public void post(Runnable action)
	{
		if (action != null)
		{
			actions.add(action);
		}
	}

	/** Client thread. Runs everything the input listener queued since the last pass, in order. */
	void drainActions()
	{
		Runnable action;
		while ((action = actions.poll()) != null)
		{
			try
			{
				action.run();
			}
			catch (RuntimeException e)
			{
				// A failing input action must never break the frame, or it recurs forever.
				log.warn("Looting bag action failed", e);
			}
		}
	}

	// ---- the client-thread pass ----------------------------------------------------------------

	/**
	 * Client thread, once per frame from {@code ClientTick}. Drains the queue, re-applies the
	 * layout if anything marked us dirty, publishes the volatiles the AWT tier reads, and flushes
	 * a pending layout save at most once a second.
	 */
	public void tick()
	{
		drainActions();

		if (!started)
		{
			return;
		}

		Widget items = client.getWidget(InterfaceID.WildernessLootingbag.ITEMS);
		boolean open = items != null && !items.isHidden() && !isDepositFlow();

		if (open)
		{
			gridBounds = boundsOf(items);
			if (dirty && applyLayout(items))
			{
				// Only a pass that actually re-positioned something consumes the dirty flag. A pass
				// that bailed out (container not transmitted yet, script 497 not run yet) has to be
				// retried, or the one signal we were given is lost for this open window.
				dirty = false;
			}
		}
		else
		{
			if (viewOpen)
			{
				log.debug("Looting bag view no longer open; clearing published state");
			}
			gridBounds = NO_BOUNDS;
			slots = Collections.emptyList();
			model.cancelDrag();
		}

		viewOpen = open;
		model.setGridBounds(gridBounds);
		saveIfDue();
	}

	/** Ask for the layout to be re-applied on the next pass. Cheap and idempotent. */
	public void markDirty()
	{
		dirty = true;
	}

	/**
	 * Client thread. Loads the layout for a new RS profile key, saving the outgoing one first.
	 * A repeat of the current key is a no-op, so it is safe to call from both
	 * {@code RuneScapeProfileChanged} and {@code GameStateChanged(LOGGED_IN)}.
	 */
	public void onProfileChanged(String newProfileKey)
	{
		if (Objects.equals(newProfileKey, profileKey))
		{
			return;
		}

		saveNow();
		profileKey = newProfileKey;
		BagLayout loaded = layoutStore.load(newProfileKey);
		model.setLayout(loaded);
		layoutDirty = false;
		lastSaveMs = clock.getAsLong();
		markDirty();
		log.debug("Loaded looting bag layout for profile {}: {}", newProfileKey, loaded.getSlots());
	}

	public String getProfileKey()
	{
		return profileKey;
	}

	// ---- drag, from the posted runnables --------------------------------------------------------

	/** Client thread. Starts a drag from a display slot. */
	public void beginDrag(int sourceSlot)
	{
		log.debug("Drag begin from slot {}", sourceSlot);
		model.beginDrag(sourceSlot);
	}

	/** Client thread. Tracks the cursor during a drag. */
	public void updateDrag(Point canvasPoint)
	{
		model.updateDrag(canvasPoint);
	}

	/**
	 * Client thread. Resolves a drop; a changed layout is marked dirty for both re-application and
	 * persistence.
	 */
	public void endDrag(int targetSlot)
	{
		int source = model.getDragSource();
		boolean changed = model.endDrag(targetSlot);
		log.debug("Drag end {} -> {} (layout changed: {}, slots frozen: {})", source, targetSlot, changed,
			model.getLastFreezeCount());
		if (changed)
		{
			layoutDirty = true;
			markDirty();
		}
	}

	/** Client thread. Drops any in-flight drag; the layout is untouched. */
	public void cancelDrag()
	{
		if (model.isDragging())
		{
			log.debug("Drag cancelled (source slot {})", model.getDragSource());
		}
		model.cancelDrag();
	}

	// ---- published state for the AWT tier --------------------------------------------------------

	/** True while the View window (not the "Add to bag" deposit flow) is on screen. */
	public boolean isViewOpen()
	{
		return viewOpen;
	}

	/** Canvas bounds of the grid layer (81:5), or an empty rectangle while the view is closed. */
	public Rectangle getGridBounds()
	{
		Rectangle published = gridBounds;
		return new Rectangle(published);
	}

	/**
	 * The last applied arrangement, indexed by display slot: what item each cell shows and where
	 * it is on the canvas. Empty while the view is closed.
	 */
	public List<BagSlot> getSlots()
	{
		return slots;
	}

	// ---- apply and restore -----------------------------------------------------------------------

	/**
	 * Client thread. Reads container 516, resolves display slots and re-positions each visible
	 * dynamic child of 81:5. Hidden children (the game's empty cells) are left exactly as the game
	 * left them; nothing is unhidden, resized, re-itemed or re-opped.
	 *
	 * @return {@code true} if the layout was applied; {@code false} if the frame was not ready, in
	 * which case the caller keeps the dirty flag and retries next pass
	 */
	private boolean applyLayout(Widget items)
	{
		ItemContainer container = client.getItemContainer(InventoryID.LOOTING_BAG);
		if (container == null)
		{
			logSkipOnce("Apply skipped: container " + InventoryID.LOOTING_BAG + " is null");
			return false;
		}

		Widget[] children = items.getDynamicChildren();
		if (children == null || children.length == 0)
		{
			logSkipOnce("Apply skipped: 81:5 has no dynamic children yet");
			return false;
		}

		lastSkipReason = null;

		int[] ids = new int[SLOTS];
		int[] quantities = new int[SLOTS];
		Item[] contents = container.getItems();
		for (int i = 0; i < SLOTS; i++)
		{
			Item item = contents != null && i < contents.length ? contents[i] : null;
			ids[i] = item == null ? -1 : item.getId();
			quantities[i] = item == null ? 0 : item.getQuantity();
		}
		log.debug("Bag snapshot from container {}: {}", InventoryID.LOOTING_BAG, describeSnapshot(ids, quantities));

		int[] target = model.arrange(ids);
		if (model.consumeLayoutChanged())
		{
			layoutDirty = true;
			log.debug("Arrange dropped a stale reservation; layout marked for saving");
		}
		log.debug("Arrange result (containerIndex->slot): {}", describeArrangement(ids, target));

		Rectangle bounds = boundsOf(items);
		List<BagSlot> published = new ArrayList<>(SLOTS);
		for (int slot = 0; slot < SLOTS; slot++)
		{
			published.add(new BagSlot(slot, -1, 0, BagGeometry.cell(slot, bounds)));
		}

		int moved = 0;
		for (int i = 0; i < children.length && i < SLOTS; i++)
		{
			Widget child = children[i];
			if (child == null)
			{
				continue;
			}

			captureGameOrder(i, child);

			if (child.isSelfHidden())
			{
				// An empty cell: hidden at 0x0 by script 497. Leave it alone entirely.
				continue;
			}

			int slot = target[i];
			if (slot < 0 || slot >= SLOTS)
			{
				continue;
			}

			child.setOriginalX(BagGeometry.x(slot));
			child.setOriginalY(BagGeometry.y(slot));
			child.revalidate();
			moved++;

			published.set(slot, new BagSlot(slot, ids[i], quantities[i], BagGeometry.cell(slot, bounds)));
		}

		slots = Collections.unmodifiableList(published);
		log.debug("Applied layout: {} of {} children re-positioned, grid bounds {}", moved, children.length, bounds);

		int taken = 0;
		for (int id : ids)
		{
			if (id > 0)
			{
				taken++;
			}
		}
		updateTitle(taken);
		return true;
	}

	/**
	 * A skipped apply now stays dirty, so the same reason would otherwise log every frame. Logged
	 * only when the reason changes.
	 */
	private void logSkipOnce(String reason)
	{
		if (!reason.equals(lastSkipReason))
		{
			lastSkipReason = reason;
			log.debug("{}", reason);
		}
	}

	/**
	 * Client thread. Puts every visible cell back where the game had it: the position we captured
	 * before touching it, or the game's own arithmetic {@code (i % 4) * 44, (i / 4) * 32} when we
	 * have no capture for that child. Called from {@link #shutDown()}; safe if 81:5 is already gone.
	 */
	public void restoreGameOrder()
	{
		viewOpen = false;
		gridBounds = NO_BOUNDS;
		slots = Collections.emptyList();
		model.cancelDrag();

		Widget items = client.getWidget(InterfaceID.WildernessLootingbag.ITEMS);
		Widget[] children = items == null ? null : items.getDynamicChildren();
		if (children == null || children.length == 0)
		{
			log.debug("Restore skipped: 81:5 is gone or has no children");
			clearCaptures();
			resetTitle();
			return;
		}

		int restored = 0;
		for (int i = 0; i < children.length && i < SLOTS; i++)
		{
			Widget child = children[i];
			if (child == null || child.isSelfHidden())
			{
				continue;
			}

			boolean captured = capturedChildren[i] == child;
			int x = captured ? capturedX[i] : BagGeometry.x(i);
			int y = captured ? capturedY[i] : BagGeometry.y(i);
			child.setOriginalX(x);
			child.setOriginalY(y);
			child.revalidate();
			restored++;
		}

		clearCaptures();
		resetTitle();
		log.debug("Restored the game's own cell order for {} children", restored);
	}

	// ---- persistence -----------------------------------------------------------------------------

	/** Client thread. Writes the layout at most once a second while it is dirty. */
	private void saveIfDue()
	{
		if (!layoutDirty || profileKey == null)
		{
			return;
		}

		long now = clock.getAsLong();
		if (now - lastSaveMs < SAVE_INTERVAL_MS)
		{
			return;
		}

		lastSaveMs = now;
		layoutDirty = false;
		layoutStore.save(profileKey, model.getLayout());
		log.debug("Saved looting bag layout for profile {}: {}", profileKey, model.getLayout().getSlots());
	}

	/** Client thread. Writes a pending change immediately, ignoring the coalescing window. */
	private void saveNow()
	{
		if (!layoutDirty || profileKey == null)
		{
			return;
		}

		layoutDirty = false;
		lastSaveMs = clock.getAsLong();
		layoutStore.save(profileKey, model.getLayout());
		log.debug("Flushed looting bag layout for profile {}: {}", profileKey, model.getLayout().getSlots());
	}

	// ---- helpers ---------------------------------------------------------------------------------

	/**
	 * The deposit flow retitles the window "Add to bag" (script 495). It must be left completely
	 * alone: no re-positioning, and the input listener never arms while this is true.
	 */
	private boolean isDepositFlow()
	{
		Widget title = client.getWidget(InterfaceID.WildernessLootingbag.TITLE);
		String text = title == null ? null : title.getText();
		if (text == null)
		{
			return false;
		}
		return text.toLowerCase().contains(DEPOSIT_TITLE);
	}

	/**
	 * Client thread. Re-texts TITLE (81:1) with the taken/free count. Only ever called after an
	 * apply, so never on the deposit flow and never with a null container. Only {@code setText} is
	 * touched; no listener on the widget is added or removed.
	 */
	private void updateTitle(int taken)
	{
		Widget title = client.getWidget(InterfaceID.WildernessLootingbag.TITLE);
		if (title == null)
		{
			return;
		}

		String text = titleText(taken);
		title.setText(text);
		log.debug("Set looting bag title to '{}'", text);
	}

	/** Client thread. Puts the game's own title back, e.g. on {@link #shutDown()}. */
	private void resetTitle()
	{
		Widget title = client.getWidget(InterfaceID.WildernessLootingbag.TITLE);
		if (title == null)
		{
			return;
		}

		String current = title.getText();
		if (current != null && current.toLowerCase().contains(DEPOSIT_TITLE))
		{
			// Shutting down with the deposit dialog open: we never wrote this title, so relabelling
			// it "Looting bag" would be the trace we are here to avoid.
			return;
		}

		title.setText(BASE_TITLE);
		log.debug("Restored looting bag title to '{}'", BASE_TITLE);
	}

	/** Pure. {@code "Looting bag (n/28)"}. */
	static String titleText(int taken)
	{
		return BASE_TITLE + " (" + taken + "/" + SLOTS + ")";
	}

	private void captureGameOrder(int index, Widget child)
	{
		if (capturedChildren[index] == child)
		{
			return;
		}

		capturedChildren[index] = child;
		capturedX[index] = child.getOriginalX();
		capturedY[index] = child.getOriginalY();
	}

	private void clearCaptures()
	{
		for (int i = 0; i < SLOTS; i++)
		{
			capturedChildren[i] = null;
			capturedX[i] = 0;
			capturedY[i] = 0;
		}
	}

	private static Rectangle boundsOf(Widget widget)
	{
		Rectangle bounds = widget.getBounds();
		return bounds == null ? NO_BOUNDS : bounds;
	}

	private static String describeSnapshot(int[] ids, int[] quantities)
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < ids.length; i++)
		{
			if (ids[i] <= 0)
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(", ");
			}
			sb.append(i).append('=').append(ids[i]).append('x').append(quantities[i]);
		}
		return sb.length() == 0 ? "empty" : sb.toString();
	}

	private static String describeArrangement(int[] ids, int[] target)
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < target.length; i++)
		{
			if (ids[i] <= 0)
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(", ");
			}
			sb.append(i).append("->").append(target[i]);
		}
		return sb.length() == 0 ? "empty" : sb.toString();
	}
}
