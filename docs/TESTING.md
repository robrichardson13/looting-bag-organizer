# Manual verification checklist (card C11)

Everything below is checked by hand in the dev client. The plugin logs verbosely at `debug` on
every lifecycle and event edge precisely so this session leaves a log we can debug from, so for
each step there is an exact log line to look for.

Nothing in this plugin fires a game action or changes what the bag contains. If any step below
makes the bag's *contents* change, stop and report it: that is a bug, not a test failure.

## 0. Running and capturing the log

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home
./gradlew runClient 2>&1 | tee /tmp/lbo-session.log
```

`runClient` already passes `--developer-mode --debug --profile=dev
--sessionfile=dev-session.properties`, so `--debug` puts the root logger at DEBUG and every line
below appears on the console. RuneLite also writes the same log to
`~/.runelite/logs/client.log` (rotated as `client_<date>.<n>.log`), so if the console scrollback
is lost:

```bash
grep -E 'lootingbagorganizer|Looting bag|Bag ' ~/.runelite/logs/client.log | tail -200
```

Useful filters while testing:

```bash
tail -f /tmp/lbo-session.log | grep -E 'BagViewController|Bag press|Bag drag|Script 497|Widget group 81'
```

Logging in: `runClient` logs into a Jagex account automatically if
`~/.runelite/credentials.properties` exists — see CLAUDE.md for the one-liner that creates it, and
delete the file when the session is over.

To reach the bag: any looting bag in the inventory, in the Wilderness (or with the Wilderness
requirement satisfied), right-click and select "Check" to open the View window. Have at least 5-6 items in it, including one
stack (e.g. coins) and at least three copies of one non-stackable, so the duplicate-id and
stack-counting rules get exercised.

## 1. Plugin lifecycle

- [ ] Enable the plugin. Console shows
      `Looting Bag Organizer started (gameState=LOGGED_IN, listeners registered=true)`
      and `BagViewController started`.
- [ ] It also shows `Profile load requested by startUp: rsProfileKey=<key>` followed by
      `Loaded looting bag layout for profile <key>: [...]`. The key must be non-null; a null key
      means the layout will not persist.
- [ ] Disable the plugin. Console shows
      `BagViewController stopping (layoutDirty=..., profile=...)`, then
      `Restored the game's own cell order for N children`,
      `Restored looting bag title to 'Looting bag'`, then `Looting Bag Organizer stopped`.
- [ ] Enable/disable/enable three times: the "started" line appears once per enable and no
      duplicate drag/press logs appear for a single mouse press afterwards (a duplicated mouse
      listener would log each press twice).

## 2. Ground-truth checks with the dev tools

- [ ] **Script Inspector**: open the bag, confirm the script that rebuilds the grid is **497**.
      The plugin logs `Script 497 fired: looting bag grid rebuilt, re-applying layout`. If the
      Script Inspector shows a different id building 81:5, correct
      `SCRIPT_LOOTING_BAG_BUILD_ITEMS` in `LootingBagOrganizerPlugin` and record the real id here.
- [ ] Confirm 497 fires **again** on every deposit/transmit while the window is open (the log line
      above should repeat).
- [ ] **Widget Inspector** on `81:5`: 28 dynamic children, empty cells hidden at 0x0, occupied
      cells 36x32 at pitch 44x32.
- [ ] **Modal mode of group 81**: close the bag and read
      `Widget group 81 closed (modalMode=?, unload=?): cancelling any drag`. Record the modalMode
      value (expected 3, `MODAL_CLICKTHROUGH`; `docs/PLAN.md` section 3.4 is an inference until
      this is seen).

## 3. Dragging

- [ ] Press inside the grid on an occupied cell:
      `Bag press at (x, y) resolved to slot N (occupied=true), consumed=true`.
- [ ] Drag it more than 4px: `Bag drag from slot N crossed the slop at (x, y); posting beginDrag`,
      then `Drag begin from slot N`, then a stream of
      `Bag drag from slot N moved to (x, y), resolved to slot M, consumed=true`.
- [ ] The overlay draws the item ghost under the cursor and a 1px outline on the hovered cell
      (`Bag drag ghost started rendering`). The source cell keeps rendering in place.
- [ ] Release over another cell: `Drag end N -> M (layout changed: true)` and the item visibly
      moves. `Applied layout: k of 28 children re-positioned, grid bounds ...` follows.
- [ ] Release outside the grid: `Drag end N -> -1 (layout changed: false)`, nothing moves.
- [ ] Escape mid-drag: `Escape cancelled the bag drag from slot N`, nothing moves, and the bag
      window stays open. Escape when *not* dragging still closes the interface (not consumed).
- [ ] Alt-drag inside the grid is not consumed (`... not consumed (leftButton=true,
      insideGrid=false)`), so alt-dragging RuneLite overlays over the bag still works.
- [ ] Click away, losing focus mid-drag: `Focus lost; cancelling the bag drag from slot N`.

## 4. Persistence

- [ ] After a drop, `Saved looting bag layout for profile <key>: [...]` appears within a second
      (writes are coalesced to one per second).
- [ ] Close and re-open the bag: the arrangement is unchanged.
- [ ] Log out and back in, re-open the bag: still unchanged, and the log shows
      `Loaded looting bag layout for profile <key>` with the same slot list.
- [ ] Hop worlds / switch character: `RS profile changed: <old> -> <new>` followed by a load for
      the new key. Arrangements must not leak between characters.

## 5. The window must not close from our own clicks

- [ ] Press-drag-release repeatedly inside the grid: the View window survives every one of them
      (no `Widget group 81 closed` line during the drags).
- [ ] Then click the world outside the window: it still closes normally
      (`Widget group 81 closed (modalMode=..., unload=...)`).

If the window closes on our own press, the consumption argument in `docs/PLAN.md` section 3.4 is
wrong and approach A is in question — record the modal mode and stop.

## 6. The game's own behaviour on a moved cell

- [ ] Hover a moved item: the yellow `qty x price = total` tooltip still appears, positioned on
      the moved cell.
- [ ] Right-click a moved item: `Examine <the right item>` and examining it prints the correct
      examine text.
- [ ] Item Identification (and any other overlay drawing on this interface) still renders on the
      moved cells, in the right place.

## 7. The deposit flow is untouched

- [ ] Use an item on the looting bag to open "Add to bag". No re-positioning happens (no
      `Applied layout` line while it is open), clicks in it are not consumed, and Store-1/5/All/X
      all work.
- [ ] The "Add to bag" title is never rewritten with a count.
- [ ] Deposit while the View window is open: 497 fires, `Applied layout` follows, and the
      arrangement is re-applied after the rebuild.

## 8. Taken/free count

- [ ] The title reads `Looting bag (n/28)` with the right n
      (`Set looting bag title to 'Looting bag (n/28)'`).
- [ ] Five copies of one non-stackable count as five; a stack of 3,032 coins counts as one.
- [ ] After a deposit, n goes up and the title is refreshed.
- [ ] Disable the plugin with the window open: the grid snaps back to container order and the
      title reads `Looting bag` again.

## 9. Runtime assumptions flagged by the implementation cards

Each of these is something the code assumes from a cache dump or from source reading and has never
been seen live. Confirm each one; if any fails, the log line named is where the failure shows up.

| # | Assumption (card) | How to confirm | Failure signature |
|---|---|---|---|
| 1 | Script **497** builds the grid; there is no `ScriptID` constant (card 1) | Script Inspector while opening the bag | No `Script 497 fired ...` line when the bag opens |
| 2 | Group 81 is modal mode 3 (`MODAL_CLICKTHROUGH`); the close is driven by the client seeing a click, so consuming ours keeps the window open (cards 1, 12) | Read modalMode from the close line; drag repeatedly | `Widget group 81 closed` during a drag |
| 3 | `81:5` has 28 dynamic children, empty ones `cc_sethide(1)` at 0x0, occupied at pitch 44x32 (cards 1, 10) | Widget Inspector on 81:5 | `Apply skipped: 81:5 has no dynamic children yet`, or `Applied layout: k of N` with N != 28 |
| 4 | `getDynamicChildren()` is indexed by container slot with hidden empties present (card 10) | Compare `Bag snapshot from container 516: ...` with what the bag visibly holds | Items appear in the wrong cells even before any drag |
| 5 | `items.isHidden()` is false and `getBounds()` is non-null canvas coords whenever the View is on screen (card 10) | Grid bounds in `Applied layout: ... grid bounds java.awt.Rectangle[...]` must match the on-screen grid | Bounds of `[0,0,0,0]`, or drags never resolving to a slot |
| 6 | The deposit flow's TITLE really reads "Add to bag", and the View title never contains that string (cards 10, 11) | Open both flows | `Applied layout` while the deposit dialog is open, or no title/count on the View |
| 7 | `setOriginalX/Y` + `revalidate()` alone is enough (no size or parent revalidate) and does not break hover/Examine (cards 1, 10) | Section 6 above | Cells move but hover/Examine stay at the old position, or cells do not move at all |
| 8 | The game never re-lays cells except through 497 (card 1) | Hover, tick, and idle with the window open | Cells snap back to container order with no `Script 497 fired` line preceding it |
| 9 | Container 516's `getItems()` is container-slot-indexed and may be shorter than 28 (card 10) | Bag with fewer than 28 items | `Bag snapshot from container 516` disagreeing with the visible contents |
| 10 | Container 516 can read null while the View is open (open-and-close within a tick); the title is then left alone rather than written `(0/28)` (cards 10, 11) | Open and immediately close the bag | `Apply skipped: container 516 is null` is fine; a title of `Looting bag (0/28)` on a non-empty bag is not |
| 11 | The AWT tier decides consumption purely from published volatiles, one frame behind at worst (card 12) | Press immediately as the window opens/closes | A press consumed with the window shut, or the first press after opening ignored |
| 12 | `ItemManager.getImage` may return null before the sprite is cached, so the ghost is skipped rather than crashing (card 13) | Drag an item never seen this session | No ghost drawn but no exception; an exception here is a bug |
| 13 | The click AWT delivers *after* a press we consumed is consumed too (`clickConsumed`), so a plain click inside the grid never reaches the client's mouse buffer (card 18) | Single-click an occupied cell without moving the mouse, repeatedly | `Widget group 81 closed` immediately after a `Bag press ... consumed=true` with no drag lines between |
| 14 | A drag pins what it touches, so the drop survives the re-apply that follows it (card 18) | Drop an item you have never dragged before onto an empty cell | `Drag end N -> M (layout changed: true)` followed by `Saved looting bag layout ... [...]` whose list is still empty, or the item snapping back on the next `Applied layout` |
| 15 | A skipped apply keeps the dirty flag and retries next frame instead of losing the signal (card 18) | Open the bag repeatedly, watching the frames around the open | `Apply skipped: ...` logged once with no `Applied layout` ever following for that open window |
| 16 | The empty-bag `"The bag is empty."` TEXT child at dynamic index 28 (RESEARCH section 1.8) is never touched: our loops stop at index 27 (card 18) | Open a completely empty bag | The empty-bag text moved, resized or missing; `Applied layout: k of 29 children` with k > 0 |
| 17 | Disabling the plugin while the "Add to bag" dialog is open leaves its title alone (card 18) | Open Add to bag, disable the plugin from the sidebar | The deposit dialog's title changes to `Looting bag` |
| 18 | Escape is consumed while a press is *armed* on a cell, not only while a drag is active (card 18, minor) | Hold the left button on a cell and press Escape | The interface does not close; releasing then pressing Escape must close it normally |

## 10. Reporting back

Record for card C11: the script id actually seen, the modal mode value, and any row of the table
above that failed, with the surrounding 20 lines of `/tmp/lbo-session.log`. Findings that change
what the code should do go into `docs/RESEARCH.md`.
