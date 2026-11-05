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
| 🦴 Broken neck | Falling 23+ blocks onto rock can break your neck: very slow, no sprinting or jumping, until surgery. Sometimes the spinal cord is hurt too and you're paralysed until surgery. (Like in real life, a broken neck isn't always fatal.) |
| 🩷 Fiberglass insulation | Pink wall fluff (glass + 2 pink wool = 4). Touch it bare-handed and you itch; break it with no helmet and you cough. It looks like cotton candy. **It is not cotton candy.** |

## Phase 4: the mind 🧠

| Thing | What it does |
|---|---|
| 🙂 Mood | 0-100, you start at 70. Being alone, hungry, thirsty, hurt or out in the dark brings it down. Friends nearby, your pets, music from a jukebox, sunshine, meals and a good night's sleep bring it up. |
| 😔 Low mood / depression | Under 30 you feel down and move slower. Under 15 it's depression: slower and weaker. It **never hurts you by itself**, and there's always a way back up. |
| 🎮 Game Console / 📺 Television | Play or watch for +15 mood (once a minute). |
| 🩺 Therapy | The Doctor sells therapy sessions (4 emeralds): +40 mood. In real life too, talking to someone you trust really helps. |
| 📅 Calendar | Shows the real-life date and the Minecraft day. Paper, paper, red dye, clock. |

## Phase 5: tech 💻

| Thing | What it does |
|---|---|
| 🛒 Tech Dealer | A villager job (job block: **Tech Counter**). Sells RAM, SSDs, GPU anti-sag brackets, motherboards, power supplies, CPUs, GPUs and laptops. GPUs and RAM are expensive: blame the AI. |
| 🖥️ PC Case | Right-click it with each part to install it: motherboard, CPU, RAM, GPU, SSD, power supply. Give it **redstone power** and right-click with an empty hand to boot. Sneak + right-click switches the operating system. |
| 😬 GPU sag | No anti-sag bracket? The GPU sags, and on the 3rd boot it cracks and is gone. |
| 💾 Operating systems | Original look-alikes in the style of a retro Mac, a modern Mac, BeOS, NeXT, retro Windows and Linux (no real logos or artwork). Play a game for +15 mood. |
| 💻 Laptop | A computer in your pocket. |
| 🤖 AI Data Center | Feed it RAM, GPUs and CPUs. It eats them all and always wants more. |

## Phase 6: no reviving 💀

| Thing | What it does |
|---|---|
| ⚫ Collapse | Instead of dying you **collapse**: you lie on the ground and your screen goes black. Nothing can hurt you while you're down: the clock decides. You have **60 seconds** (`collapseSeconds`). |
| ⚡ Defibrillator | Hold it, look at the collapsed player (within 3 blocks), press **charge → clear → shock** (Z, X, C by default; rebind in Controls). Copper, redstone, iron and a lightning rod. |
| 🗄️ AED Wall Case | Keep a defibrillator on the wall: right-click with it to store it, empty hand to grab it. |
| 🚑 Ambulance Radio | Use it near a collapsed player: they're rushed to the nearest Operating Table, and if a Doctor is there they get emergency surgery. |
| 💀 Gone for good | Nobody saved you in time? You really die and become a **spectator** (or start over as a new person with `permaDeathSpectator = false`). The void and /kill skip the collapse. |
| ⚫ Black screen | Your screen is pitch black while collapsed, asleep, or under surgery. |

Turn it all off with `noReviving = false`. **Back up your world first!**

## Phase 7: real computers 🖥️

| Thing | What it does |
|---|---|
| 🗂️ Apps | Every computer (PC, laptop, ready-built models) has a desktop with app icons: **Notes** (saved in the computer), **Calculator**, **Terminal** (`help`, `ls`, `date`, `whoami`, `echo`, `apps`, `neofetch`, `clear`). |
| 💿 Software Discs | Buy them from the Tech Dealer and right-click a computer to install: **Paint** (16x16 pixel art, saved), **Snake** (a score of 3+ lifts your mood), **Music** (a little keyboard). |
| 🍎 Ready-built lineup | 13 look-alikes of Macs through the years, with original pixel art and no Apple logo: Compact (1984), Color Compact (1993), Colorful All-in-One (1998), Cube (2000), Lamp (2002), Pro Tower (2006), Slim All-in-One (2012) and Mini Desktops M1 to M6 (the M6 is a "future" model). Every part is already inside; just give it redstone power. They face you when placed. |

## Phase 8: the world 🗺️

| Thing | What it does |
|---|---|
| 🏛️ Countries | `/country create <name>` founds a country and makes you its leader. `/country join`, `leave`, `info`, `list`. The leader sets the tax (`/country tax <0-50>`) and can take money from the treasury (`/country withdraw`). |
| 🚩 Country Flag | Only the leader can place it. It claims the land around it (5x5 chunks). Walking in shows "Welcome to <country>!" |
| 🏧 ATM | Right-click with emeralds: each one becomes 10 money (minus your country's tax). Deposit banknotes, sneak-right-click to take out a 100 banknote, empty hand to see your balance. Your bank account stays after death. |
| 💸 /pay | `/pay <player> <amount>` |
| 📱 Phone | Text other online players, see the time, your balance and country. Both of you need a phone and signal. Texts arrive as server messages, so chat filters don't hide them. |
| 📡 Signal Tower | Phones work within 160 blocks of one. |

A small panel in the top-left shows water, rest, stamina, blood and injuries (only once they matter).
`/realism` shows your numbers (ops can test with `/realism set|bleed|burn`). English and Czech.

**Config** (`serverconfig/realism-server.toml`): days for thirst/sleep, real-life days instead of Minecraft days,
and on/off for each rule.

Coming next: a drivable ambulance, hospital buildings in the world, radiation and diseases, electricity, eras...

MIT license.
