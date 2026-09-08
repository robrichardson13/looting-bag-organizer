# Research notes

Facts gathered before writing code, so decisions can be checked later. Dates are when the fact
was verified against the RuneLite source at `/Users/robrichardson/Code/robrichardson/runelite`.

## 1. What the game gives us (2026-09-07)

Verified against the live OSRS cache in `~/.runelite/jagexcache/oldschool/LIVE` (fetched
2026-09-07) by compiling RuneLite's `cache` module out of
`/Users/robrichardson/Code/robrichardson/runelite/cache/src/main/java` and dumping interface
group 81 plus the clientscripts that reference it. Everything below is from that dump, not from
memory. Re-run the dump with the same technique if the interface ever changes.

### Container

- The looting bag is item container `InventoryID.LOOTING_BAG` (516), capacity 28. It is only
  transmitted when the player checks or views the bag, so its contents are stale between views.
- The deposit ("Add to bag") flow builds the *same* interface from a different container, id 93.

### Static widget tree of `InterfaceID.WildernessLootingbag` (group 81, 8 static children)

| Child | gameval | Type | Notes |
|-------|---------|------|-------|
| 0 | `UNIVERSE` | LAYER | root, 190x261 |
| 1 | `TITLE` | TEXT | "Looting bag" / "Add to bag", colour `ff981f`, font 496. Also carries the inv-transmit listener (below) |
| 2 | `UNIVERSE_GRAPHIC1` | GRAPHIC | close X, sprite 831/832, op 1 = `Close`, `onOp=[29]` |
| 3 | `FRAME` | LAYER | x=9 y=22 w=172 h=228; `onLoad=[1191]` draws the border |
| 4 | `FRAME_GRAPHIC0` | GRAPHIC | sprite 1040 background |
| 5 | `ITEMS` | LAYER | the grid container. **No listeners, no click mask of its own** |
| 6 | `TOTAL` | TEXT | the "Value: N coins" footer, colour `ff981f`, font 494 |
| 7 | `TOOLTIP` | LAYER | the yellow hover box |

(`WidgetType`: LAYER=0, RECTANGLE=3, TEXT=4, GRAPHIC=5 —
`runelite-api/src/main/java/net/runelite/api/widgets/WidgetType.java`.)

### How the cells are built — clientscript 497

`ITEMS` has no static children. Script **497** (`arg0 = inv id`) builds every cell:

1. `cc_deleteall` on `81:5` — wipes the whole grid.
2. `for i in 0 .. inv_size-1` (28 for container 516): `cc_create(81:5, type GRAPHIC, index i)`.
   **A dynamic child is created for every slot, occupied or not**, and its dynamic-child index
   equals the container slot index.
3. Empty slot (`inv_getobj == -1`): `cc_sethide(1)` and nothing else — the child exists but is
   hidden and keeps its default 0x0 size and 0,0 position.
4. Occupied slot: `cc_setsize(36, 32)`, then
   `cc_setposition((i % 4) * (36 + 8), (i / 4) * 32)` — **4 columns, cell 36x32, x pitch 44,
   y pitch 32, absolute position modes, filled top-left in container order**. Then
   `cc_setoutline(1)`, `cc_setgraphicshadow(0x333333)`, `cc_setobject(itemId, qty)`.
5. Quantity: drawn by `cc_setobject`, i.e. the standard item-sprite quantity render. There is no
   separate quantity widget and **no item-name text widget** — the short names under the icons in
   the client are RuneLite's own Item Identification overlay, which explicitly lists this
   interface (`runelite-client/.../plugins/itemidentification/ItemIdentificationOverlay.java:50`).
6. Ops. View branch (inv 516): `cc_setop(10, "Examine")` only, plus
   `cc_setopbase("<col=ff9040>" + name)`. cs2 ops are 1-based, so that is RuneLite
   `Widget.setAction(9, "Examine")` (the `8 - 1` / `10 - 1` idiom in
   `banktags/tabs/LayoutManager.java:358-359` is the same conversion).
   Deposit branch (inv 93): `Store-1/5/All/X` on ops 1-4 and `Examine` on op 9.
7. Tooltip. Per-cell `cc_setonmouserepeat(script 526, ..., 81:7, ...)` and
   `cc_setonmouseleave(script 40, 81:7)`. Script 1234 formats `qty x price<br>= total` into
   `TOOLTIP`. The per-slot GE prices arrive as **VarClientInt 81-102 and 104-109** (varc 103 is
   skipped — 28 values across a gap), pushed by script **1235**.
8. Empty bag: if inv is 516 and nothing was drawn, a TEXT child is created at index 28 with
   "The bag is empty." That is the child DWMS reads
   (`dude-wheres-my-stuff/.../carryable/LootingBag.java:37-45`).

### What triggers a rebuild

- Script **495** is the entry point: it sets `TITLE`'s text, picks the container
  (`arg0 == 1` -> 93 "Add to bag", else 516), invokes 497, and then registers
  `if_setoninvtransmit(script 496, ...)` **on `TITLE` (81:1)**, not on `ITEMS`.
- Script **496** is just `invoke 497` — so *any* transmit of the container re-runs the full
  `cc_deleteall` + `cc_create` rebuild and every cell goes back to its default grid position.
- Script **1235** (called by the server with 28 prices) does **not** rebuild: it sets the price
  varcs and then walks the existing children with `cc_find(81:5, index)` to re-attach tooltip
  listeners. It never touches position or size.
- Nothing repositions cells on hover or on tick. Mouse-over listeners only drive `TOOLTIP`.

### Telling "View" from "Add to bag"

Both flows are group 81. Check `TITLE` (81:1) text — "Looting bag" vs "Add to bag" — which is
what DWMS does (`carryable/LootingBag.java:95-96`). A second, cheaper discriminator falls out of
the ops: on the View a cell's only action is `Examine`; on the deposit flow it has `Store-1`.

### Why the window closes on the next interaction

Not established from the cache: group 81 is opened by the server, so the modal mode is not in
the interface definition. The root layer has `noClickThrough=false`, i.e. it does not block
clicks, which fits a click-through modal sub-interface (`WidgetModalMode.MODAL_CLICKTHROUGH = 3`,
`runelite-api/.../widgets/WidgetModalMode.java`) — the client closes those when the player
interacts with the world. To confirm at runtime: log `WidgetClosed.getModalMode()` for group 81
(`runelite-api/.../events/WidgetClosed.java`), or walk `client.getComponentTable()` for the
`WidgetNode` whose `getId() == 81` and read `getModalMode()`.

This does not threaten our mouse listener: a `MouseListener` registered through `MouseManager`
sees AWT events before the client's own handling regardless of modality. It does mean the
lifecycle is short and abrupt, so drag state must be dropped on `WidgetClosed(81)` as well as on
`shutDown()`.

### Related but unused

`VarbitID.LOOTINGBAG_USEALLITEMS`, `VarbitID.LOOTINGBAG_IGNORE_FOOD`,
`InterfaceID.Bankmain.DEPOSIT_LOOTINGBAG`, and the bankside variant
`InterfaceID.Bankside.LOOTINGBAG_ITEMS` (group 15) — a different interface, not ours.

There is no server-side reorder action. Every arrangement is cosmetic and lives in our config.

## 2. Re-positioning widget children (2026-09-07)

### Precedent

Core `banktags/tabs/LayoutManager.java` re-lays the bank grid: it hooks `ScriptPreFired` for
`ScriptID.BANKMAIN_FINISHBUILDING` (line 621), then for each child of `Bankmain.ITEMS` calls
`setItemId` / `setItemQuantity` / `setAction` and finally
`setHidden(false); setOriginalX(posX); setOriginalY(posY); revalidate();`
(`LayoutManager.java:474-481`). Positions are computed the same way we would: index modulo the
row width times a pitch. `resetWidgets()` (`LayoutManager.java:641-663`) is the reminder that
sizes the game sets on open have to be restored on every rebuild.

### Verdict for the looting bag: feasible

- The cells are ordinary dynamic `GRAPHIC` children of `81:5`. `setOriginalX/Y` + `revalidate()`
  works on them exactly as it does on bank children. Cs2 set their position with absolute
  position modes, so nothing about our writes is unusual.
- **The game never derives a slot from a position.** Script 1235 addresses cells with
  `cc_find(81:5, index)`, and the menu/op machinery works off the widget itself. Hover, tooltip
  and `Examine` therefore keep working on a moved child, and they follow the child rather than
  the grid square it used to sit in.
- Nothing re-lays the grid on hover or on tick.
- The one thing that undoes our work is a full rebuild — script 497 via the inv-transmit
  listener on `TITLE`, i.e. every `ItemContainerChanged(516)`, and interface load. So re-apply
  the layout after each rebuild, not once on open.
- Hook point: there is no named `ScriptID` for any of this in RuneLite
  (`runelite-api/src/main/java/net/runelite/api/ScriptID.java` has no looting bag entry), so use
  the raw ids. `ScriptPostFired` for script **497** is the exact "the grid has just been built"
  signal; `WidgetLoaded(81)` + `ItemContainerChanged(516)` is the belt-and-braces fallback.
  Confirm the ids in a running client with the Script Inspector (`--developer-mode`, F12 ->
  Script Inspector) while opening the bag, and the tree with the Widget Inspector.

### Empty slots as drop targets

Every slot has a child, including empty ones, but an empty one is `cc_sethide(1)` with 0x0 size,
so it cannot be hit-tested as-is. Two options: unhide and size it (as `LayoutManager` does for
bank fillers, `LayoutManager.java:464-472`) or, simpler and more in keeping with "never create
our own item widgets", hit-test the grid arithmetic ourselves — the geometry is fully known:
cell 36x32 at `x = (i % 4) * 44`, `y = (i / 4) * 32` inside `81:5`.

### Restoring on shutdown

`shutDown()` only needs to put each child back at its container-order position with the same
formula, or force one rebuild by re-running the game's own path; the game will rebuild anyway on
the next transmit of 516.

## 3. Dragging

The bag's item widgets have no drag listener of their own (the bank uses
`ScriptID.BANKMAIN_DRAGSCROLL`). Plan: our own `MouseListener` registered at position 0 via
`MouseManager`, consuming press/drag/release only while the View window is open and the press
landed on an item cell. Same threading model as bankless-bank: AWT decides consumption from
volatiles the client thread publishes; every mutation is posted to the client thread.

### Mouse coordinate space and MouseManager ordering (2026-09-08)

**Position-0 ordering.** `MouseManager.registerMouseListener(int position, listener)` is
`list.add(position, l)` on a `CopyOnWriteArrayList` — an insert, not a claim. The most recently
registered listener at a given position runs *first*. `StretchedModePlugin` also registers at
position 0 (`TranslateMouseListener`), and it is the only thing that turns a raw AWT canvas
`MouseEvent` (stretched-canvas pixels) into game-raster space — widget bounds
(`Widget#getBounds`, `getCanvasLocation`) are always in game-raster space, because stretching is
a draw-time-only blit (`Hooks#drawn`), never a coordinate rewrite upstream of input. If our
listener (re-)registers after Translate is already in the list — e.g. toggling the plugin off
then on while stretched mode is running — we land ahead of it, and every `MouseEvent` we then see
is untranslated. Hit-testing that against widget-space bounds fails silently: presses look like
they're outside the grid. Confirmed live: `insideGrid=false` presses logged coordinates equal to
the true position times the active stretch scale (1.25). A profile swap "fixes" it only because it
restarts `StretchedModePlugin`, re-inserting Translate ahead of us again.

Rule: never trust raw `MouseEvent` x/y for hit-testing against widget bounds.
`client.getMouseCanvasPosition()` is in game space unconditionally (what core plugins use) — but
see the freshness caveat below before relying on it mid-gesture.

**`getMouseCanvasPosition()` freshness.** The client advances its tracked pointer position
(backing `getMouseCanvasPosition()`) only after the *entire* MouseManager chain returns for an
event, and only if that event comes back unconsumed (confirmed by disassembling the shipped
injected client: `tz.mouseDragged`/`tz.mouseMoved` skip the position write whenever
`e.isConsumed()`). Consuming an event — as we do for every drag we handle — means the client never
advances its tracked position for it. Press is fresh in practice (AWT always delivers an
unconsumed `MOUSE_MOVED` immediately before a press, and a press itself doesn't move the pointer),
but consuming every `MOUSE_DRAGGED` freezes the tracked position at the press point for the rest
of the gesture: drag distance always computes as zero, the slop threshold is never crossed, and
the whole gesture looks dead. A test harness that re-stubs the canvas position on every event
(including consumed ones) cannot reproduce this — it must only advance the tracked value on
unconsumed events, mirroring the real client.

Fix used here: latch one boolean per gesture at press time (compare the fresh canvas position
against the raw event to infer pre- vs post-Translate delivery), then transform raw event
coordinates for drag/release using the client's own stretch scale
(`isStretchedEnabled()`/`getStretchedDimensions()`/`getRealDimensions()`) instead of depending on
`getMouseCanvasPosition()` after the press.

## 4. Open decisions

- **Duplicate ids.** Non-stackable items occupy one slot each with the same id. A layout keyed
  by id alone can't tell copies apart. Options: key by `(id, nth occurrence)`, or store a slot
  list and match copies positionally. Decide before writing `BagLayout.moveSlot`.
- **Move semantics.** Swap (bank-style, drop onto an occupied slot swaps) vs insert-and-shift.
  Swap matches the bank and the inventory; start there.
- **Items not in the layout.** Newly added loot takes the first free slot in container order.
- **Placeholders.** Should an item that leaves the bag keep its slot reserved? Probably yes for
  a UIM's regular kit, but defer until the basic drag works.

## 5. Build / tooling facts

Same as bankless-bank: Java 11 bytecode, Gradle 7.4 wrapper, `runeLiteVersion` pinned to the
plugin-hub `runelite.version` (1.12.38 as of September 2026), BSD-2, `build=standard` in
`runelite-plugin.properties`, no `net.runelite.client.account` usage (banned by the packager).

## 6. Sync precedents (2026-09-07)

**Correction to the premise:** there is no core RuneLite "looting bag" plugin. Grepped
`runelite-client/src/main/java/net/runelite/client/plugins/` (case-insensitive) for
`LootingBag`, `LOOTING_BAG`, and `lootingbag` — the only hits are
`bank/BankConfig.java:96` (a config description for the bank's "deposit looting bag contents"
button) and an incidental match on the word "loot" in `itemidentification/`. There is no
dedicated plugin, no panel, no "?" indicator anywhere in core RuneLite. That behaviour — a
count that reads "?" or shows nothing until the bag has actually been viewed — is a
**third-party tracking pattern**, implemented identically in DWMS and bankless-bank (the latter
is a verbatim port of the former, `Copyright (c) 2022, Thource`). The rest of this section
documents that pattern.

### Why contents go stale between views

The server only sends item-container 516 (`InventoryID.LOOTING_BAG`) while the client is
actually being told about the bag — in practice, while interface 81 (`WildernessLootingbag`) is
open and being polled. `client.getItemContainer(516)` returns `null` at all other times.
DWMS's `ItemContainerWatcher.gameTick()`
(`dude-wheres-my-stuff/src/main/java/dev/thource/runelite/dudewheresmystuff/ItemContainerWatcher.java:90-101`)
polls `client.getItemContainer(itemContainerId)` unconditionally every tick and only flips
`justUpdated = true` when that call is non-null — i.e. only while the bag is open. This is the
generic mechanism `ItemStorage.onItemContainerChanged`
(`dude-wheres-my-stuff/.../ItemStorage.java:80-120`) also mirrors via the
`ItemContainerChanged(516)` event: both paths agree the data is only fresh while viewing, and
both leave `items` holding the last-known snapshot (not cleared) the rest of the time — hence a
tracker plugin either shows stale data or a "no data yet" state
(`dude-wheres-my-stuff/.../Storage.java:301-315`, `softUpdate()` sets footer text to `"No data"`
when `lastUpdated == -1`) rather than a live count.

### How DWMS/bankless-bank keep the snapshot usable between views

Both `carryable/LootingBag.java` (DWMS:
`dude-wheres-my-stuff/src/main/java/dev/thource/runelite/dudewheresmystuff/carryable/LootingBag.java`;
bankless-bank, identical logic:
`bankless-bank/src/main/java/io/robrichardson/banklessbank/tracking/carryable/LootingBag.java`)
layer four heuristics on top of the base container read, all needed only because the container
itself is silent outside a view:

1. **View detection via widget text, not `WidgetLoaded`.** Interface 81 is shared by the "View"
   and "Add to bag" (deposit) flows, confirming what CLAUDE.md already flags. `onGameTick()`
   (`LootingBag.java:29-53`) reads `client.getWidget(81, 5)` (the `ITEMS` container) and checks
   child 28's text for `"The bag is empty."` to detect an empty bag while viewing — the same
   child DWMS's widget notes in section 1 above already identify. `checkForDeposit()`
   (`LootingBag.java:94-112`) separately reads `client.getWidget(81, 1)` (the `TITLE` child) for
   the literal text `"Add to bag"` to distinguish the deposit dialog from the View window.
2. **Menu-click interception for "Use item on bag" deposits**, which never re-trigger
   `ItemContainerChanged(516)` immediately. `onMenuOptionClicked()`
   (`LootingBag.java:114-151`) watches for a "Use X on looting bag" click (matching against
   `CarryableStorageType.LOOTING_BAG.getContainerIds()`, i.e. the looting-bag item's own item
   ids, `CarryableStorageType.java:20-27`), records the used item + quantity + inventory slot as
   a `SuspendedItem` with a tick countdown, then `checkUsedItems()` (`LootingBag.java:55-92`)
   confirms the item actually left the inventory (`ItemContainerWatcher.getInventoryWatcher()
   .getItemsRemovedLastTick()`, `ItemContainerWatcher.java:157-171`) before crediting it to the
   bag's tracked contents, and expires unconfirmed suspicions after their tick budget. A
   related deposit dialog check (`client.getWidget(219, 1)`, `LootingBag.java:58-64`) extends
   the countdown while the "How many do you want to deposit?" dialog is open.
3. **Chat-message veto.** `onChatMessage()` (`LootingBag.java:154-165`) clears all suspended
   "used on bag" items when the chat line `"You can't put items in the looting bag"` appears
   (e.g. bag full, item not lootable), so a rejected deposit doesn't get wrongly credited.
4. **Coin tracking is a degenerate case of the same pattern.** `coins/LootingBag.java`
   (`dude-wheres-my-stuff/.../coins/LootingBag.java:16-31`) only ever *clears* its tracked coin
   count (reading the same "The bag is empty." child-28 text while viewing); it has no
   general-purpose read of the coin quantity outside a view because coins in the bag aren't
   otherwise observable — it's a pure "last known, refined only when confirmed empty" tracker.

None of this reads a varbit; `VarbitID.LOOTINGBAG_USEALLITEMS` /
`LOOTINGBAG_IGNORE_FOOD` (noted in section 1) are player *settings*, not content trackers, and
this plugin has no need of them either.

### bankless-bank's actual use of this machinery

bankless-bank wires the ported `CarryableStorageManager` (including its `LootingBag`) into
`DeathStorageManager`
(`bankless-bank/src/main/java/io/robrichardson/banklessbank/tracking/death/DeathStorageManager.java:93,525,549`)
for death-loss prediction ("what would I lose if I died right now"), not to drive any live
widget or overlay. `BanklessBankPlugin.java` never references `LootingBag` directly — it only
holds the `CarryableStorageManager` and hands it to the death-storage code
(`bankless-bank/src/main/java/io/robrichardson/banklessbank/BanklessBankPlugin.java:137,179`).
That's a meaningful precedent in itself: even a sibling plugin that ported the full
DWMS tracking stack only tolerates its staleness because its consumer (a "what would I lose"
estimate) is inherently best-effort. It does not attempt to keep a *live* bag view in sync from
this data.

### What the Organizer actually needs

**Approach A (re-position the real View widgets, per section 2/3 above) needs none of the
DWMS/bankless-bank tracking machinery.** The Organizer only ever renders a layout while
interface 81's View window is open, and while it's open, `client.getItemContainer(516)` is
continuously fresh — the same condition DWMS's own watcher relies on to flip `justUpdated`. So:

- No `ItemContainerChanged` bookkeeping is needed beyond what CLAUDE.md's architecture already
  specifies (re-read 516 on `ItemContainerChanged(516)`, since "container 516 is stale between
  views" only matters if something needs to *know contents while the window is closed* — the
  Organizer never does).
- No menu-click interception, chat-message vetoes, or tick-countdown "suspended item" logic.
  Those exist purely to infer contents *before* the next authoritative container read arrives;
  the Organizer never needs an inference because it never renders anything until the window
  (and thus container 516) is open and authoritative again.
- The one piece of DWMS's pattern the Organizer does still need is the **View-vs-"Add to bag"
  disambiguation** (`client.getWidget(81, 1)` title text check) — already captured in section 1
  — since both flows load group 81.
- No "?" / stale / "no data" state is needed at all: the Organizer has nothing to show when the
  bag isn't open (there's no persistent panel), so there is no moment where it would display
  a snapshot it isn't sure is current.

**A home-rolled UI (approach C) that must stay in sync between views — e.g. a persistent panel
or sidebar showing bag contents/count while the View window is closed — would need the full
DWMS pattern**, because that is exactly the problem DWMS built it to solve: container 516 tells
you nothing while closed, so a display that must be live outside a view has to reconstruct
"believed contents" from indirect signals (deposit-widget title checks, use-on-bag menu clicks
confirmed against inventory diffs, chat-message rejection, tick-based expiry of unconfirmed
guesses) and must render a "stale" / "no data" state for anything it hasn't confirmed. That's
meaningfully more code and more edge cases (item stacking on non-stackables, rejected deposits,
partial "how many" dialogs) than approach A ever needs to touch.

### The hub plugin at `~/repos/runelite-plugins` (2026-09-07)

Not a monorepo — a single plugin, package `com.lootingbag`
(`/Users/robrichardson/repos/runelite-plugins/src/main/java/com/lootingbag/`). Its
`@PluginDescriptor` name is "Looting Bag"
(`LootingBagPlugin.java:59-61`), the README title is "Looting Bag Value". It overlays the bag's
*value* and *free-slot count* on the looting-bag item icon **in the inventory** — it never draws
into interface 81 itself. It corroborates and slightly extends the "?"/stale-state pattern
already documented above from DWMS/bankless-bank, with one addition: it also infers contents
from pickups and deposits so the count can stay live between views, not just carry the last
confirmed snapshot.

**Counting taken/free slots** — `LootingBag.getFreeSlots()`
(`LootingBag.java:90-99`): `28 - sum` over its own tracked `items` map, where each entry counts
as `1` free-slot-equivalent if `itemManager.getItemComposition(itemId).isStackable()` (any
stack occupies one slot regardless of quantity) else counts as its full `quantity` (each
non-stackable copy occupies its own slot) — the same "count occupied slots, not items" rule
CLAUDE.md's rule of thumb about non-stackables implies. `items` itself is a plain
`Map<Integer, Integer>` (`LootingBag.java:27`), not a positional/sparse model — this plugin only
needs a count and a value, never a layout, so it never had to solve the "duplicate id ->
which slot" problem docs/RESEARCH.md section 4 flags for us.

**Why it shows a placeholder ("Check", not literally "?") until checked** —
`isSynced` (`LootingBag.java:31-32,46`) starts `false` and is set `true` only inside
`syncItems()` (`LootingBag.java:101-126`), which is the one place that reads the authoritative
container. `LootingBagOverlay.getFreeSlotsText()` / `getValueText()`
(`LootingBagOverlay.java:50-59`, `61-80`) return the literal string `"Check"` whenever
`!lootingBag.isSynced()`. So the guard is identical in spirit to DWMS's `lastUpdated == -1` /
"No data" state (section 6 above) — a boolean flag that only flips true once real container data
has been observed at least once this session; it is not derived from anything about *current*
freshness, so once true it never reverts to the placeholder even though the container itself
goes stale again the moment the View window closes.

**Full event/hook inventory** (all in `LootingBagPlugin.java`), each mapped to what it feeds:

| Hook | Line | Purpose |
|---|---|---|
| `onWidgetLoaded` (group `WILDERNESS_LOOTINGBAG` = 81) | 184-190 | records `tickBagViewed = client.getTickCount()`; does **not** itself sync — see below |
| `onPostClientTick` | 193-205 | if still the same tick the widget loaded on, reads `client.getItemContainer(InventoryID.LOOTING_BAG)` and calls `lootingBag.syncItems(...)`. Comment at 196-199 explains why: the bag can open *and* close within one game tick before it finishes loading, so gating the read on "did the widget-load tick just happen" (rather than syncing straight from `onWidgetLoaded`) avoids syncing off a container that hasn't been populated yet; if the widget closes before this fires, `onWidgetClosed` clears the marker so no stale read happens |
| `onWidgetClosed` (group 81) | 207-214 | resets `tickBagViewed = -1`, cancelling a pending sync from the branch above |
| `onItemContainerChanged` | 216-225 | container `INV` (36) -> `handleInventoryUpdated` (deposit/pickup diffing, see below); container `LOOTING_BAG` (516) -> `lootingBag.syncItems(...)` directly — the authoritative resync path, same event CLAUDE.md already specifies for us |
| `onGameTick` | 141-157 | expires/promotes `possibleSuppliesPickupActions` (ground-pickup items that might have routed to the inventory instead of the bag, per a "supplies" client setting) after 1 tick, crediting the bag if the item never showed up in inventory |
| `onVarClientIntChanged` (`VarClientInt.INPUT_TYPE`) | 159-181 | tracks the numeric "deposit X" chat-box input opening/closing, capturing the typed amount |
| `onMenuOptionClicked` | 227-272 | three unrelated purposes: (a) reading the "Store 1/5/10/All/X" deposit-dialog option text once `depositingX` is armed, (b) detecting "Use item on looting bag" (`WIDGET_TARGET_ON_WIDGET`, option `"Use"`) to arm `lastItemIdUsedOnLootingBag`/`lastDepositedXAmount`, (c) recording ground-item pickup / telegrab clicks as a `PickupAction` with a source tile, for the `ItemDespawned` correlation below |
| `onProjectileMoved` | 274-294 | matches the Telekinetic Grab projectile (id 143) to compute its landing cycle, used only to corroborate a subsequent `ItemDespawned` as a genuine telegrab pickup |
| `onItemDespawned` | 296-344 | the ground-truth "something was picked up" signal: correlates a despawned ground item against the last `PickupAction` (same tile, or telegrab tile+cycle) and, unless it looks like a "supply" that would route to inventory instead, calls `lootingBag.addItem(...)` optimistically (marking the quantity unconfirmed if `quantity >= 65535`, since ground stacks cap out and the true amount is unknowable — this is the plugin's own version of the ">"-prefixed "at least this much" caveat in the README) |
| `onChatMessage` (`GAMEMESSAGE`) | 346-373 | regex-matches the wilderness agility dispenser's reward message ("You have been awarded ... and ... from the Agility dispenser") to credit two items by parsed name+quantity — a chat-message *source of truth* rather than DWMS's chat-message *veto*, but same idea: chat text as an inference channel that fires when the container event alone doesn't tell you what happened |

Two more inference paths for deposits/pickups, both feeding off the `INV` (36)
`ItemContainerChanged`, not off group 81 or container 516 directly:
`handleInventoryUpdated` (`LootingBagPlugin.java:389-444`) diffs the inventory before/after to
confirm a "deposit X" (`lastDepositedXAmount`) actually left the inventory before crediting the
bag, and to resolve the "supplies" ambiguity from `onGameTick` above; `handleItemUsedOnItem`
(`LootingBagPlugin.java:455-479`) and `handleAmountToDepositSelection`
(`LootingBagPlugin.java:481-507`) turn a "Use X on looting bag" / deposit-dialog selection into
a provisional `addItem` call. None of this is unique to this plugin — it is the same
inventory-diff-plus-menu-click pattern DWMS's `carryable/LootingBag.java` uses (section 6 above),
just spread across more helper methods.

**Interface 81 usage: none.** `LootingBagOverlay extends WidgetItemOverlay` and calls
`showOnInventory()` (`LootingBagOverlay.java:23`), so it renders text anchored to the looting-bag
item's `WidgetItem` canvas bounds **wherever that item sits in the inventory widget**
(`renderItemOverlay`, `LootingBagOverlay.java:26-39` — the `itemId` guard at line 29 rejects
every item except `ItemID.LOOTING_BAG` / `LOOTING_BAG_OPEN`). It never calls
`client.getWidget(81, ...)`, never subscribes to `ScriptPreFired`/`ScriptPostFired` for script
497 or any other group-81 script, and never edits `TOTAL` (81:6) or any other group-81 child. It
also never disambiguates "View" vs "Add to bag" the way DWMS does (section 6 above) — it doesn't
need to, because it never reads anything from group 81's widget tree at all; its only signal from
that interface is the bare `WidgetLoaded`/`WidgetClosed` group-id check used purely as a timing
gate for *when* to trust `client.getItemContainer(516)`.

**What's reusable for our View-window taken/free count.** Nothing here shows a trick for
writing into interface 81 — this plugin deliberately stays off it and anchors to the inventory
icon instead, which is a valid model for us only if we *don't* want the count inside the View
window (CLAUDE.md's rule against creating our own widgets would rule out mimicking its approach
verbatim inside 81 anyway; we'd have to edit `TOTAL` (81:6) or add a sibling text child, which
this plugin gives no precedent for). What *is* directly reusable:

- The `getFreeSlots()` counting rule (occupied-slot count, not item count, using `isStackable()`
  as the branch) is exactly what our `BagLayout`/`BagViewController` should compute from the
  28-slot container read for any on-screen "N free" text.
- The `isSynced` gate is unnecessary for us for the reason section 6 already gives: our View
  window only ever renders while container 516 is fresh, so there is no moment to show a
  "Check"/placeholder state — the count is derived from the same live read that drives our
  layout re-application, on `ItemContainerChanged(516)` and after each script-497 rebuild.
- The `onWidgetLoaded`/`onPostClientTick`/`onWidgetClosed` `tickBagViewed` dance
  (`LootingBagPlugin.java:184-214`) is a useful caution, not a pattern to reuse as-is: it
  confirms empirically that group 81 can open and close within a single tick before its
  container data is ready, which is a real edge case our controller's refresh/drain-queue logic
  (CLAUDE.md's "Threading model") should tolerate — e.g. don't assume a `WidgetLoaded(81)` this
  tick guarantees container 516 is already populated.
