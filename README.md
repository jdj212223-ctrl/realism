# Realism 🌍

Real life in Minecraft (NeoForge 1.21.1), built in phases.

## Phase 1: your body

| Rule | What happens |
|---|---|
| 💧 Thirst | Drink or die after **3 days**. Water bottles reset it, milk/soup/fruit help a bit. Crouch in water with an empty hand to drink from it. Warnings: thirsty → dizzy → collapsing. |
| 😴 Sleep | Sleep through a night or die after **11 days** awake. Warnings: yawning → nodding off → hallucinations (sounds that aren't real). |
| ⚡ Stamina | Sprinting and hard work drain it, resting refills it. Keep pushing with none left: out of breath → shaky → blackouts → **death by exhaustion**. Thirst, hunger and cold tire you faster. |
| 💪 Muscle tears | Mining or hitting too fast tears your arm (weaker, slower for 2 days). Tear it again = bad tear (6 days, "needs a doctor" in phase 2). |
| 🦴 Broken legs | Falls of 7+ blocks can break your leg (12+ always do). Landing on rock hurts more and takes longer to heal. |
| 🔥 Burns | Punching a working furnace bare-handed: 1st, 2nd or 3rd degree burn. A Cold Sweat boiler is always 3rd degree. (In VR, touching it with your hand counts.) |
| 🥶 Frostbite | With Cold Sweat: freezing for a minute gives frostbite, and it keeps hurting while you stay frozen. |

## Phase 2: blood 🩸

| Rule | What happens |
|---|---|
| 🩸 Blood | You have **5 liters**. Under 4.2 l you're pale (weaker), under 3.5 l dizzy, under 2.6 l you collapse, and under **2 l you bleed out**. Blood slowly comes back while you're fed. |
| 🔪 Bleeding | Mob hits, weapons, arrows, cactus and explosions can make you bleed: **light** (stops by itself), **heavy** or **severe**. Breaking a leg on rock is an open fracture: heavy bleeding. |
| 🩹 Bandage | Stops light and heavy bleeding. Paper + string + wool = 3. |
| 🎗️ Tourniquet | Holds severe bleeding, but you're slower while it's on. String, leather, string over a stick. |
| 🅰️ Blood types | Everyone gets a real blood type (O+, A-, AB+...) by real-world odds, and keeps it after death. |
| 💉 Syringe + blood bag | A syringe draws 0.5 l from you or a friend into a blood bag. A blood bag gives 0.5 l, **only if the type matches** (O- gives to everyone, AB+ takes from everyone); the wrong type makes you very sick. |

Blocky pixel blood particles while bleeding: turn them off with `bloodParticles = false`.

## Phase 3: the hospital 🏥

| Thing | What it does |
|---|---|
| 🩺 Doctor | A new villager job. Give a villager a **Medical Station** (white concrete, red dye, iron) and they become a Doctor who sells bandages, burn cream, splints, tourniquets and syringes. |
| 🛏️ Operating Table | Right-click it with a **Doctor within 8 blocks** and **3 emeralds**: surgery fixes broken legs, torn muscles, burns and frostbite, stops bleeding and gives back half a liter of blood. You wake up groggy. |
| 🧴 Burn cream | Heals 1st and 2nd degree burns; a 3rd degree burn heals twice as fast. Honey bottle + slime ball. |
| 🪵 Splint | A broken leg heals 3x faster. Stick, bandage, stick. |
| 🦴 Broken neck | Falling 23+ blocks onto rock breaks your neck. No surviving that. |
| 🩷 Fiberglass insulation | Pink wall fluff (glass + 2 pink wool = 4). Touch it bare-handed and you itch; break it with no helmet and you cough. It looks like cotton candy. **It is not cotton candy.** |

A small panel in the top-left shows water, rest, stamina, blood and injuries (only once they matter).
`/realism` shows your numbers (ops can test with `/realism set|bleed|burn`). English and Czech.

**Config** (`serverconfig/realism-server.toml`): days for thirst/sleep, real-life days instead of Minecraft days,
and on/off for each rule.

Coming next: Chromeflesh surgery, no reviving after serious injuries, then eras, computers, countries...

MIT license.
