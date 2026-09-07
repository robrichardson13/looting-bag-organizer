# Research notes

Facts gathered before writing code, so decisions can be checked later. Dates are when the fact
was verified against the RuneLite source at `/Users/robrichardson/Code/robrichardson/runelite`.

## 1. What the game gives us (2026-09-07)

- The looting bag is item container `InventoryID.LOOTING_BAG` (516). It is only transmitted when
  the player checks or views the bag, so its contents are stale between views.
- The "View" window is interface `InterfaceID.WILDERNESS_LOOTINGBAG` (81). Children in
  `InterfaceID.WildernessLootingbag`: `UNIVERSE`, `TITLE` (1), `FRAME`, `ITEMS` (5), `TOTAL`,
  `TOOLTIP`. Item cells are dynamic children of `ITEMS`; DWMS reads child 28 of that widget for
  the "The bag is empty." text and child 1 of the group for the "Add to bag" title
  (`dude-wheres-my-stuff/.../carryable/LootingBag.java`).
- The same group is reused for the deposit ("Add to bag") flow, so `WidgetLoaded` for group 81
  is not by itself "the player is looking at their bag". Check the title.
- There is no server-side reorder action. Every arrangement is cosmetic and lives in our config.
- Related but unused: `VarbitID.LOOTINGBAG_USEALLITEMS`, `VarbitID.LOOTINGBAG_IGNORE_FOOD`,
  `InterfaceID.Bankmain.DEPOSIT_LOOTINGBAG`.

## 2. Re-positioning widget children (precedent)

Core `banktags/tabs/LayoutManager.java` re-lays the bank grid by hooking `ScriptPreFired` for
`ScriptID.BANKMAIN_FINISHBUILDING`, then calling `setOriginalX/Y`, `setItemId`,
`setItemQuantity` and `revalidate()` on each child of `Bankmain.ITEMS`. That is the pattern to
copy: let the game build the bag, then rearrange the children it built.

Open: which clientscript builds interface 81's item cells. Find it with the dev tools Script
Inspector (`--developer-mode`) while opening the bag, then hook `ScriptPostFired` on that id. If
it has no distinct script, fall back to `WidgetLoaded(81)` plus `ItemContainerChanged(516)`.

## 3. Dragging

The bag's item widgets have no drag listener of their own (the bank uses
`ScriptID.BANKMAIN_DRAGSCROLL`). Plan: our own `MouseListener` registered at position 0 via
`MouseManager`, consuming press/drag/release only while the View window is open and the press
landed on an item cell. Same threading model as bankless-bank: AWT decides consumption from
volatiles the client thread publishes; every mutation is posted to the client thread.

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
