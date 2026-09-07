package io.robrichardson.lootingbagorganizer.ui;

import java.util.concurrent.ConcurrentLinkedQueue;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;

/**
 * The seam between RuneLite and the pure tier. Client-thread owner of {@link BagViewModel}.
 *
 * <p>Reads the looting bag container and the {@code WildernessLootingbag.ITEMS} widget children
 * into {@link BagSlot}s, asks the model where everything goes, then re-positions the children
 * ({@code setOriginalX/Y} + {@code revalidate}) to match. The AWT-side input listener never
 * touches the model; it posts runnables onto {@link #actions}, drained on the client thread.
 */
@Singleton
public class BagViewController
{
	private final Client client;
	private final ClientThread clientThread;
	private final BagViewModel model = new BagViewModel();
	private final ConcurrentLinkedQueue<Runnable> actions = new ConcurrentLinkedQueue<>();

	@Inject
	public BagViewController(Client client, ClientThread clientThread)
	{
		this.client = client;
		this.clientThread = clientThread;
	}

	public BagViewModel getModel()
	{
		return model;
	}

	/** Called from the AWT thread. Runs at the top of the next frame on the client thread. */
	public void post(Runnable action)
	{
		actions.add(action);
	}

	/** Client thread only. Drain queued actions, then re-apply the layout to the widgets. */
	public void refresh()
	{
		Runnable r;
		while ((r = actions.poll()) != null)
		{
			r.run();
		}
		// TODO: snapshot container + widgets, model.build(...), reposition widget children.
	}
}
