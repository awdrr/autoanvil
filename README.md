# Auto Anvil (Fabric 1.21.11)

Opens a panel next to every anvil. It combines the enchanted books in your inventory onto armor and tools,
or into one big book, in the cheapest order. It does all the clicking for you, and when you don't have enough
levels for the next step it **waits for XP**. A friend can splash bottles at you and the mod carries on by itself.

## Using it

1. Put the books and the gear you want to enchant in your **inventory**. Worn armor can't go in an anvil,
   so take it off first.
2. Open an anvil. The panel on the left shows:
   - **Target** (`<` `>` to switch): each item in your inventory that a book fits (netherite first, then
     diamond), and then **"Armor book"**, **"Sword book"** and so on. A book target combines your books into
     one book.
   - **Enchantment list**: tick what you want. Your choice is saved per kind of item (every helmet shares
     one list, every sword shares one, and so on), so "Mending + Unbreaking only" is just two ticks.
     Ticking one enchantment unticks the ones it can't go with (Protection vs Blast Protection, Depth
     Strider vs Frost Walker, etc.).
     - `✔` will be added · `have` already on it · `no book` no book for it · `✖` blocked by something on the
       item · `40+` left out because it would be Too Expensive
   - **To max it, get: …** lists the books you're missing (at max level) for what you ticked.
   - **Steps tab**: every anvil step in order, e.g. `1. Prot4 + Mend  2 lv`, `4. #3 + #1  8 lv` (`#3` is the
     result of step 3). Hover a step to see both inputs, what it gives and the prior-work penalty after it.
     While it's running, the current step is highlighted and finished ones are ticked.
   - **Plan**: number of steps, total levels, the most expensive step, and how much XP you still need
     (roughly how many bottles).
3. Press **Start** (this item / one book), **All items** (every item ticked "in All"; wood, stone, iron,
   gold, leather, chain and copper gear is unticked by default), or **Make all** (keep making combined books
   until the books run out).

**Queue (keybind).** Hover any item in any inventory screen (your inventory, a chest, the anvil) and press
**R**. It gets a gold number on its slot and joins the queue. Press R again to take it back out. In the
world, R queues the item in your hand. At the anvil, press **Queue (n)** and the items are done in that
order. You can keep queueing while it runs (while it waits for XP, for example) and they get added to the
end. The queue also does iron and other low-tier gear that "All items" skips. Rebind R in
Controls → Auto Anvil.

**Several of the same item** (three Netherite Swords, say) are fine. They show as "Netherite Sword #1/#2/#3"
with their slot in the tooltip, each has its own "in All" tick, and books are shared out in order: the
first sword gets the best set, the next gets what's left, and so on.

It only uses books that are in your inventory. If everything won't fit under the anvil's 40-level limit, it
puts on as many as it can, most important first (Mending, Unbreaking, Protection/Sharpness/Efficiency…).

**Order.** By default it uses the **cheapest** order (fewest XP), worked out exactly. On the Steps tab you
can switch to **books first**: all the books get combined into one book, then that book goes onto the
armor in a single step. If one step would be over 40 levels ("Too Expensive!"), it uses the cheapest order
for that item instead, so nothing gets left out.

**Combined books.** "Armor book" with Prot IV, Unbreaking III, Mending, Vanishing, Feather Falling IV,
Aqua Affinity and Depth Strider III makes one book with all seven. Its prior-work penalty is kept as low as
possible, because that penalty gets paid again when the book goes onto armor. A combined book can always
still go onto a fresh item of its kind without hitting "Too Expensive!". After that, choose the boots (or any
armor piece) and press Start to put the book on in one step. Enchantments that don't fit that piece
(Aqua Affinity on boots, for example) are ignored for free.

**Stopping.** Press Stop or close the anvil whenever you like. Anything in the anvil goes back to your
inventory. If the anvil breaks mid-run, place a new one and press Start again. It picks up from the
half-combined books.

## How it works

- `plan/Anvil` is a line-for-line port of vanilla `AnvilMenu.createResult`: book fee = max(1, anvil_cost / 2)
  × level, plus both items' prior-work penalties, a +1 penalty per conflict, and 40+ = Too Expensive.
- `plan/Planner` is an exact dynamic programme over subsets of books (3^n). For each subset it keeps the
  cheapest result per prior-work penalty, and its answers are checked against brute force in the unit tests.
  By default it minimises **XP points**, assuming each step is paid as soon as it's affordable. That's the
  cheapest way when XP is being splashed. Set `"optimizeFor": "levels"` to minimise levels instead.
- `run/Runner` does each step by shift-clicking the inputs into the anvil, waiting for the server's
  result and cost, waiting for levels, then shift-clicking the result out. Every click is checked against
  what the server sends back before the next one, and the wait between clicks scales with your ping.

## Config (`config/autoanvil.json`)

| key | default | |
|---|---|---|
| `actionDelayTicks` | `0` | extra ticks between clicks on top of your ping (0 = every tick); raise it if a server complains |
| `optimizeFor` | `"xp"` | `"xp"` (fewest XP points while splashing) or `"levels"` |
| `combineBooksFirst` | `false` | the Steps-tab order switch: `true` = all books into one book, then one step onto the item |
| `chatMessages` | `true` | progress and results in chat |
| `profiles` | | your ticks per kind (`helmet`, `boots`, `sword`, `spear`, `book_armor`, …) |

## Kit Factory

Makes whole kits by itself. It walks your trading hall and **buys the diamond helmet, chestplate, leggings,
boots, sword, pickaxe and axe from villagers**. The **spear is crafted** from the diamonds and sticks in your
input chest. It buys the enchanted books from librarians. It gets all its emeralds and XP by running `/string`
and trading the string to a fisherman; it never takes emeralds or emerald blocks from chests. Then it combines
everything on the anvil with Auto Anvil and stores the finished items in your output chests. It makes 27 of each
item by default. `/kitfactory items` changes the amount per item and whether each one is bought or crafted.

It only presses keys and clicks like a player would: walking, turning, right-clicking villagers and blocks,
and clicking slots. It never walks or turns while a screen is open. Walking to a villager, it keeps looking at
it and strafes (A/D, or W/S plus A/D) instead of turning away to walk. Where the floor between two walkway
points is clear it cuts straight across, so a walkway that loops round the hall isn't walked all the way round.

**Batches**: armor pieces are made, enchanted and stored 3 at a time, swords, axes and spears at least 2,
pickaxes at least 3 (`batchMin` / `batchMax`). To fit a batch it first clears the inventory: leftover string
is traded away, spare emeralds are packed into emerald blocks at the crafting table (by dragging stacks across
the grid, like a player), and the blocks go into the input chest, or are thrown at the drop spot once that's
full (`/kitfactory spare drop` throws them every time). After crafting, leftover diamonds and sticks go back
in the input chest. Pickaxes take Silk Touch over Fortune. The one exception is
`stringInGui` (on by default): to keep the fisherman's screen open, the mod sends `/string` itself,
because you can't type in chat with a screen open. Set it to `false` to have it close the screen and type
the command into chat instead, which is slower.

### Before the first run

- **Villagers**: a librarian for every enchantment you ticked (it buys the cheapest one it found), a
  **fisherman who buys string**, and **armorers, toolsmiths and weaponsmiths selling the diamond gear**.
  If an item on the buy list has no seller, the factory stops and tells you. It never crafts those items.
- **Grindstone at the base** (recommended). Villager gear comes with random enchantments. If every sale of an
  item has one that clashes with yours (Fire Protection vs Protection IV, Bane vs Sharpness), it buys the
  cheapest and grinds it clean first. Sales without a clash are preferred.
- **Input chest(s)**: a chest or double chest of **plain books**, plus diamonds and sticks for whatever is
  crafted. 27 spears need 27 diamonds and 54 sticks. No emeralds needed. Spare emeralds from string trading
  are packed into blocks and stored here, so leave some room. A spare anvil is optional: anvils don't break
  on your server.
- **Drop spot** (optional): stand next to the walkway, look where spare emerald blocks should go (lava, a
  cactus, off an edge) and type `/kitfactory dropspot`. Used once the input chests are full.
- **Output chest(s)**: empty, with room for all 216 items. Gear doesn't stack, so that's 4 double chests.
- **Walkway**: a clear path along the villagers. Walk it down the middle and add a point at each corner and at
  the far end. A villager more than 3 blocks from that line is reached by stepping up to 2.5 blocks off it,
  over open floor. The survey walks the whole walkway, so villagers the server hasn't sent you yet (far down a
  long hall) are found too. It aims around fences, trapdoors and workstations in front of a villager. When
  it's done, it lists any villager it skipped and why, with its position.
- **Base**: one spot where the anvil, crafting table, grindstone and chests are all **within 4.5 blocks**.
- **Your ticks**: open an anvil and tick the enchantments you want for each kind (helmet, boots, sword,
  spear, ...). The factory makes exactly those, at the best level a librarian sells.
- Start with an **empty inventory** and no armor in your hands.

### Setup commands

```
/kitfactory base          stand at the base; finds the anvil, crafting table and grindstone in reach
/kitfactory path add      at each corner of the walkway and at the far end
/kitfactory chest input   look at the books/diamonds chest (repeat for more)
/kitfactory chest output  look at an output chest (repeat for more)
/kitfactory survey        walks the hall once and opens every villager to record trades and prices
/kitfactory trades        check or edit prices (an edited price is kept), untick sales you don't want used
/kitfactory items         how many of each item, and Buy (villager) or Craft (input chest) per item
/kitfactory start         go (Esc or /kitfactory stop stops it)
```

The same per-item settings as commands: `buy <item> [n]` and `craft <item> [n]` (e.g. `craft pickaxe 10`
crafts 10 diamond pickaxes from the chest instead of buying them), `set <item|all> <n>` (amount only).
Item names: helmet, chestplate, leggings, boots, sword, pickaxe, axe, spear.

Also: `status`, `reset` (count from 0 again), `dropspot` / `dropspot clear`, `spare chest|drop`,
`forget` (drop all recorded trades), `string <command>` (if your server's string command has another name).
There is also a "Start / stop Kit Factory" key in Controls (unbound by default).

### Config (`config/autoanvil-factory.json`)

| key | default | |
|---|---|---|
| `buy` | helmet, chestplate, leggings, boots, sword, pickaxe, axe | bought from villagers, never crafted; the rest of `targets` is crafted (the Buy/Craft buttons in `/kitfactory items`) |
| `targets` | 27 of each, spear included | how many to make, in this order |
| `stringCommand` | `"string"` | the server command that gives string (it fills every free slot) |
| `stringInGui` | `true` | send it with the fisherman's screen open (see above) |
| `stringAskBelow` | `128` | ask for more string when less than this is left |
| `xpLevel` | `30` | trade XP up to this level, spend it at the anvil, come back for more |
| `batchMin` | armor 3, sword/axe/spear 2, pickaxe 3 | fewest of an item worked on together; room is made for them |
| `batchMax` | armor 3, others 4 | most of an item worked on together |
| `keepEmeralds` | `192` | loose emeralds kept when packing the rest into blocks (more if the next books cost more) |
| `spareEmeralds` | `"chest"` | `"chest"`: store blocks in the input chests, thrown at the drop spot once full; `"drop"`: always thrown |
| `dropSpot` | none | set with `/kitfactory dropspot` |

## Building / testing

```
./gradlew build                     # jar in build/libs, runs the planner unit tests
./gradlew runClient -Pselftest      # end-to-end test in a real survival world (see SelfTest.java)
./gradlew runClient -Pselftest=factory   # Kit Factory end to end in a test hall (see FactoryTest.java)
```

The self-test puts books and gear in a survival inventory and clicks the panel the way a player would. It
splashes XP bottles while the mod waits, then checks every step's server-side cost against the plan and
reads every result back from the server. Scenarios: a helmet with 6 books from level 0, a chestplate with
only Mending + Unbreaking ticked, a 7-enchantment armor book and then that book onto boots, "All items" on
a sword + spear + pickaxe (an iron axe is skipped), three identical swords with one left out, an anvil vanishing mid-run and the run resuming, an
over-worked helmet where only the most important enchantments fit, the Steps tab (scrolled through), the
books-first order on a helmet, books-first boots that fall back to the cheapest order, and the queue key
pressed for real over hovered slots (including one item queued while the run is going).
