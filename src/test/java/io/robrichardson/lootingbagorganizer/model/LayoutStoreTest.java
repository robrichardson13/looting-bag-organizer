package io.robrichardson.lootingbagorganizer.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import io.robrichardson.lootingbagorganizer.LootingBagOrganizerConfig;
import java.util.Arrays;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/** Verifies {@link LayoutStore}'s JSON round-trip and its use of {@link ConfigManager}. */
@RunWith(MockitoJUnitRunner.Silent.class)
public class LayoutStoreTest
{
	private static final String PROFILE = "profile-key";

	@Mock private ConfigManager configManager;
	private LayoutStore store;

	@Before
	public void setUp()
	{
		store = new LayoutStore(configManager, new Gson());
	}

	@Test
	public void saveWritesJsonUnderTheConfigGroupProfileAndLayoutKey()
	{
		BagLayout layout = new BagLayout();
		layout.setSlot(0, 42);

		store.save(PROFILE, layout);

		verify(configManager).setConfiguration(
			eq(LootingBagOrganizerConfig.CONFIG_GROUP), eq(PROFILE), eq("layout"), anyString());
	}

	@Test
	public void saveIsNoOpWithoutProfileKey()
	{
		store.save(null, new BagLayout());

		verify(configManager, never()).setConfiguration(anyString(), anyString(), anyString(), anyString());
	}

	@Test
	public void loadRoundTripsThroughGson()
	{
		BagLayout original = new BagLayout();
		original.setSlot(0, 1);
		original.setSlot(3, 2);
		original.setSlot(5, 3);

		String json = new Gson().toJson(original);
		when(configManager.getConfiguration(LootingBagOrganizerConfig.CONFIG_GROUP, PROFILE, "layout"))
			.thenReturn(json);

		BagLayout loaded = store.load(PROFILE);

		assertEquals(original.getSlots(), loaded.getSlots());
	}

	@Test
	public void loadUsesProfileScopedGetConfiguration()
	{
		when(configManager.getConfiguration(eq(LootingBagOrganizerConfig.CONFIG_GROUP), eq(PROFILE), eq("layout")))
			.thenReturn(null);

		store.load(PROFILE);

		verify(configManager).getConfiguration(LootingBagOrganizerConfig.CONFIG_GROUP, PROFILE, "layout");
	}

	@Test
	public void loadReturnsEmptyLayoutWhenProfileKeyIsNull()
	{
		BagLayout layout = store.load(null);

		assertTrue(layout.getSlots().isEmpty());
	}

	@Test
	public void loadReturnsEmptyLayoutWhenConfigValueIsNull()
	{
		when(configManager.getConfiguration(LootingBagOrganizerConfig.CONFIG_GROUP, PROFILE, "layout"))
			.thenReturn(null);

		BagLayout layout = store.load(PROFILE);

		assertTrue(layout.getSlots().isEmpty());
	}

	@Test
	public void loadReturnsEmptyLayoutWhenConfigValueIsEmptyString()
	{
		when(configManager.getConfiguration(LootingBagOrganizerConfig.CONFIG_GROUP, PROFILE, "layout"))
			.thenReturn("");

		BagLayout layout = store.load(PROFILE);

		assertTrue(layout.getSlots().isEmpty());
	}

	@Test
	public void loadReturnsEmptyLayoutOnMalformedJsonWithoutThrowing()
	{
		when(configManager.getConfiguration(LootingBagOrganizerConfig.CONFIG_GROUP, PROFILE, "layout"))
			.thenReturn("{not valid json!!");

		BagLayout layout = store.load(PROFILE);

		assertTrue(layout.getSlots().isEmpty());
	}

	@Test
	public void loadReturnsEmptyLayoutWhenJsonParsesToNull()
	{
		when(configManager.getConfiguration(LootingBagOrganizerConfig.CONFIG_GROUP, PROFILE, "layout"))
			.thenReturn("null");

		BagLayout layout = store.load(PROFILE);

		assertTrue(layout.getSlots().isEmpty());
	}

	@Test
	public void loadNormalisesAnOverLengthSavedLayout()
	{
		StringBuilder slots = new StringBuilder("[");
		for (int i = 0; i < 40; i++)
		{
			slots.append(i == 0 ? "" : ",").append(i + 1);
		}
		slots.append("]");
		when(configManager.getConfiguration(LootingBagOrganizerConfig.CONFIG_GROUP, PROFILE, "layout"))
			.thenReturn("{\"slots\":" + slots + "}");

		BagLayout loaded = store.load(PROFILE);

		assertEquals(BagLayout.SLOTS, loaded.getSlots().size());
	}

	@Test
	public void loadNormalisesTrailingNullsAndNonPositiveIds()
	{
		when(configManager.getConfiguration(LootingBagOrganizerConfig.CONFIG_GROUP, PROFILE, "layout"))
			.thenReturn("{\"slots\":[1,0,-3,4,null,null]}");

		BagLayout loaded = store.load(PROFILE);

		// 0 and -3 canonicalise to null (non-positive ids), and the trailing nulls after slot 3 are
		// dropped, but the null at index 1/2 stays since a later slot (4) is still occupied.
		assertEquals(Arrays.asList(1, null, null, 4), loaded.getSlots());
	}
}
