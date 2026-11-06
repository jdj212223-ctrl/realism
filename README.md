# Realism 🌍

Real life in Minecraft (NeoForge 1.21.1), built in phases. **1.0.0: everything is in. 1.2.0: surgeons, knives, Czech food, murder evidence.**

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

## 0.8.1: hospitals in the world 🏥

Hospital buildings now generate in plains, forests, taiga, savanna, meadows and snowy biomes (only in new chunks). Each has a Doctor, an Operating Table, a Medical Station, 2 hospital beds, an AED wall case with a defibrillator, and a supply chest. Find one with `/locate structure realism:hospital`. The Ambulance Radio now rushes patients to these hospitals too.

## 0.8.2: call the ambulance 📱🚑

The phone has a **🚑 Call ambulance** button, and there's an `/ambulance` command (no cheats needed): with a phone and signal, the nearest collapsed player within 256 blocks is rushed to the nearest hospital. The COLLAPSED alarm now includes coordinates. Computers no longer lag on the retro desktops. **Dreams and nightmares:** while you sleep the black screen shows drifting dreams; you wake up with a dream (+5 mood) or, when you're feeling low or very tired, a nightmare (-5 mood). Turn the sleep screen off with `blackScreenWhenSleeping = false`.

## 1.0.0: everything 🌍✨

### 📜 Eras
The first time an operator opens a world with Realism, they choose where history starts:
- **Year 0**: invent everything yourselves. The first time anyone makes an invention, the year jumps to when it was really invented: a book (printing, 1450), a compass (1600), a clock (1700), a Generator (electricity, 1880), a Medical Station (modern medicine, 1900), an Ambulance (1930), a TV (colour TV, 1950), a PC Case (1980), a Phone (2007) and the AI Data Center (2026). `/era` shows the list.
  - ⚫⚪ **Black and white** until colour TV is invented (`oldTimesVision` in the client config).
  - 🎩 **Fancy period clothes**: everyone starts each life in a top hat, tailcoat, fancy trousers and buckled shoes (wearing the full outfit lifts your mood).
  - 💀 **Brutal**: before modern medicine you take 1.5x damage, have half as long to be saved when you collapse, and surgery, defibrillators, blood bags and antibiotics don't work yet.
- **2026**: today's world, everything is already invented.

### ☢️ Radiation and diseases 🦠
| Thing | What happens |
|---|---|
| ☢️ Uranium | Uranium Ore (deep underground, glows a little) and Raw Uranium are radioactive. The dose adds up in sieverts: 1 Sv sick, 2 Sv weak, 4 Sv hurting, **8 Sv deadly**. Away from it your body slowly repairs. |
| 📟 Geiger Counter | Hold it: it clicks faster the more radiation there is, and shows the dose. |
| 💊 Anti-radiation pills | Halve what you take in for 10 minutes (dried kelp x2 + bottle: iodine comes from seaweed). |
| 🤧 Flu | Catch it from the freezing cold, rain with no hat, or a coughing friend. Slow and tired for 2 days. Flu medicine (honey bottle + sugar) makes it pass. Never deadly. |
| 🤢 Food poisoning | Rotten flesh, raw chicken, spider eyes, pufferfish: thirsty and queasy for a day. Water helps. |
| 🌡️ Infection | A wound left bleeding for 2 minutes, zombie bites, fiberglass cuts. A fever that gets worse for 2 days, then sepsis. **Antibiotics** (bread + brown mushroom + bottle: penicillin comes from mould) or surgery cure it. |
| 🎗️ Cancer | Rare, much likelier after radiation. 4 slow stages (3 days each), you only feel tired. The **Medical Scanner** finds it: stage 1-2 surgery removes it, later **chemotherapy** (from the Doctor) often pushes it back a stage. No gross pictures, and there's always treatment. |
| 🩻 Medical Scanner | Needs electricity. A full check-up: blood, radiation, illnesses, cancer. |

### ⚡ Electricity
**Generator** (100 W, burns coal) and **Solar Panel** (40 W, daylight), joined by **Power Cables**. Devices: **Electric Lamp** (10 W), computers (60 W, redstone still works too), Medical Scanner (50 W), Patient Monitor (20 W). Use too much and the whole grid **blacks out**. Right-click a generator to see the grid.

### 🏠 Home
| Thing | What it does |
|---|---|
| 🍳 Grandma's Stove | The old cast-iron stove from grandparents' houses: cooks food 2x fast, warms the room (Cold Sweat), makes everyone near it cosy, and its food is **made with love** (extra mood). It's hot: touch it and you get burned. |
| 🔨 No crafting table | Real life has no magic crafting box (`noCraftingTable`). Build a **Workbench** (2 planks over 2 logs) and hold a **Hammer** (2 cobblestone + stick) to craft. Every craft wears the hammer down. The 2x2 inventory grid still works. |
| 🎙️ Voice-only chat | With Simple Voice Chat installed, typed chat is off: talk with your voice. Commands and phone texts still work (`voiceOnlyChat = false` brings typing back). |
| 🥽 VR burns | With Vivecraft, your real hand touching a burning furnace or boiler burns you. |
| 🔊 Echo | Sounds echo in big caves and halls (`echo` in the client config). |
| 🩷 Fiberglass cuts | Bare-handed fiberglass can cut you: a little bleeding, sometimes an infection. |

### 🚑 The hospital
| Thing | What it does |
|---|---|
| 🚑 Ambulance | A real, drivable van (W/S to drive, A/D to steer). Drive up to a collapsed player and they're loaded in the back by themselves: the siren and lights go on and the paramedics keep them alive 4x longer. Reach an Operating Table with a Doctor and they get emergency surgery. Sneak-punch an empty one to pick it up. The Doctor sells them too. |
| 💓 Patient Monitor | Shows the nearest patient's heart rate, a moving heart trace (flat when collapsed), blood and oxygen, and beeps with every beat. Needs electricity. |
| 🦾 Chromeflesh surgery | With the Chromeflesh mod: hold an implant and use the Operating Table with a Doctor (8 emeralds) to have it put in properly, under anesthetic. |

### ⚖️ The law
Countries' leaders are the government. Murder (players or villagers) and theft (opening someone else's chest in a country you're not a citizen of) go on your record by themselves. Leaders use `/law setjail`, `/law arrest <player>` (5 minutes per crime), `/law release`, `/law pardon` and `/law record`. **In old times only**, a jailed murderer can be sentenced with `/law sentence`: the screen just fades to black, they're gone for good, and their **remains** stay where they fell (blocky pixel blood only, and only with `bloodParticles` on). Modern times have no death penalty, like Czechia since 1990. Players who are gone for good leave remains too (`remains`).

## 1.2.0: surgeons, knives, Czech food and murder evidence 🩺🔪🇨🇿🕵️

| Thing | What it does |
|---|---|
| 🫳 Dragging | Sneak + right-click a collapsed player with an empty hand to drag them behind you (again to let go). Right-click an Operating Table while dragging to lay them on it. |
| 🩺 Be the surgeon | A **Surgery Kit** (iron, shears, 2 bandages): use it on a patient at an Operating Table and stay close for 5 seconds. It fixes what a Doctor's surgery fixes and brings a collapsed patient back. Success starts at 60% and every surgery makes you better (up to 98%); a mistake makes them bleed. Hold a Chromeflesh implant in your other hand to put it in. |
| 🔪 Knives | Ancient times have swords. **From 1900** (modern medicine) swords turn into knives of the same material, keeping enchantments and wear (`modernKnives`). Knives are quick, often make people bleed, and **cut food**: knife in one hand, food in the other, right-click. Craft them in the 2x2 grid (material + stick, diagonal). |
| 🇨🇿 Czech food | Cooked on Grandma's Stove: **houskový knedlík** (cut it into slices), **svíčková**, **guláš**, **řízek**, **smažený sýr**, **bramboráky**, **kulajda**, **chlebíčky**, **koláče**, **buchty**, **rohlíky** and **párek v rohlíku**, plus cheese and bread slices. Czech home cooking lifts your mood. Dobrou chuť! |
| 🕵️ Murder evidence | Attacking a player is **assault**; if they die of it, it's **murder** on the attacker's record, even if they log off. **Witnesses** within 32 blocks who see it are told (and then it counts even outside any country). Weapons carry the **fingerprints** of whoever last hurt someone with them. A **Forensics Kit** on remains shows the cause of death, the weapon and the fingerprints; with a weapon in your other hand it checks it for prints. |

A small panel in the top-left shows water, rest, stamina, blood and injuries (only once they matter).
`/realism` shows your numbers (ops can test with `/realism set|bleed|burn`). English and Czech.

**Config** (`serverconfig/realism-server.toml`): days for thirst/sleep, real-life days instead of Minecraft days,
and on/off for each rule.


MIT license.
