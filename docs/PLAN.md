# Implementation plan

Written 2026-09-07 from the research in `docs/RESEARCH.md` (sections 1, 2 and 6 are ground truth
from the live cache and from source, not guesswork). This file is the spec. Where it disagrees
with a hunch, follow this file; where it disagrees with `docs/RESEARCH.md`, the research wins and
this file needs updating.

## 1. Approach

**Recommendation: A — re-position the real widget children the game builds.**

Script 497 creates one dynamic `GRAPHIC` child of `WildernessLootingbag.ITEMS` (81:5) per
container slot, dynamic index == container slot index, at `x = (i % 4) * 44`, `y = (i / 4) * 32`,
size 36x32. The game never derives a slot from a position: script 1235 addresses cells with
`cc_find(81:5, index)` and ops/hover ride on the widget object. So `setOriginalX/Y` +
`revalidate()` moves a cell and its `Examine`, its opbase name and its yellow value tooltip move
with it. This is the same mechanism core `banktags` uses
(`runelite/runelite-client/src/main/java/net/runelite/client/plugins/banktags/tabs/LayoutManager.java:474-481`),
which is the strongest possible hub-compliance argument: it is a first-party pattern, it creates
no widgets, it fires no game actions, and it changes nothing the server knows about.

Cost of A: the layout has to be re-applied after every rebuild (script 497 runs on every transmit
of container 516, wiping positions), and empty cells are hidden at 0x0 so drop targets come from
grid arithmetic rather than hit-testing children. Both are cheap and fully specified below.

**B — replace the interface with our own: rejected, and say so plainly.** The plugin hub forbids
imitating Jagex interfaces with real widgets. Building our own 4x7 item grid with our own
`Examine`/tooltip inside group 81, or hiding the game's grid and drawing a replacement, is exactly
the case that rule exists for. It also throws away everything that already works (per-slot GE
price varcs, the opbase names, the value footer) and would have to re-implement it.

**C — right-click option plus a home-rolled UI: rejected for v1.** It is hub-legal (it is an
overlay, not a fake interface) but it is strictly more work for a worse result. Per card 2, a UI
that must stay in sync while the View window is closed needs the whole DWMS tracking stack
(deposit-title checks, use-on-bag menu interception confirmed against inventory diffs,
chat-message vetoes, tick-based expiry, a "no data" state). Approach A needs none of it, because
it only ever renders while container 516 is live. C also drops the game's tooltip and Examine
unless we rebuild them.

**What would change the recommendation.** Any of these, found during the runClient card, sends us
to C:

- The game re-lays or re-creates cells more often than script 497 fires (e.g. on hover or tick),
  so positions visibly flicker back.
- Consuming the left-click in the grid does not stop the window closing, i.e. the close is driven
  by something other than the client seeing a click (see 3.4).
- `setOriginalX/Y` on these children turns out to break hover or the `Examine` menu entry.

Nothing in the cache dump suggests any of the three, but all three are runtime facts.

## 2. Resolved decisions (closes `docs/RESEARCH.md` section 4)

**The layout stores only deliberate placements.** `BagLayout` is 28 sparse slots; a non-null entry
is an item id the player explicitly dragged into that slot. Items the player never touched are
never written to the layout. This single choice resolves the rest.

**Duplicate ids: copies are interchangeable, match greedily by id.** Non-stackables give several
container slots the same id, and there is nothing to tell the copies apart (same id, quantity 1,
no per-copy state the client can see). Keying by `(id, nth occurrence)` would be precision we
cannot act on: it would make "which copy went where" depend on container order, which the server
reshuffles on every deposit. So: a slot reserving id X claims any one unclaimed container copy of
X. Deterministic (lowest slot index first, then lowest container index), and stable in practice.

**Move semantics: swap.** Drop on an occupied cell swaps the two, drop on an empty cell moves.
Matches the bank and the inventory, is one line in the model, and needs no re-flow rules. Insert-
and-shift is rejected for v1: with 28 fixed slots and a sparse layout, shifting would have to
decide what "shift" means across gaps, and it silently moves items the player did not touch.

**New items: first free slot in container order.** After pinned items are placed, remaining
container items are assigned in ascending container index to the lowest-index display slot that is
neither taken nor reserved. If every free slot is reserved (bag nearly full of pinned ids that are
absent), the lowest-index untaken slot is used and its reservation is dropped, so the bag can
always show all 28 items.

**Placeholders: reservations, yes; rendered placeholder graphics, no.** A slot whose pinned item
has left the bag stays reserved and simply renders as an empty cell. The item reclaims that slot
next time it comes back. This is what a UIM wants for a regular kit, it costs zero extra UI, it
draws nothing that could be mistaken for a game widget, and it is already implied by "the layout
stores placements". Drawing a greyed-out ghost of the absent item is explicitly out of scope for
v1. A reservation is dropped only when the player drags something else into that slot, or when the
bag is full enough to need the slot (rule above).

## 3. Drag UX

### 3.1 Geometry

All of it comes from grid arithmetic on the canvas bounds of `81:5`, never from hit-testing
children (empty cells are `cc_sethide(1)` at 0x0 and cannot be hit).

```
COLS = 4, ROWS = 7, SLOTS = 28
CELL_W = 36, CELL_H = 32, PITCH_X = 44, PITCH_Y = 32
x(slot) = (slot % 4) * 44        y(slot) = (slot / 4) * 32
```

`slotAt` divides by the pitch, not by the cell size, so the 8px gutter between columns belongs to
the column on its left. Forgiving drops beat pixel-exact ones. Anything outside the 4x7 pitch
rectangle is slot -1.

### 3.2 Interaction

1. **Press** (left button, inside the grid rectangle, View window open): consumed, drag armed,
   press point recorded on the AWT thread.
2. **Drag past 4px slop**: drag becomes active. `beginDrag(sourceSlot)` posted to the client
   thread. The source cell keeps rendering in place (we never hide a game widget mid-drag); the
   overlay draws a ghost of the item under the cursor and a 1px highlight around the hovered
   target cell.
3. **Release** over slot `t`: `endDrag(t)` posted. `t == -1` (outside the grid) or `t == source`
   cancels. Otherwise `layout.moveSlot(source, t)` swaps, the layout is marked dirty, and the next
   client-thread pass re-applies positions.
4. **Escape**, **focus lost**, **`WidgetClosed(81)`**, **`shutDown()`**: cancel any in-flight drag.
5. **Right button is never consumed**, so `Examine` and the value tooltip keep working exactly as
   before. Mouse-move is never consumed either, so hover tooltips are untouched.

A press that never moves is still consumed and does nothing. That is fine: on the View flow a cell
has no left-click op at all (only `Examine` on op 9), so there is nothing to swallow.

### 3.3 How the AWT listener decides consumption

The listener never reads the model. `BagViewController` publishes three volatiles at the top of
every client-thread pass, exactly as `bankless-bank`'s `BankInputListener` does
(`bankless-bank/src/main/java/io/robrichardson/banklessbank/ui/BankInputListener.java:38-52`,
`publish` at :92):

- `volatile boolean viewOpen` — group 81 loaded **and** `TITLE` text is not "Add to bag" (the
  deposit flow is left completely alone).
- `volatile Rectangle gridBounds` — canvas bounds of `81:5`, or an empty rectangle.
- `volatile boolean dragArmed` / `dragActive` — armed on the client thread by the runnable the
  press posted, read and cleared on AWT, so a runnable that lands after the release cannot make a
  later unrelated drag ours. Same pattern and same reasoning as `BankInputListener:329-343`.

Consumption rule: consume a left press only when `viewOpen && !altHeld && gridBounds.contains(p)`;
consume subsequent drags and the release only when that press was consumed. Alt is respected so
alt-dragging other RuneLite overlays keeps working. Every mutation goes onto the controller's
`ConcurrentLinkedQueue<Runnable>`.

### 3.4 Not closing the window

Group 81 is opened by the server, so its modal mode is not in the interface definition; the root
layer's `noClickThrough=false` is consistent with `WidgetModalMode.MODAL_CLICKTHROUGH` (3), which
the client closes when the player interacts with the world.

The reasoning that this is a non-problem: a `MouseListener` registered through `MouseManager` at
position 0 sees the AWT event before the client does, and a consumed event is never written into
the client's mouse buffer. No click in the buffer means no world interaction, so nothing triggers
the modal close. In other words the same consumption that gives us the drag is what keeps the
window open, and it only applies inside the grid rectangle, so the close X, the world and every
other interface behave normally.

**This is an inference and card 11 must verify it at runtime.** Two dev-tool checks, both cheap:

- Log `WidgetClosed.getModalMode()` for group 81 (`runelite-api/.../events/WidgetClosed.java`), or
  walk `client.getComponentTable()` for the `WidgetNode` with `getId() == 81` and read
  `getModalMode()`, to confirm mode 3.
- With the plugin running, press-drag-release inside the grid repeatedly and confirm the window
  survives; then click the world and confirm it still closes (we must not have broken that).

If the window closes anyway, fall back to re-opening is not acceptable (that would be a game
action). The answer would be approach C.

## 4. Taken/free count

**Where: appended to the `TITLE` (81:1) text — `"Looting bag (12/28)"`.** Set it on the client
thread right after the layout is applied, i.e. after each script-497 rebuild, since 495 resets the
title on every open.

Why this is hub-safe: setting the text of a widget the game already built is a first-party pattern,
not an imitation of one. Core RuneLite does exactly this to the bank title in two places:
`banktags/tabs/TabInterface.java:269,274` (`bankTitle.setText("Tag tab ...")`) and
`bank/BankPlugin.java:484` (appends the computed value to the Seed Vault title). The rule the hub
enforces is against building fake Jagex interfaces out of real widgets; re-texting one is the
opposite of that. It also means no new widget, no overlay to position against a moving window, and
the count disappears with the window automatically.

Do not touch `TITLE`'s listeners: script 495 registers the inv-transmit listener on that child.
Set text only.

**Counting rule (from card 4).** Occupied slots, not items. Read container 516 and count entries
with `id > 0` and `quantity > 0`; each non-stackable copy already has its own container slot, and
a stack is one slot regardless of quantity, so the direct slot count is equivalent to the hub
plugin's `28 - sum(stackable ? 1 : quantity)` (`~/repos/runelite-plugins/.../LootingBag.java:90-99`)
without needing `ItemManager.isStackable`. Display `taken/28`.

No "?" / "Check" placeholder state is needed: we only ever render while the View window is open,
and container 516 is authoritative for exactly that window (card 2). If the container reads null
while the view is open (the open-and-close-within-one-tick case the hub plugin documents at
`LootingBagPlugin.java:193-205`), leave the title alone rather than writing `(0/28)`.

## 5. Applying, re-applying and restoring

**Apply.** Client thread. Read container 516 into `int[28]` of item ids. Ask the model for
`slotOfContainerIndex`. For each child `i` of `81:5` that the game left visible, set
`setOriginalX(x(slot))`, `setOriginalY(y(slot))`, `revalidate()`. Never unhide, resize, re-op or
re-item a child; never create one. Hidden (empty) children stay exactly as the game left them.

**Re-apply triggers**, in order of reliability:

1. `ScriptPostFired` for raw script id **497** — the precise "the grid was just rebuilt" signal.
   There is no `ScriptID` constant for it; use the literal with a named private constant and a
   comment pointing at `docs/RESEARCH.md`.
2. `ItemContainerChanged(516)` and `WidgetLoaded(81)` as belt and braces, since 497 is invoked
   through the transmit listener and script ids are a cache detail that could change.

All three set a `dirty` flag; the next client-thread pass applies. Applying twice is harmless.

**Restore on shutdown.** `shutDown()` posts one `clientThread.invoke`: if `81:5` still exists, put
every visible child back at `x = (i % 4) * 44, y = (i / 4) * 32` for its own index `i` and
`revalidate()`, and reset `TITLE` to `"Looting bag"`. Then unregister the mouse listener, the key
listener and the overlay. The game rebuilds from scratch on the next transmit anyway, so this only
has to survive the current open window, but a disabled plugin must leave no trace.

**Threading**, per CLAUDE.md: the model is client-thread only; the input listener is AWT only and
communicates solely through volatiles (out) and the runnable queue (in); every `Client` or `Widget`
access is on the client thread. The queue is drained once per client-thread pass, before anything
reads the model. Use the `ClientTick` subscription for that pass (client thread, once per frame),
not the overlay's `render`, because the overlay only renders while the window is open and we want
the queue drained even on the frame the window closes.

## 6. Persistence

`LayoutStore` as scaffolded, with three changes, all copied from
`bankless-bank/src/main/java/io/robrichardson/banklessbank/model/LayoutStore.java`:

- Inject RuneLite's `Gson` rather than hand-rolling JSON.
- Take the RS profile key as a parameter: `load(String profileKey)` / `save(String profileKey,
  BagLayout)`, so the caller owns "which profile am I on" and the store stays trivially testable.
  Read and write with `configManager.getConfiguration(CONFIG_GROUP, profileKey, KEY)` /
  `setConfiguration(...)`, which is what `getRSProfileConfiguration` resolves to and what
  bankless-bank uses.
- Catch `RuntimeException` on load, log a warning, return a fresh layout. A malformed saved value
  must never throw into the render loop, where it would recur every frame.

The profile key comes from `configManager.getRSProfileKey()` and is only stable after
`RuneScapeProfileChanged`; the plugin loads there and on `GameState.LOGGED_IN`, and saves when the
key changes or on `shutDown()`. Writes are coalesced: mark dirty on mutation, flush at most once a
second from the client-thread pass (bankless-bank `BankViewController:492-500`).

Nothing else is persisted. No `@ConfigItem` is needed for v1.

## 7. Implementation cards

Each is one class or one behaviour, sized for a single sonnet agent. Dependencies are card
numbers in this list.

### C1. `BagLayout`: sparse slots, normalise, swap

- Files: `model/BagLayout.java`, `src/test/.../model/BagLayoutTest.java`
- Contracts:
  - `List<Integer> getSlots()` (unmodifiable; `null` = free, non-null = reserved item id)
  - `Integer slotAt(int index)`, `void setSlot(int index, Integer itemId)`, `void clearSlot(int)`
  - `void moveSlot(int from, int to)` — swaps entries, no-op if `from == to`
  - `void normalise()` — clamps to 28 entries, drops trailing nulls, converts ids `<= 0` to null
  - `boolean equals(Object)` / `hashCode()` over the normalised slot list
- Acceptance:
  - [ ] `moveSlot` swaps two occupied slots, moves into an empty one, is a no-op for `from == to`
  - [ ] Out-of-range index on any mutator throws `IndexOutOfBoundsException`
  - [ ] `normalise()` truncates a 40-entry list to 28, strips trailing nulls, nulls ids `<= 0`
  - [ ] `getSlots()` is unmodifiable
  - [ ] Tests are plain JUnit 4, no Mockito, no RuneLite imports in the class
- Depends on: nothing

### C2. `LayoutStore`: JSON per RS profile

- Files: `model/LayoutStore.java`, new `src/test/.../model/LayoutStoreTest.java`
- Contracts: `@Inject LayoutStore(ConfigManager, Gson)`, `BagLayout load(String profileKey)`,
  `void save(String profileKey, BagLayout layout)`; key `"layout"` in group
  `LootingBagOrganizerConfig.CONFIG_GROUP`
- Acceptance:
  - [ ] Round-trips a layout through `save` then `load` with a stubbed `ConfigManager`
  - [ ] `load` with a null profile key, a null value or `""` returns an empty layout, no throw
  - [ ] `load` on malformed JSON logs and returns an empty layout rather than throwing
  - [ ] `load` calls `normalise()` on the deserialised layout
  - [ ] `save` with a null profile key writes nothing
  - [ ] Mockito allowed here (`ConfigManager` is a RuneLite type)
- Depends on: C1

### C3. `BagGeometry`: the 4x7 grid

- Files: new `ui/BagGeometry.java`, new `src/test/.../ui/BagGeometryTest.java`
- Contracts (all static, pure):
  - `int COLS=4, ROWS=7, SLOTS=28, CELL_W=36, CELL_H=32, PITCH_X=44, PITCH_Y=32`
  - `int x(int slot)`, `int y(int slot)` — child-local coordinates
  - `Rectangle cell(int slot, Rectangle gridBounds)` — canvas rectangle of that cell
  - `int slotAt(Rectangle gridBounds, int canvasX, int canvasY)` — `-1` when outside
- Acceptance:
  - [ ] `x/y` match `(i%4)*44` and `(i/4)*32` for slots 0, 3, 4, 27
  - [ ] `slotAt` round-trips the centre of `cell(s, b)` back to `s` for all 28 slots
  - [ ] A point in the 8px gutter maps to the column on its left; a point past column 4 or row 7,
        or left/above the grid, is `-1`
  - [ ] Plain JUnit 4; only `java.awt.Rectangle`/`Point` imported
- Depends on: nothing

### C4. `BagViewModel.arrange`: placement engine

- Files: `ui/BagViewModel.java`, new `src/test/.../ui/BagViewModelArrangeTest.java`
- Contracts:
  - `void setLayout(BagLayout)` / `BagLayout getLayout()`
  - `int[] arrange(int[] containerItemIds)` — input is 28 entries, `-1`/`0` for empty; output is 28
    entries, `out[containerIndex]` = display slot, `-1` for an empty container slot
  - `boolean consumeLayoutChanged()` — true once if `arrange` had to drop a reservation
- Algorithm (section 2): pass 1 places pinned ids (ascending slot, first unclaimed container copy
  of that id); pass 2 places the rest in ascending container index into the lowest free unreserved
  slot, falling back to the lowest free reserved slot and clearing that reservation.
- Acceptance:
  - [ ] Empty layout: items land in slots 0..n-1 in container order
  - [ ] A pinned id lands in its slot even when its container index moves
  - [ ] Three identical non-stackable copies with one pinned: the pinned slot gets one copy, the
        other two auto-place, no slot is used twice, no copy is dropped
  - [ ] A reservation whose item is absent stays free (nothing else auto-places into it) until the
        bag is full enough to need it, at which point it is consumed and `consumeLayoutChanged()`
        reports true
  - [ ] A full 28-item bag with 28 reservations for absent ids still places all 28 items
  - [ ] Output is a permutation: no duplicate display slots
  - [ ] Plain JUnit 4, no Mockito
- Depends on: C1

### C5. `BagViewModel`: hit-testing and drag state

- Files: `ui/BagViewModel.java`, new `src/test/.../ui/BagViewModelDragTest.java`
- Contracts:
  - `void setGridBounds(Rectangle)`, `int slotAt(Point canvas)` (delegates to `BagGeometry`)
  - `void beginDrag(int sourceSlot)`, `void updateDrag(Point canvas)`, `boolean endDrag(int
    targetSlot)` (returns true when the layout changed), `void cancelDrag()`
  - `boolean isDragging()`, `int getDragSource()`, `Point getDragPoint()`, `int getHoverSlot()`
- Acceptance:
  - [ ] `endDrag` with `-1` or with the source slot cancels and returns false
  - [ ] `endDrag` on another slot swaps in the layout and returns true
  - [ ] Dropping onto an empty slot moves; onto an occupied one swaps
  - [ ] After a swap both affected slots are pinned in the layout (drag pins what it touches)
  - [ ] `cancelDrag` clears state; `endDrag` without `beginDrag` is a harmless false
  - [ ] Plain JUnit 4
- Depends on: C3, C4

### C6. `BagViewController`: read, arrange, re-position, publish

- Files: `ui/BagViewController.java`, new `src/test/.../ui/BagViewControllerTest.java`
- Contracts:
  - `void startUp()` / `void shutDown()` / `void post(Runnable)` / `void tick()` (drain queue, then
    apply if dirty, then publish volatiles)
  - `void markDirty()`, `void onProfileChanged(String profileKey)`, `void cancelDrag()`
  - `boolean isViewOpen()`, `Rectangle getGridBounds()` (volatile reads for the AWT side)
  - `void restoreGameOrder()` — the shutdown path from section 5
  - private `applyLayout()`: view-vs-deposit check on `TITLE`, container read, `arrange`,
    `setOriginalX/Y` + `revalidate()` per visible child
- Acceptance:
  - [ ] With a mocked `Client`, a 3-item container and a layout pinning item B to slot 5, the child
        for B gets `setOriginalX(44)` / `setOriginalY(32)` and `revalidate()`
  - [ ] Hidden children are never touched (no `setOriginalX`, no `setHidden`, no `setItemId`)
  - [ ] `TITLE` text `"Add to bag"` short-circuits: no child is repositioned at all
  - [ ] A null `81:5` widget or a null container 516 is a no-op, no NPE
  - [ ] `restoreGameOrder()` puts child `i` back at `(i%4)*44, (i/4)*32`
  - [ ] `tick()` runs queued runnables in order and swallows a runnable that throws
  - [ ] Dirty layouts are saved through `LayoutStore` at most once a second
  - [ ] JUnit 4 + Mockito, in the style of
        `alch-blocker/src/test/java/io/robrichardson/alchblocker/AlchBlockerPluginBehaviourTest.java`
- Depends on: C4, C5, C2

### C7. Taken/free count in the title

- Files: `ui/BagViewController.java`, `src/test/.../ui/BagViewControllerTest.java`
- Contracts: private `updateTitle(int taken)` called at the end of `applyLayout()`;
  `static String titleText(int taken)` (package-private, pure) returning `"Looting bag (12/28)"`
- Acceptance:
  - [ ] Counts container slots with `id > 0`, so five of one non-stackable counts as five and a
        stack of 3,032 counts as one
  - [ ] Title is set to `Looting bag (n/28)` after each apply
  - [ ] Never sets the title on the "Add to bag" flow, and never when the container reads null
  - [ ] Only `setText` is called on `TITLE`; no listener on it is touched
  - [ ] `restoreGameOrder()` resets the title to `"Looting bag"`
- Depends on: C6

### C8. `BagInputListener`: AWT mouse and Escape

- Files: new `ui/BagInputListener.java`
- Contracts: implements `net.runelite.client.input.MouseListener` and `KeyListener`;
  `@Inject BagInputListener(BagViewController)`; `void publish(boolean viewOpen, Rectangle
  gridBounds, boolean altHeld)` called from the controller's client-thread pass
- Behaviour: section 3.2 and 3.3. Left press inside the grid consumed and armed; 4px slop;
  drag/release consumed only if the press was; right button and mouse-move never consumed; Escape
  and `focusLost()` cancel.
- Acceptance:
  - [ ] A press outside `gridBounds`, or while `viewOpen` is false, or while alt is held, is
        returned unconsumed
  - [ ] A right press inside the grid is unconsumed (Examine still works)
  - [ ] Press, move 2px, release: consumed, no `beginDrag` posted
  - [ ] Press, move 10px, release over another cell: `beginDrag`/`updateDrag`/`endDrag` posted in
        that order, all events consumed
  - [ ] Release outside the grid after an active drag still posts `endDrag(-1)` and is consumed
  - [ ] Escape while dragging posts a cancel and consumes the key; Escape when not dragging is left
        to the game (it closes the interface)
  - [ ] The listener holds no reference to the model and calls no `Client` method
  - [ ] Tested by direct invocation with synthetic `MouseEvent`s and a mocked controller
- Depends on: C5, C6

### C9. `BagDragOverlay`: ghost and target highlight

- Files: new `ui/BagDragOverlay.java`
- Contracts: `extends Overlay`, `OverlayLayer.ABOVE_WIDGETS`, `setPosition(OverlayPosition.DYNAMIC)`;
  `@Inject BagDragOverlay(Client, ItemManager, BagViewController)`
- Behaviour: while a drag is active, draw `itemManager.getImage(itemId, qty, stackable)` centred on
  the cursor at ~60% alpha and a 1px outline around `BagGeometry.cell(hoverSlot, gridBounds)`.
  Renders nothing when the view is closed or no drag is active.
- Acceptance:
  - [ ] Returns null from `render` when not dragging
  - [ ] Draws nothing outside the grid rectangle
  - [ ] Reads only client-thread state (it runs on the client thread), never the AWT fields
  - [ ] Registered in `startUp()` and unregistered in `shutDown()`
- Depends on: C6, C8

### C10. Plugin wiring and lifecycle

- Files: `LootingBagOrganizerPlugin.java`
- Contracts: subscriptions for `ClientTick` (controller `tick()`), `ScriptPostFired` (id 497 ->
  `markDirty()`), `WidgetLoaded(81)` -> `markDirty()`, `WidgetClosed(81)` -> `cancelDrag()`,
  `ItemContainerChanged(516)` -> `markDirty()`, `GameStateChanged(LOGGED_IN)` and
  `RuneScapeProfileChanged` -> `onProfileChanged(configManager.getRSProfileKey())`
- Acceptance:
  - [ ] `startUp()` registers the overlay, `mouseManager.registerMouseListener(0, listener)` and
        the key listener exactly once; `shutDown()` unregisters all three and calls
        `restoreGameOrder()` plus a final save
  - [ ] Registering, shutting down and re-registering leaves exactly one mouse listener
  - [ ] Script id 497 is a named constant with a comment referencing `docs/RESEARCH.md` section 2
  - [ ] Uses `net.runelite.api.gameval.InterfaceID.WildernessLootingbag.*` and
        `InventoryID.LOOTING_BAG`, no legacy `ComponentID`/`InterfaceID`
  - [ ] `./gradlew build` passes
- Depends on: C6, C7, C8, C9

### C11. Manual verification with `runClient`

- Files: none (findings go into `docs/RESEARCH.md` and the card resolution)
- Steps, in the dev client with `--developer-mode`:
  - [ ] Open Script Inspector, check the bag, confirm **497** is the script that builds the grid and
        that it fires again on every deposit/transmit. Correct the constant if it is not 497.
  - [ ] Widget Inspector: confirm `81:5` has 28 dynamic children, empty ones hidden at 0x0, and that
        the occupied ones are at pitch 44x32.
  - [ ] Confirm the modal mode of group 81 (log `WidgetClosed.getModalMode()`, or read
        `getModalMode()` off the `WidgetNode` in `client.getComponentTable()`). Record the value.
  - [ ] Drag an item to another cell: it moves, the arrangement survives closing and re-opening the
        bag, and it survives a relog.
  - [ ] The window does **not** close from our press/drag/release inside the grid; it still closes
        when clicking the world outside it.
  - [ ] Hovering a moved item still shows the yellow `qty x price = total` tooltip, and right-click
        still offers `Examine <item>` for the right item.
  - [ ] "Add to bag" (use an item on the bag, deposit dialog) is completely unaffected: no
        re-positioning, no consumed clicks, deposit works.
  - [ ] Depositing while the View window is open re-applies the layout after the rebuild.
  - [ ] The title reads `Looting bag (n/28)` with the right n, including after a deposit.
  - [ ] Disable the plugin with the window open: the grid snaps back to container order and the
        title goes back to `Looting bag`.
  - [ ] Item Identification and any other overlay on this interface still render correctly on
        moved cells.
- Depends on: C10

### C12. Plugin hub compliance check

- Files: `runelite-plugin.properties`, `build.gradle`, `README.md`, `LICENSE`
- Acceptance:
  - [ ] Java 11 bytecode; Gradle 7.4 wrapper; `build=standard` in `runelite-plugin.properties`
  - [ ] `runeLiteVersion` pinned to the current plugin-hub `runelite.version`
  - [ ] BSD-2 license header present in every source file, `LICENSE` matches
  - [ ] No `net.runelite.client.account` usage, no dependency on another hub plugin
  - [ ] No automation: grep the source for `MenuOptionClicked` invocation, `client.invokeMenuAction`,
        `setDraggedOnWidget`, `KeyEvent` synthesis, any packet or action fired at the game. Expect
        zero hits.
  - [ ] No widget is created, no `setItemId`/`setItemQuantity`/`setAction`/`setHidden` call on a
        game widget; the only writes are `setOriginalX`, `setOriginalY`, `revalidate` and
        `TITLE.setText`
  - [ ] `./gradlew build` clean, no warnings that the packager would reject
- Depends on: C10

### Dependency summary

```
C1 ──> C2 ──┐
C1 ──> C4 ──┴─> C6 ─> C7 ─┐
C3 ──> C4                 ├─> C10 ─> C11
C3 ──> C5 ──> C6          │       └> C12
C5, C6 ──> C8 ──> C9 ─────┘
```

C1 and C3 have no dependencies and can start in parallel; so can C2 once C1 lands.
