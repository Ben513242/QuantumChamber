# Quantum Superposition Chamber — Design Specification

> Status: Living design document  
> Target: Minecraft Java Edition  
> Document stage: Gameplay / architecture specification before implementation  
> Revision: 0.7 — Code-agent handoff audited
>
> **Code-agent handoff:** Read the entire specification, then follow **Section 32 — Code Agent Execution Contract**. Begin with M0 and do not jump directly to dynamic Universes. M3 and M7 contain explicit research/design gates and must not be guessed through.

---

## 1. Project Concept

The mod introduces a **Quantum Superposition Chamber**: a hollow bedrock cube whose door can transition the occupants into a superposition state after the activation condition is satisfied.

The chamber is not a Nether-style portal. It is a persistent **cross-universe anchor** that allows players to move among an effectively unbounded set of persistent parallel universes.

The intended experience is inspired by the idea of infinitely many possible lives / worlds:

- some universes may be almost identical to the current world;
- some may share terrain but have divergent history or contents;
- some may use a completely different world seed;
- previously visited universes persist permanently;
- multiple players can travel together, become separated, and potentially meet again later;
- the chamber itself is a globally projected invariant, while the environment immediately outside it may be completely different or dangerous.

---

## 2. Core Design Principles

### 2.1 Universe persistence

Every universe that has ever been observed/materialized is permanently preserved.

A universe must never be silently rerolled, regenerated as a different universe, or reused for another identity.

**Invariant:**

> The same `Universe UUID` always refers to the same persistent world history.

Persistent state includes normal Minecraft world state such as:

- generated chunks;
- placed and destroyed blocks;
- player-built structures;
- containers and inventories;
- entities;
- deaths and entity removal;
- environmental changes;
- all other ordinary persistent world modifications.

Unobserved possible universes do not need to exist physically. They may remain only as potential candidates until selected for the first time.

---

### 2.2 Universe identity is global

A universe is a **server-global world**, not a child owned by a particular Quantum Chamber.

Multiple independent chambers may lead to the same universe.

Example:

```text
Quantum Chamber A ─┐
Quantum Chamber B ─┼──> Universe #91821
Quantum Chamber C ─┘
```

If players arriving through different chambers select `Universe #91821`, they enter the same persistent world and may encounter each other.

---

### 2.3 Absolute coordinates are invariant during transfer

Universe transitions preserve absolute coordinates.

A player at:

```text
X = 1250.25
Y = 72
Z = -340.75
```

must arrive in the destination universe at the same coordinates.

The system must not perform Nether-style coordinate scaling **for Quantum cross-Universe transfer**. The destination remains in the same `DimensionRole` as the source Chamber (Overworld->Overworld, Nether->Nether, End->End).

By default it must also avoid:

- safe-position searches;
- automatic Y adjustment;
- nearby platform generation;
- automatic tunnel creation;
- automatic fluid removal;
- automatic structure relocation.

Player yaw/pitch should be retained. Velocity is provisionally intended to be retained as well.

---

### 2.4 Global time

All universes share the same global world time.

Example:

```text
Global quantum time = 583742 ticks
Universe #0          = 583742
Universe #17         = 583742
Universe #91821      = 583742
```

An unloaded universe must resynchronize to the global time when loaded.

Current design does **not** introduce time-dilated or asynchronous universes.

Weather may remain universe-specific unless changed later.

---

## 3. Quantum Chamber

### 3.1 Physical form

The chamber is a hollow cube made from bedrock or a bedrock-derived mod structure.

It has:

- an enclosed interior;
- one operable wall/door;
- a stable orientation;
- a globally unique Chamber UUID;
- a fixed absolute position;
- a defined bounding volume;
- an activation state;
- an Origin Universe.

The Chamber size is **locked at `7 × 7 × 7` blocks** for the first implementation.

The **MVP geometry is also locked** so a code agent does not need to invent a structure:

```text
External bounding box: 7 × 7 × 7
Wall thickness:         1 block
Usable interior:        5 × 5 × 5
Front face:             1-block bedrock frame surrounding a 5 × 5 Quantum Bulkhead
Door opening:           the central 5 × 5 bulkhead opens as one logical door
Controller:             one custom Controller block in the top-center front frame
```

Orientation-relative local coordinates use `x=0..6`, `y=0..6`, `z=0..6`, with the door-facing wall at local `z=0`. The interior is `x=1..5`, `y=1..5`, `z=1..5`. The door aperture occupies `x=1..5`, `y=1..5`, `z=0`; the remaining outer frame is invariant bedrock except for the Controller at the top-center front frame.

The closed bulkhead should visually reference the vanilla `minecraft:block/bedrock` texture/model language where practical, so ordinary resource packs that replace bedrock also influence the closed Chamber appearance. The bulkhead itself remains a custom multiblock because it must open/close as one server-authoritative passage.

For M1, manual/creative construction is acceptable. A survival-friendly construction ritual/recipe is **not a blocker for core implementation** and must not be invented silently by the code agent.

---

### 3.2 Chamber identity

Each chamber has a globally unique identity independent from Universe identity.

```text
UniverseUUID != ChamberUUID
```

Example record:

```text
Chamber UUID: Q-781
Origin Universe: #0
Origin Dimension Role: OVERWORLD
Position: (100, 64, -300)
Facing: EAST
Enabled: true
Destroyed: false
```

---

### 3.3 Origin and projections

A chamber is physically created in one **Origin Universe**.

Once created, that event becomes globally significant: the same chamber is projected into every universe at the **same absolute coordinates and orientation in the same `DimensionRole` in which it was created**.

A Chamber created in an Overworld projects to every Universe's Overworld. A Chamber created in a Nether projects to every Universe's Nether. A Chamber created in an End projects to every Universe's End. It does **not** automatically create three copies inside all three roles of one Universe.

Example:

```text
Q-A = (100, 70, 100)
Q-B = (7000, 65, -5000)

Universe #0
  Q-A at (100,70,100)
  Q-B at (7000,65,-5000)

Universe #17
  Q-A at (100,70,100)
  Q-B at (7000,65,-5000)

Universe #91821
  Q-A at (100,70,100)
  Q-B at (7000,65,-5000)
```

Only the Origin instance has ownership semantics.

Other manifestations are **Projection Chambers**.

---

### 3.4 Chamber geometry is invariant; surroundings are not

The chamber's own volume is protected as a cross-universe causal invariant.

When a projection materializes, the chamber volume itself is guaranteed to exist correctly.

The world immediately outside that volume is **not modified for safety**.

Therefore the door may open directly into:

- a mountain;
- solid stone;
- a cave;
- an ocean;
- lava;
- open sky;
- a cliff;
- a structure;
- another player's building;
- any other naturally or historically existing state of that universe.

This is intentional gameplay.

Example:

```text
Universe #0
[ Chamber ] -> grassland

Universe #281
[ Chamber ] -> lava

Universe #913
[ Chamber ] -> open air

Universe #5512
[ Chamber ] -> solid mountain
```

No automatic safe exit is created.

---

### 3.5 Retrocausal projection

If a new Quantum Chamber is created in one universe, its projections may overwrite blocks already occupying the corresponding chamber volume in other universes.

This is accepted as a deliberate mechanic rather than treated as an error.

Conceptually this is a **retrocausal projection**: once the chamber exists as a quantum anchor, its invariant geometry becomes represented across all universes.

Only the chamber volume is forcibly overwritten. The surrounding world remains unchanged.

---

### 3.6 Global chamber collision rule

Because every chamber projects globally, chamber placement cannot be validated only against the current universe.

Before a new chamber is created, its bounding volume must be checked against every active Chamber Anchor in the Global Chamber Registry.

If two chamber bounding boxes overlap **within the same `DimensionRole`**, creation is rejected.

Chambers in different roles may share the same XYZ because they never project into the same physical `ServerWorld`.

This prevents contradictory global projections.

### 3.7 Vanilla redstone activation

A Quantum Chamber may be activated by **ordinary vanilla redstone circuitry**. The mod must not introduce a separate electrical network merely to arm the Chamber.

A dedicated Chamber Controller / activation input participates in normal Minecraft redstone rules and can therefore be driven by ordinary components such as:

- redstone dust;
- levers;
- buttons;
- repeaters;
- comparators;
- observers;
- pressure plates;
- redstone torches;
- other blocks/mods that correctly provide vanilla redstone power.

Activation semantics are **rising-edge triggered** rather than level-triggered:

```text
0 -> powered
    -> attemptArm() exactly once

power remains ON
    -> do not repeatedly retrigger

power returns to 0
    -> controller becomes eligible for the next rising edge
```

A redstone pulse may begin Superposition only when the normal activation invariants are already valid:

```text
Chamber Anchor enabled
+ Chamber structurally valid
+ physical door closed / Chamber sealed
+ all participating occupants inside the Chamber
+ every participating occupant has QuantumState buff
+ no conflicting active Superposition Session
+ redstone rising edge
= activation accepted
```

If the pulse arrives while validation fails, no Universe is selected and no partial Session is created. A later valid activation requires another rising edge.

Redstone activation is deliberately separate from Anchor lifecycle authority:

- redstone does **not** destroy the Origin Chamber;
- redstone does **not** globally disable/enable an Anchor by itself;
- Projection Chambers may expose the same activation input while the global Anchor is enabled;
- Origin-only disable/destruction rules remain controlled by `ChamberLifecycleService`.

The first implementation should also expose a vanilla comparator-readable diagnostic output from the Controller so redstone contraptions can react without client UI. Proposed provisional signal contract:

```text
0   = invalid / dead / disabled
3   = idle but structurally valid
7   = sealed and activation requirements currently satisfiable
11  = active Superposition / collapsing
15  = selected passage ready
```

Exact comparator levels may be rebalanced, but the architecture should reserve a stable server-authoritative status output.

---

## 4. Chamber Lifecycle

### 4.1 Origin ownership

Only a chamber's Origin Universe may authoritatively:

- disable it;
- destroy it;
- modify future owner-controlled lifecycle state.

Projection Chambers cannot independently destroy or disable the global anchor.

---

### 4.2 Disable

If an Origin Chamber is disabled:

```text
Q-781.enabled = false
```

all projections become inactive.

The physical chamber may remain visible, but it no longer:

- activates superposition;
- produces the infinite corridor;
- performs universe collapse;
- allows quantum travel.

Players in other universes may therefore become stranded if they do not have access to another working chamber.

---

### 4.3 Destruction

If the Origin Chamber is destroyed, its Quantum Anchor is permanently severed.

Existing universes are **not deleted**.

A destroyed anchor does not erase the worlds previously reached through it.

Players stranded in those universes may only escape through another valid chamber or another future mechanic.

Destroyed projections remain physically present as **Dead Chambers**: inert 7 × 7 × 7 shells that no longer activate Superposition, no longer create passages, and can no longer be restored through that severed Anchor. They remain as persistent evidence that the cross-universe Anchor once existed.

---

## 5. Activation Sequence

The agreed activation sequence is:

**Participant membership rule:** at the activation rising edge, every non-spectator player whose bounding box is inside the sealed Chamber interior is automatically a participant. There is no client-side opt-out subset. Spectators are ignored. If even one included player lacks `QuantumState`, activation fails for everyone. Mobs/entities do not become cohort members merely by being inside the room.

1. Player(s) enter the Quantum Chamber.
2. Chamber door is closed and the Chamber becomes sealed.
3. **Every player who will participate in the shared Superposition Session must possess the required `QuantumState` buff.**
4. The buff is obtained from a **Brewing-system potion implemented by this mod**. Brewing ingredients, recipe progression, potion duration, and balance remain tunable gameplay data, but the potion/buff system is part of the mod rather than an external dependency.
5. The Chamber Controller validates structural integrity, occupant membership, buff state, lifecycle state, and that no conflicting Session exists.
6. A **vanilla redstone rising edge** received by the Chamber Controller is the baseline activation command. Ordinary levers/buttons/redstone logic may therefore arm the Chamber.
7. If validation succeeds at the rising edge, a Superposition Session is created. If validation fails, the pulse is rejected without partial activation.
8. A valid sealed chamber enters **Superposition State**.
9. The interior visually becomes an infinitely extending corridor containing many doors.
10. Player chooses a door.
11. Opening a door is the **measurement event**.
12. Measurement immediately collapses the superposition.
13. The infinite corridor disappears immediately.
14. The chamber returns visually to a single finite room.
15. The opened door becomes bound to one destination Universe for that passage.
16. If the destination is already ready, the passage becomes traversable immediately. If a brand-new Universe still requires runtime creation/chunk preparation, the door enters the short server-authoritative `STABILIZING` state defined in Section 20.4; collapse is **not** delayed or undone.
17. A player crossing a ready threshold transfers instantly to that Universe.
18. Transfer preserves absolute coordinates.
19. No Nether-portal animation or waiting sequence is shown.

### 5.1 Locked M1 Brewing baseline

To make the specification executable without inventing balance rules, M1 uses the following **development-default** recipe and IDs:

```text
Status effect ID: quantumchamber:quantum_state
Potion ID:        quantumchamber:quantum_state
Base duration:    3600 ticks (3:00)
Amplifier:        0
Brewing recipe:   minecraft:awkward + minecraft:echo_shard
                  -> quantumchamber:quantum_state potion
```

Register the recipe through Fabric API's `FabricBrewingRecipeRegistryBuilder.BUILD` / `registerPotionRecipe` API for the pinned 0.102.0+1.21 baseline. Vanilla potion-container conversions (splash/lingering) may be supported when they work through normal brewing semantics, but are secondary to the drinkable potion baseline.

**Consumption rule:** effects are checked atomically on successful activation. If validation fails, no player's effect is removed. When `ARMING -> SUPERPOSITION` succeeds, the server consumes/removes `QuantumState` from all cohort participants and freezes membership into the `SuperpositionSession`; the session does not later depend on potion duration. This gives one successful Chamber activation per dose and avoids mid-corridor expiration races.

The ingredient and duration remain balance-configurable later, but the code path, registry IDs, and one-dose-per-successful-activation semantics are the development baseline unless this specification is revised.

---

## 6. Superposition and Measurement

### 6.1 Infinite corridor — approved Hybrid model

While the chamber is sealed and valid occupants possess the required activation state, its interior appears to extend infinitely.

The approved implementation direction is a **Hybrid non-Euclidean corridor**. The chamber remains a real structure in the active Universe, while corridor space beyond the chamber is represented by a finite, server-authoritative **Superposition Space** that can be recycled seamlessly to appear unbounded.

Conceptually:

```text
Current Universe

┌──────── Quantum Chamber ────────┐
│                                 │
│ Player -> corridor threshold    │
└──────────────┬──────────────────┘
               │ seamless transition
               ▼

Hidden Superposition Space
┌─────────────────────────────────────┐
│ Door -- Door -- Door -- Door -- ... │
│     finite segments are recycled    │
└─────────────────────────────────────┘
```

The player-facing experience must remain continuous:

```text
close door + QuantumState buff
        -> chamber enters superposition
        -> interior appears to expand
        -> player can continue through an apparently endless corridor
        -> doors continue to present new QuantumCandidates
        -> opening one door causes measurement/collapse
        -> corridor disappears immediately
        -> the experience returns to one finite chamber
```

The Hybrid model has the following approved properties:

- the infinite corridor is **not** generated as infinitely many normal world blocks/chunks;
- corridor collision and authoritative player position are server-side, not a client-only hallucination;
- only a finite number of corridor segments need to physically exist at one time;
- corridor segments may be recycled using seamless repositioning / coordinate remapping;
- the client renders the transition so the player does not perceive a Nether-style portal effect, loading overlay, or deliberate transition animation;
- players participating in the same Superposition Session share the same logical corridor and candidate-door state;
- entity position, dropped items, projectiles, collision, sound, and door interactions should remain compatible with normal server-authoritative Minecraft behavior where practical;
- client rendering is responsible for hiding the finite/recycled nature of the space, but must not independently decide gameplay state;
- the design should avoid recursive rendering dependence where the same result can be achieved with finite geometry plus repositioning, to reduce shader/render-mod compatibility risk.

The first implementation **locks** the low-level representation to one static hidden dimension: `quantumchamber:superposition`.

The seamless illusion is implemented as a **hidden replica transfer**, not as impossible vanilla cross-dimension geometry:

1. before activation completes, the server allocates/preloads a session entrance cell in `quantumchamber:superposition` containing an interior replica geometrically identical to the sealed 7 × 7 × 7 source Chamber;
2. when `ARMING -> SUPERPOSITION` succeeds, the whole cohort is directly transferred to that replica while the room is sealed and visually identical;
3. the replica then exposes the logical infinite corridor;
4. opening a logical corridor door triggers measurement;
5. on `COLLAPSING`, all remaining corridor participants are direct-transferred back into the source Chamber's finite interior before the source physical bulkhead is presented as the selected passage.

No portal block, Nether portal overlay, nausea, cinematic, or intentional loading screen is used. A normal one-frame/world-synchronization hitch caused by Minecraft/Iris rebuilding dimension state is acceptable for the first release; **functional shader compatibility and absence of a portal animation are hard requirements, zero-frame visual continuity is not**. Destination/session cells should be preloaded to minimize the hitch.

### 6.1.1 Approved corridor geometry

The player-facing Superposition Corridor is a **single long straight corridor with doors extending indefinitely along both the left and right walls**.

Approved characteristics:

- the corridor's dominant geometry is straight rather than maze-like or branching;
- doors appear on both sides for as far as the player can meaningfully continue;
- the player should never reach a gameplay-visible terminal end;
- physical Superposition Space remains finite and recyclable despite the infinite appearance;
- door spacing may later use a fixed rhythm or controlled procedural variation, but must preserve the visual language of an endless bilateral door corridor;
- recycled corridor segments must preserve logical door identity for the active Superposition Session so repositioning cannot cause a previously observed door to silently become a different candidate;
- decorative / non-selectable doors may be used if useful for density or visual continuity, but a player must not be misled by interaction behavior unless that is intentionally designed later;
- the corridor must remain compatible with multiple players walking together and becoming physically separated along the corridor.

Conceptual view:

```text
        Door     Door     Door     Door
         |        |        |        |
LEFT  [ D ]    [ D ]    [ D ]    [ D ]
       ===================================> apparent infinity
RIGHT [ D ]    [ D ]    [ D ]    [ D ]
         |        |        |        |
        Door     Door     Door     Door
```

This replaces the earlier undecided choice among straight corridor / sparse doors / branching maze / mixed layouts.

---

### 6.2 Doors represent possible worlds

Each perceived door corresponds to a `QuantumCandidate`.

A candidate may resolve to:

- the current universe;
- the original universe;
- a previously discovered universe;
- a newly materialized universe;
- an almost identical universe;
- a same-seed divergent universe;
- a different-seed universe;
- a universe with altered structures or contents;
- other future universe profile types.

Players should not normally be shown the Universe ID before opening the door.

---

### 6.3 Opening the door causes collapse

Opening a door is the measurement event.

The collapse occurs immediately upon opening, not when the player later crosses the threshold.

Therefore:

```text
OPEN DOOR
   -> select / resolve destination
   -> collapse superposition
   -> remove all alternative corridor/door states
   -> restore finite chamber
   -> bind opened passage to destination universe
```

The player cannot open a door, inspect the destination for free, reject it, and then choose another candidate within the same superposition event.

A new selection requires a new activation cycle.

Door measurement is an atomic server state transition. The first valid interaction that changes `SUPERPOSITION -> COLLAPSING` wins. Any simultaneous/subsequent door-open interaction for that Session is rejected/ignored and cannot allocate a second destination.

---

## 7. Universe Generation Model

A/B/C/D are not separate mutually exclusive modes. They are possible outcomes within a broader Universe Profile system.

### 7.1 Possible universe relationships

A newly materialized universe may be:

#### A. Completely new world seed

- different terrain;
- different biomes;
- different structures;
- substantially unrelated physical geography.

#### B. Persistent branch / historical duplicate

- based on a previously existing world state or conceptually related history;
- independently persistent after divergence.

#### C. Same or similar terrain with changed contents

Possible differences include:

- structures;
- loot;
- entity state;
- environment;
- other generated or historical details.

#### D. Arbitrarily many new universes

Every collapse can potentially create a previously unobserved persistent universe.

Previously observed universes remain eligible for future selection.

---

### 7.2 Universe Profiles

Conceptual record:

```text
UniverseRecord
- universeId
- universeUUID
- parentUniverseId
- creationGameTime
- profileType
- rootWorldSeed
- generationProfile
- discovered
- firstObserver
- dimensionKeys[OVERWORLD|NETHER|END]
```

`parentUniverseId` records provenance / branching history only. It does not imply ownership.

Potential profile parameters may later include:

```text
seedRelation
topographySimilarity
structureSimilarity
historyRelation
lootVariation
entityVariation
worldRulesVariation
```

These are design concepts, not finalized implementation fields.

---

### 7.3 Lazy materialization

The system must not attempt to pre-create an infinite number of dimensions.

A possible universe may exist only as a candidate/profile until first selected.

On first observation:

```text
candidate selected
  -> allocate permanent Universe UUID
  -> resolve generation profile
  -> create persistent world identity
  -> materialize necessary chunks
  -> ensure required Chamber Projections
  -> save UniverseRecord permanently
```

Once materialized, the same universe is never regenerated as a different world.

### 7.4 Provisional candidate probability policy

The exact Universe-selection distribution is **not yet a locked gameplay rule**. The architecture must therefore keep probability weights data-driven/configurable rather than embedding them into corridor or dimension code.

For the prototype/M4 milestone, use a deliberately simple provisional distribution only to make the system testable, for example:

```text
Current Universe                    5%
Previously discovered Universe    20%
Brand-new Universe                75%
```

When `Brand-new Universe` is selected, the generation-strategy mix may initially be split approximately as:

```text
Random new seed                   50%
Same-seed fresh history           30%
Mutated/similar profile           20%
Literal BranchSnapshot             0% until M7 exists
```

These numbers are placeholders, not lore. They may be rebalanced later without save migration. A selected door must nevertheless remain deterministic for the lifetime of its active Superposition Session.

---

## 8. Lazy Chamber Projection

The system must also avoid eagerly placing every chamber in every universe.

Use a **Global Chamber Registry + Lazy Projection** model.

When a chunk loads in a universe:

1. determine whether any Global Chamber Anchor bounding boxes intersect the chunk;
2. determine whether the projection is already materialized;
3. if not, allow/generate the normal chunk state first;
4. overwrite only the required Chamber volume;
5. install chamber identity/state;
6. record materialization.

This avoids an unbounded `Universe × Chamber` eager update operation.

---

## 9. Multiplayer Model

### 9.1 Shared superposition session

Multiple players may enter and activate one chamber together.

A shared activation creates an **Observation Cohort** / Superposition Session.

Conceptual example:

```text
Session #1358
sourceUniverse = #0
chamber = Q-781
members = [A, B, C]
state = SUPERPOSITION
```

All participating players should perceive the same corridor/candidate state for that session.

---

### 9.2 Door binding

When one participant opens a door:

```text
destination = Universe #91821
```

that passage is locked to `#91821` until the passage ends / door closes.

Players are not automatically teleported because another participant opened the door.

Only players who actually cross the threshold are transferred.

---

### 9.3 Separation

Example:

```text
A crosses -> #91821
B crosses -> #91821
C remains -> #0
```

C is not automatically transferred.

When the passage ends, C is separated from A and B.

C's next activation is a new superposition event and is not guaranteed to reach `#91821`.

This allows players to become genuinely separated across the multiverse.

---

### 9.4 Reunion

Separation is not necessarily permanent.

Because all observed universes remain globally persistent and can be selected again, C may later collapse into `Universe #91821` and encounter A/B again.

This is intentional emergent multiplayer gameplay.

---

## 10. Player State

Current provisional rule: player-carried state remains global across universe transfers.

Initially this includes:

- inventory;
- armor/equipment;
- XP;
- health;
- hunger;
- potion/status effects;
- identity.

Ender Chest behavior is provisionally global as well but may be reviewed later.

The mod does not currently maintain separate per-universe player inventories.

### 10.1 Death and respawn

Death does **not** return the player to the Origin Universe or Universe #0. Respawn remains within the player's **current Universe**.

Normal Minecraft respawn priority applies inside that Universe:

1. valid respawn anchor/bed semantics appropriate to that dimension family;
2. otherwise that Universe's normal spawn fallback.

Universe transfer therefore changes the world context in which subsequent death/respawn is resolved. The mod must persist enough player Universe identity that reconnect/restart cannot accidentally respawn a dead player in another Universe.

Do **not** assume vanilla fallback respawn logic will automatically choose a custom Universe's Overworld. Add a `RespawnRoutingService` (with narrowly scoped mixins/hooks only where necessary) so a player whose current Universe is U falls back to `U:OVERWORLD` spawn, never the canonical server Overworld, unless an explicit gameplay rule later says otherwise.

---

## 11. Safety and Environmental Consequences

The mod intentionally does not guarantee a safe destination outside the chamber.

Examples of valid outcomes:

- door opens into solid stone;
- lava contacts the door;
- water floods toward the chamber;
- opening is suspended above a large fall;
- chamber is embedded in a generated structure;
- chamber projection overwrites part of an existing build.

These outcomes are features, not generation failures.

Only the chamber interior / invariant volume is guaranteed.

---

## 12. Current Hard Invariants

The following rules are currently considered approved unless explicitly revised later:

1. Observed universes persist permanently.
2. Same Universe UUID always means the same persistent world.
3. Universe identity is global, not chamber-owned.
4. Multiple chambers may lead to the same universe.
5. Transfer preserves absolute XYZ.
6. Universe time is globally synchronized.
7. Quantum Chambers have globally unique identities.
8. Each Chamber has one Origin Universe.
9. Each Chamber projects to the same XYZ/orientation in every Universe's matching `DimensionRole`.
10. Chamber external bounding geometry is `7 × 7 × 7`.
11. Chamber geometry is invariant.
12. Surrounding terrain is not made safe.
13. Chamber projection may overwrite pre-existing blocks within its invariant volume.
14. New chamber placement must avoid globally overlapping Chamber Anchors within the same `DimensionRole`.
15. Only the Origin instance can authoritatively disable/destroy its Chamber Anchor.
16. Disabling Origin disables all projections.
17. Destroying Origin severs the anchor but does not delete universes.
18. Destroyed projections remain as inert **Dead Chambers**.
19. The activation potion/buff and Brewing recipe are part of this mod.
20. Every participant in a shared Superposition Session must have the required buff.
21. Valid activation requires entering and sealing the Chamber after participant validation.
22. Superposition appears as an infinite corridor with many doors.
23. Opening a door is measurement and immediately causes collapse.
24. The corridor disappears immediately when collapse occurs.
25. The selected door remains bound to a single destination for that passage.
26. Only players who physically cross are transferred.
27. Players who fail to follow may become separated into different universes.
28. Previously separated players can potentially meet again.
29. Player inventory/state is initially global across universes.
30. Death respawns the player in the **current Universe**, not automatically in the Origin Universe.
31. New universe creation uses lazy materialization.
32. Chamber projections use lazy materialization.
33. No Nether portal overlay/waiting animation is used.
34. Superposition uses one apparently infinite straight corridor with doors on both left and right walls.
35. The player must not encounter a gameplay-visible corridor endpoint.
36. Candidate probability weights remain configurable/provisional until explicitly balanced.
37. Every Universe contains its own parallel **Overworld, Nether, and End family**; Nether/End are not globally shared between different Universe identities.
38. Entering Nether/End within Universe U keeps the player inside Universe U's dimension family.
39. Returning from that Universe's Nether/End returns to the corresponding dimensions of the same Universe U.
40. A Chamber can be armed by ordinary vanilla redstone circuitry through its Controller/input.
41. Redstone activation is rising-edge triggered; continuous power must not repeatedly create Sessions.
42. Redstone activation does not bypass Chamber structural, lifecycle, sealing, or participant-buff validation.
43. Redstone activation is distinct from Origin-only Anchor disable/destruction authority.
44. A Chamber projects only into the same `DimensionRole` (Overworld/Nether/End) in every Universe.
45. Cross-Universe Chamber traversal preserves both absolute XYZ and `DimensionRole`.
46. All non-spectator players inside the sealed Chamber at activation are cohort participants; one unbuffed included player blocks activation.
47. Successful activation consumes one `QuantumState` dose/effect from every participant atomically.
48. The first implementation uses one static hidden `quantumchamber:superposition` dimension with an identical Chamber replica; it does not require cross-dimensional see-through rendering.

---

## 13. Target Runtime / Adaptation Environment

### 13.1 Locked first target

The first implementation target is explicitly **Minecraft Java Edition 1.21.0**, not 1.21.1.

Baseline development/runtime matrix:

```text
Minecraft          1.21.0
Java               21
Fabric Loader      0.17.2
Fabric API         0.102.0+1.21
Mappings           Yarn 1.21+build.9 (recommended pinned baseline)
Fabric Loom        1.7.4 pinned bootstrap
Gradle Wrapper      8.8 pinned bootstrap
Environment        Client + dedicated/integrated server
Mod ID             quantumchamber
Java package root   dev.quantumchamber
```

`Fabric API 0.102.0+1.21` is a Minecraft 1.21 artifact and must not be treated as a 1.21.1 build. The project metadata and CI matrix should therefore initially reject accidental 1.21.1 launches until an explicit port is created.

### 13.2 Source-set policy

Use Fabric Loom split environment source sets so common/server-authoritative code is not contaminated by client rendering classes:

```text
src/main/java      -> common + server-authoritative gameplay
src/client/java    -> client presentation/render compatibility only
src/main/resources -> shared data/assets
src/client/resources -> client-only resources only if required
```

The mod is expected to be installed on **both client and server** because it introduces custom gameplay state, networking, blocks/items, and world/dimension behavior.

### 13.3 Dependency policy

Hard dependencies for the first target should remain minimal:

```text
Required
- Minecraft 1.21.0
- Fabric Loader
- Fabric API

Optional / compatibility targets
- Sodium
- Iris
- resource packs
- Continuity / CIT-style ecosystem where applicable
- Immersive Portals only through an optional backend, not core gameplay
```

Do not make Cloth Config, Mod Menu, Sodium, Iris, Immersive Portals, or another rendering mod mandatory for core universe travel.

### 13.4 Development workstation / repository location

Development should occur in a **standalone Git repository outside the player's normal `.minecraft` directory**. The personal game installation must never be used as the source tree or primary test world.

Recommended Windows layout:

```text
D:\MinecraftDev\QuantumChamber\        <- Git repository / source of truth
D:\MinecraftDev\QuantumChamber\run\   <- Loom-generated development game data
```

The exact drive is not important; the separation is. A path without cloud-sync, OneDrive live rewriting, or non-ASCII edge cases is preferred for Gradle/Loom reliability.

Repository workflow:

```text
local Git working tree
    <-> remote GitHub repository
         |
         +-- main        protected/stable integration branch
         +-- develop     active integration branch
         `-- feature/*   isolated implementation work
```

The design/spec remains version-controlled under `docs/`. Never keep the only copy of Universe save fixtures or migration test data in the developer's real Minecraft saves.

### 13.5 IDE and toolchain

Recommended primary workstation:

```text
OS                 Windows 10/11 x64
IDE                IntelliJ IDEA (Community is sufficient)
JDK                Temurin/OpenJDK 21 x64
Git                current Git for Windows
Build              Gradle Wrapper committed to repository
Minecraft launcher not required for development launches
```

Fabric's development documentation uses IntelliJ IDEA as its primary documented IDE and Minecraft 1.21-era development requires Java 21. Loom generates development run configurations for client/server workflows.

Use the repository's Gradle Wrapper (`gradlew` / `gradlew.bat`) rather than a machine-global Gradle install so every contributor and CI uses the same build tool version.

### 13.6 Pinned M0 build baseline

For bootstrap, pin versions rather than using `+` selectors:

```properties
minecraft_version=1.21
yarn_mappings=1.21+build.9
loader_version=0.17.2
fabric_version=0.102.0+1.21
loom_version=1.7.4
gradle_version=8.8
java_version=21
mod_id=quantumchamber
maven_group=dev.quantumchamber
archives_base_name=quantumchamber
```

Initial `fabric.mod.json` compatibility must be conservative:

```json
{
  "id": "quantumchamber",
  "environment": "*",
  "depends": {
    "fabricloader": ">=0.17.2",
    "minecraft": "1.21",
    "java": ">=21",
    "fabric-api": ">=0.102.0"
  }
}
```

The development/test matrix remains **exactly Loader 0.17.2 + Fabric API 0.102.0+1.21** even though metadata may permit a newer Loader/API patch. Do not advertise Minecraft 1.21.1 until it is explicitly ported and tested.

Fabric Maven contains the requested Loader `0.17.2`, Fabric API `0.102.0+1.21`, Yarn `1.21+build.9`, and Loom `1.7.4`. The Gradle wrapper version should be chosen and then frozen only after the empty M0 project successfully completes `build`, `runClient`, and `runServer` with this exact dependency set.

The project's `fabric.mod.json` should initially require exactly the intended Minecraft line rather than silently advertising broader compatibility that has not been tested.

### 13.7 Development run profiles

Maintain separate development environments:

```text
run/client-base/
    -> Fabric API only

run/client-render/
    -> Fabric API + exact Sodium/Iris compatibility stack

run/server/
    -> dedicated Fabric server, no client/render mods

run/testworlds/
    -> disposable persistence / migration fixtures
```

The exact Loom run-directory declarations are implementation details, but the data directories must remain separate so Iris/resource-pack tests cannot contaminate baseline results.

Development compatibility mods are **test runtime dependencies**, not core compile-time imports. Core code must compile when Sodium/Iris are absent.

### 13.8 First implementation repository structure

Recommended initial repository:

```text
QuantumChamber/
├─ build.gradle
├─ settings.gradle
├─ gradle.properties
├─ gradlew
├─ gradlew.bat
├─ gradle/wrapper/
├─ LICENSE
├─ README.md
├─ docs/
│  ├─ quantum_superposition_chamber_design.md
│  `─ plans/
├─ src/main/java/
├─ src/main/resources/
├─ src/client/java/
├─ src/client/resources/
└─ src/test/java/
```

Start as a **single Fabric module**. Do not introduce multi-loader or multi-Minecraft-version Gradle subprojects before the 1.21 implementation proves which boundaries actually need abstraction. The architecture already isolates version-sensitive systems (`DynamicDimensionBackend`, `TransferBackend`, client compatibility hooks), so future multi-version work can be added without pre-optimizing the build.

### 13.9 Testing strategy in development

Use three levels:

1. **Pure JVM tests** for deterministic domain logic that does not require Minecraft runtime: Universe candidate hashing, probability selection, state-machine transitions, spatial index arithmetic.
2. **Minecraft/Fabric integration tests or test-mod assertions** for block/state/persistence/world behavior: Chamber validation, redstone rising-edge activation, PersistentState save/reload, projection materialization.
3. **Manual client/dedicated-server compatibility tests** for dynamic dimensions, corridor repositioning, multiplayer, Iris shaders, Sodium and resource packs.

Every milestone must remain launchable on a dedicated server. Client-only classes must never leak into `src/main/java`.

### 13.10 Where implementation work should happen in this collaboration

The authoritative long-term project should live in a normal Git repository, preferably mirrored to GitHub for history, issue tracking, CI and review. The working source should not live only as chat attachments.

For this collaboration, implementation can proceed in milestone-sized repository snapshots/branches. The recommended operational sequence is:

```text
1. bootstrap M0 Git repository
2. verify exact 1.21 / Loader 0.17.2 / API 0.102.0 environment
3. commit a clean baseline
4. implement one milestone on a feature branch
5. build + client + dedicated-server verification
6. merge only after milestone tests pass
```

The first code milestone should be deliberately small: **M0 + redstone-aware Chamber Controller skeleton**, not dynamic Universes yet. This establishes the exact toolchain, server/client split, save format conventions and redstone behavior before the high-risk runtime-dimension work begins.

---

## 14. Architectural Goals

The implementation should satisfy these boundaries:

1. **Server authoritative** — Universe identity, candidate resolution, chamber lifecycle, collapse, player transfer, corridor logical position, and persistence are decided by the server.
2. **Client presentation only** — the client may hide coordinate recycling and present visual continuity, but it must not independently choose a Universe or approve a traversal.
3. **Rendering-mod isolation** — core gameplay must not depend on replacing Minecraft's framebuffer, shader pipeline, or world renderer.
4. **Dynamic-world isolation** — dynamic dimension creation must sit behind a dedicated backend rather than being scattered across gameplay classes.
5. **Persistent IDs over object references** — saved data stores UUIDs / registry keys / positions, not Java object identity.
6. **Lazy materialization** — neither Universes nor Chamber Projections are eagerly created merely because they are theoretically possible.
7. **No eager Universe × Chamber Cartesian expansion**.
8. **No literal infinite corridor generation**.
9. **No mandatory recursive portal rendering**.
10. **Gameplay rules remain independent from the specific portal/render technology used later.**

---

## 15. Proposed Module / Package Architecture

Names below are the default implementation targets. Exact Yarn/Minecraft signatures may be adjusted when compilation requires it, but package boundaries/responsibilities should not be renamed or collapsed without documenting the reason in the milestone implementation notes.

```text
dev.quantumchamber
|
|-- QuantumSuperpositionMod
|-- registry/
|   |-- ModBlocks
|   |-- ModItems
|   |-- ModEffects
|   |-- ModComponents
|   `-- ModNetworking
|
|-- universe/
|   |-- UniverseRecord
|   |-- UniverseRegistry
|   |-- UniverseRegistryState
|   |-- UniverseCandidate
|   |-- UniverseMaterializer
|   |-- UniverseLifecycleManager
|   |-- UniverseGenerationStrategy
|   |-- DynamicDimensionBackend
|   |-- DimensionRole
|   |-- DimensionFamilyRoutingService
|   `-- GlobalQuantumClock
|
|-- chamber/
|   |-- ChamberRecord
|   |-- ChamberRegistry
|   |-- ChamberRegistryState
|   |-- ChamberDetector
|   |-- ChamberLifecycleService
|   |-- ChamberProjectionManager
|   |-- ProjectionIndex
|   |-- ChamberProtectionService
|   |-- ChamberControllerBlockEntity
|   `-- ChamberRedstoneService
|
|-- superposition/
|   |-- SuperpositionSession
|   |-- SuperpositionSessionManager
|   |-- ObservationCohort
|   |-- SessionState
|   |-- QuantumCandidateResolver
|   |-- PassageBinding
|   `-- CollapseService
|
|-- corridor/
|   |-- SuperpositionSpaceManager
|   |-- SessionEntranceAllocator
|   |-- CorridorLogicalCoordinate
|   |-- CorridorPageManager
|   |-- CorridorPage
|   |-- DoorKey
|   |-- DoorSlot
|   `-- CorridorRepositionService
|
|-- transfer/
|   |-- UniverseTransferService
|   |-- DestinationPreparationService
|   |-- RespawnRoutingService
|   `-- TransferBackend
|
|-- persistence/
|   |-- QuantumPersistentStates
|   |-- SaveSchemaVersion
|   `-- RecoveryManager
|
|-- config/
|   `-- QuantumServerConfig
|
|-- network/
|   |-- SessionPackets
|   |-- CollapsePackets
|   `-- ClientSyncPackets
|
|-- compat/
|   |-- CompatibilityManager
|   |-- PortalBackend
|   `-- OptionalModHooks
|
`-- api/
    |-- QuantumUniverseApi
    |-- ChamberApi
    `-- UniverseProfileApi

src/client/java/dev/quantumchamber/client
|
|-- QuantumSuperpositionClient
|-- render/
|   |-- CorridorPresentationController
|   |-- ChamberVisualController
|   `-- OptionalVisualEffects
|
|-- resource/
|   `-- ClientResourceReloadHooks
|
`-- compat/
    |-- IrisCompat
    |-- SodiumCompat
    `-- OptionalPortalClientCompat
```

The core Universe and Chamber packages must not import Iris, Sodium, or Immersive Portals classes.

---

## 16. Universe Architecture

### 16.1 Global Universe Registry

Use one server-global persistent registry as the authoritative mapping:

```text
UniverseUUID -> UniverseRecord
```

Recommended minimum record:

```text
UniverseRecord
- UUID universeUuid
- UUID parentUniverseUuid?          // provenance only
- long createdAtGlobalTime
- UniverseProfile profile
- long rootWorldSeed
- MaterializationState state
- boolean discovered
- Map<DimensionRole, RegistryKey<World>> dimensionKeys
- creation/source metadata
- save schema version
```

Universe IDs shown to players, if added later, may use a shorter human-readable number, but all internal identity should use UUID/Identifier to prevent collision.

### 16.2 Dynamic dimension backend

The mod needs runtime creation of persistent world identities. This is one of the highest-risk technical areas and must be isolated behind:

```java
interface DynamicDimensionBackend {
    RegistryKey<World> keyFor(UniverseRecord record, DimensionRole role);
    ServerWorld createOrLoad(UniverseRecord record, DimensionRole role);
    Optional<ServerWorld> getLoaded(UniverseRecord record, DimensionRole role);
    void ensureRegistered(UniverseRecord record, DimensionRole role);
    void unloadIfIdle(UniverseRecord record, DimensionRole role);
}
```

The gameplay code must never assume whether the implementation is:

- a small adapted/backported dynamic-dimension implementation;
- a compatible DimLib implementation;
- another future Fabric API / loader facility.

Current DimLib 1.21 branch material targets found upstream are based on Minecraft 1.21.1, so they cannot simply be declared as a binary dependency for this exact 1.21.0 target. Its `DimensionAPI` design is still a valuable implementation reference.

### 16.3 Persistent but not permanently loaded

"Permanent Universe" means permanent **save identity and data**, not permanent RAM residency.

Allowed lifecycle:

```text
MATERIALIZED + PLAYERS PRESENT
        -> loaded ServerWorld

no players / no required tickets
        -> eligible for idle unload

later selected again
        -> re-register/load same dimension ID and same save data
```

Unloading must never delete region/entity/POI data or alter the Universe UUID.

### 16.4 Universe dimension family: Overworld + Nether + End

A `UniverseRecord` represents a **global Universe identity containing a full vanilla dimension family**, not merely one Overworld-like `ServerWorld`.

Conceptually:

```text
Universe U
├─ U:overworld
├─ U:the_nether
└─ U:the_end
```

Therefore:

- Universe #17 Nether is distinct from Universe #18 Nether;
- Universe #17 End is distinct from Universe #18 End;
- vanilla-style Nether portal coordinate scaling may occur **inside one Universe family** between `U:overworld` and `U:the_nether`;
- this does not alter the Quantum Chamber rule that **cross-Universe transfer itself preserves absolute XYZ**;
- End portals/gateways remain inside the same Universe family;
- beds/respawn anchors and dimension-specific respawn mechanics resolve against the current Universe family's corresponding dimensions;
- persistent data, entities, structures, dragon state, End gateways, Nether builds, etc. belong to that Universe rather than being shared globally.

The backend must therefore resolve dimensions using a composite identity such as:

```text
UniverseDimensionKey = (UniverseUUID, DimensionRole)
DimensionRole = OVERWORLD | NETHER | END
```

Use deterministic registry paths, for example:

```text
quantumchamber:universe/<uuid>/overworld
quantumchamber:universe/<uuid>/the_nether
quantumchamber:universe/<uuid>/the_end
```

All three roles of one Universe share that Universe's `rootWorldSeed`; vanilla role-specific generators/settings then produce Overworld/Nether/End terrain from the same root seed unless a future `UniverseProfile` explicitly overrides generation settings.

**Critical routing requirement:** vanilla portal logic does not automatically know that a custom parallel Nether/End belongs to a particular custom Overworld. Implement a dedicated `DimensionFamilyRoutingService` and narrowly scoped mixins/hooks so:

- Nether portals inside Universe U route only between `U:OVERWORLD` and `U:NETHER`, with normal 8:1 scaling inside that family;
- End portals/gateways inside Universe U route only within `U:OVERWORLD` / `U:END` as appropriate;
- no parallel-family portal may fall back to the canonical server `minecraft:overworld`, `minecraft:the_nether`, or `minecraft:the_end` merely because vanilla code assumes those singleton keys.

M3 may bring up one alternate Overworld first, but full portal-family routing is mandatory before M6/full-family gameplay is considered complete.

This requirement applies from the architecture level even if M3 initially materializes only the Overworld role for bring-up testing. Before a public gameplay release, the full three-role family must be implemented and persistence-tested.

### 16.5 Universe generation strategy interface

A/B/C/D behavior should be expressed through strategy objects rather than hardcoded condition trees. The following signatures are architectural targets; the implementing agent may adapt exact Yarn/Minecraft types after M0 compilation while preserving the boundary:

```java
interface UniverseGenerationStrategy {
    UniverseProfile createProfile(CandidateSeed candidate, UniverseRecord source);
    ChunkGeneratorDescriptor createGenerator(UniverseProfile profile);
}
```

Planned strategy families:

```text
RandomSeedUniverseStrategy
    -> unrelated new seed/world

SameSeedFreshUniverseStrategy
    -> same/similar terrain generation, fresh world history

MutatedProfileUniverseStrategy
    -> controlled changes to generation/structures/contents

BranchSnapshotUniverseStrategy
    -> literal persistent branch of an existing world state
```

The first three can be implemented without copying an entire existing save. `BranchSnapshotUniverseStrategy` requires a separate storage/consistency design and should be treated as a later high-risk milestone, while keeping the API reserved from day one.

### 16.6 Stable potential universes without eager creation

Infinite doors do not mean infinite dimensions.

For an unobserved door, derive a lightweight candidate deterministically from:

```text
SuperpositionSession UUID
+ logical door index
+ left/right side
+ server secret/world entropy
```

Conceptually:

```text
CandidateKey = H(sessionUuid, logicalDoorIndex, side, entropy)
```

The candidate can carry a stable profile descriptor without creating chunks or a ServerWorld.

Only measurement/opening causes:

```text
candidate
 -> resolve existing Universe OR allocate new Universe UUID
 -> persist UniverseRecord
 -> materialize dimension
```

This guarantees that a door a player has already observed does not silently change identity merely because the physical corridor segment was recycled.

---

## 17. Global Chamber Architecture

### 17.1 Chamber Registry

Use one global persistent registry:

```text
ChamberUUID -> ChamberRecord
```

Suggested record:

```text
ChamberRecord
- UUID chamberUuid
- UUID originUniverseUuid
- DimensionRole originDimensionRole
- BlockPos anchorPos
- Direction facing
- ChamberGeometry geometryId
- enabled
- destroyed
- creationGlobalTime
- owner/creator metadata if later required
```

### 17.2 Spatial Projection Index

Do not scan every chamber whenever a chunk loads.

Because all Chamber Projections share the same absolute X/Z across Universes, maintain a server-global spatial index:

```text
(DimensionRole, ChunkPos) -> set<ChamberUUID>
```

A projection materializer can then query only chamber bounding volumes intersecting that chunk.

### 17.3 Projection order

When a destination Universe/chunk is first required:

```text
1. create/load destination Universe
2. load/generate destination chamber chunks normally
3. query ProjectionIndex
4. overwrite Chamber invariant volume only
5. install projection controller/identity state
6. verify door facing and interior
7. mark projection materialized
8. only then allow player transfer
```

This ordering preserves the intentionally unsafe external environment while guaranteeing the chamber interior exists.

When a **new Chamber Anchor is created**, projections whose target chunk is already loaded in any currently loaded Universe of the same `DimensionRole` must be materialized immediately. Only unloaded target chunks wait for future lazy chunk-load materialization. This closes the otherwise incorrect case where a projection would never appear merely because its target chunk was already loaded before Anchor creation.

### 17.4 Chamber protection

Projection protection cannot rely solely on vanilla bedrock hardness because creative mode, commands, pistons, custom explosives, or modded interactions can bypass normal mining rules.

`ChamberProtectionService` should own all invariant checks for:

- block break;
- block place replacing protected volume;
- piston movement;
- explosion modification;
- fluid replacement of invariant blocks;
- structure/worldgen overwrite after materialization;
- projection door/controller destruction.

Only the Origin lifecycle service can intentionally disable/destroy the anchor.

Implement protection around a single mutation gate/authorization context so the mod's own projection materializer and Origin destruction code can edit invariant blocks without recursively blocking themselves. Ordinary player, piston, explosion, fluid and generic world mutations intersecting a protected projection are rejected server-side. Operator/admin commands should be treated as explicit administrative override only if the implementation can identify them safely; otherwise the next integrity validation must restore the invariant projection rather than allowing silent permanent corruption.

### 17.5 Resource-pack-friendly physical shell

Where the design genuinely uses vanilla `minecraft:bedrock`, allow it to remain vanilla bedrock rather than duplicating its texture. This means ordinary resource packs that replace bedrock automatically affect the chamber shell.

Custom controller/door/corridor blocks should use normal model/blockstate JSON resources so they can also be replaced by resource packs.

---


### 17.6 Chamber Controller and redstone I/O

The Controller is the single server-authoritative redstone integration point for one Chamber Anchor/projection. It should not scan the entire 7 × 7 × 7 structure every tick.

Responsibilities:

```text
ChamberControllerBlock
    -> exposes vanilla redstone/comparator block behavior

ChamberControllerBlockEntity
    -> stores local projection/controller linkage only
    -> does not own global Universe/Chamber truth

ChamberRedstoneService
    -> detects rising edge
    -> requests Chamber validation
    -> requests SuperpositionSessionManager.startSession(...)

ChamberStatusSignal
    -> maps server state to comparator output
```

Edge state must survive chunk unload/reload safely enough that loading a permanently powered Controller cannot be mistaken for a fresh pulse. Persist or reconstruct a small `wasPowered` latch in the controller/projection state.

Recommended event flow:

```text
neighbor update / scheduled validation
        -> read vanilla received power
        -> currentPowered && !wasPowered ? RISING_EDGE : no trigger
        -> update wasPowered
        -> if RISING_EDGE: validate Chamber
        -> if valid: create Session
        -> synchronize comparator output when state changes
```

Do not poll every global Chamber every server tick. Redstone/block events should drive activation, with bounded validation only for the affected Chamber.

## 18. Superposition Session State Machine

Recommended authoritative state machine:

```text
IDLE
  |
  | chamber sealed + activation requirements satisfied
  v
ARMING
  |
  | validation succeeds
  v
SUPERPOSITION
  |
  | a logical door is opened
  v
COLLAPSING
  |
  | destination resolved/materialized/prepared
  v
PASSAGE_READY
  |
  | players may cross while door remains open
  +--------------------------+
  |                          |
  | door closes / expires    | player crosses
  v                          v
SESSION_END             TRANSFERRED member(s)
```

The **visual infinite corridor disappears at entry into `COLLAPSING`**, satisfying the gameplay rule that opening the door immediately restores a single chamber.

Destination preparation may continue server-side for a short technical interval after collapse if a brand-new Universe must be created/generated. No cinematic/Nether transition animation is introduced.

### 18.1 Observation Cohort

`SuperpositionSession` should store:

```text
sessionUuid
sourceUniverseUuid
chamberUuid
participants
logicalCorridorOffset
candidateSalt/entropy
openedDoorKey?
destinationUniverseUuid?
state
passageOpen
```

Players who were part of the same session share the same logical door candidates.

### 18.2 Collapse restores cohort to the finite chamber

Because the corridor ceases to exist immediately after measurement, corridor occupants must be remapped back into the source Chamber's finite interior during collapse.

Restoration is server-authoritative and deterministic. First preserve each participant's Chamber-local `x/z` ordering where possible. If multiple restored bounding boxes would overlap or intersect the closed shell, place participants into deterministic fallback slots on the 5 × 5 interior floor, ordered by player UUID. Never place a returning participant outside the Chamber merely to resolve a collision.

---

## 19. Infinite Corridor Implementation

### 19.1 Dedicated static Superposition Dimension

Recommended first implementation:

```text
quantum:<superposition_dimension>
```

This is a **single static void/pocket dimension**, not one dimension per session.

Each active session receives an isolated corridor cell/region inside this dimension.

Benefits:

- real Minecraft collision;
- real entities/items/projectiles;
- normal server authority;
- simple cleanup;
- no collision with generated Universe terrain;
- only one special dimension type to define statically;
- no need to create infinitely many corridor dimensions.

### 19.2 Finite geometry, infinite logical coordinate

Use a **paged logical corridor** rather than one physical corridor that must contain the entire cohort span.

Maintain:

```text
Logical corridor coordinate
    -> effectively unbounded signed longitudinal coordinate

Logical page index
    -> floorDiv(logicalZ, PAGE_LENGTH)

Physical page slot
    -> one finite reusable corridor page in Superposition Dimension
```

Recommended M2 constants (tunable without save migration):

```text
Corridor interior width:   5 blocks
Corridor interior height:  5 blocks
PAGE_LENGTH:               96 blocks
Door station spacing:       8 blocks
Logical doors/station:       2 (LEFT + RIGHT)
Physical page-slot spacing: >= 160 blocks
```

These constants affect presentation/performance only. `DoorKey` identity derives from logical coordinates, never physical page coordinates.

### 19.3 Corridor page allocation and seamless page crossing

`CorridorPageManager` allocates physical pages only for logical pages currently occupied or immediately adjacent to occupied pages. Empty distant logical gaps require no physical geometry.

When a player or session-tagged entity crosses a logical page boundary:

1. ensure the destination logical page has a physical page slot;
2. move the entity within the **same static Superposition Dimension** to the corresponding local position in that page slot;
3. preserve yaw/pitch and velocity where safe;
4. keep the repeated page geometry identical across the transfer seam;
5. retain the entity's logical corridor coordinate independently from its physical position.

Players close together occupy the same/adjacent physical pages and remain mutually visible through normal Minecraft range rules. Players who fall extremely far apart can occupy different physical page slots without forcing the server to build all corridor blocks between them. They still belong to the same `SuperpositionSession` and share the same logical door/candidate mapping.

Only pages containing participants, relevant dropped items/projectiles, or a one-page neighbor safety margin remain allocated. Deallocate/recycle empty pages after a bounded grace period. Never recycle a physical page while an entity is crossing its seam or interacting with a door inside it.

This page model replaces the earlier whole-session sliding-window approach and removes the finite-window failure when multiplayer participants intentionally spread very far apart.

### 19.4 Logical door addressing

A door is addressed by:

```text
DoorKey
- sessionUuid
- logicalDoorIndex
- side = LEFT | RIGHT
```

Physical door slots may be reused, but `DoorKey` is not.

Candidate mapping:

```text
DoorKey -> QuantumCandidate
```

must remain stable for the lifetime of the Superposition Session.

### 19.5 No recursive renderer required

The corridor's infinity effect should result from:

- repeated real geometry;
- hidden logical-page allocation and same-dimension page-boundary repositioning;
- fog/lighting/occlusion choices if desired;

rather than rendering portals inside portals.

This is a deliberate shader-compatibility decision.

---

## 20. Transfer / Destination Preparation

### 20.1 Transfer pipeline

Opening the selected door performs measurement immediately, then prepares traversal:

```text
OPEN
 -> freeze selected DoorKey
 -> resolve QuantumCandidate
 -> allocate/find UniverseRecord
 -> create/load destination ServerWorld in the source Chamber's same DimensionRole
 -> synchronize destination global time
 -> preload destination Chamber chunks
 -> materialize/verify Chamber Projection
 -> mark PassageBinding READY
```

Crossing the chamber threshold then performs:

```text
same DimensionRole
same X/Y/Z
same yaw/pitch
provisionally same velocity
same player inventory/state
-> destination Universe
```

### 20.2 No safe relocation

`DestinationPreparationService` is explicitly forbidden from changing the selected XYZ merely because the door exterior is dangerous.

Its job is to make the **Chamber Projection** valid, not to make the outside safe.

### 20.3 Baseline transfer backend

The required baseline is a shader-safe direct cross-world transfer backend:

```java
interface TransferBackend {
    void prepare(PassageBinding binding);
    void transfer(ServerPlayerEntity player, PassageBinding binding);
}
```

The baseline backend must not require see-through cross-dimensional rendering.

This guarantees the gameplay works even without Immersive Portals.

The baseline transfer is a direct server-authoritative world transfer after destination chunks are ready. The client may perform its normal dimension/world renderer reinitialization (including an Iris pipeline rebuild) and may show a brief hitch on first entry to a dynamic world. The compatibility requirement is **no crash, shader corruption, forced shader disable, Nether portal overlay, nausea, or mod-authored cinematic/loading GUI**. Zero-frame cross-world continuity is an optional future `PortalBackend` enhancement, not a baseline correctness requirement.

### 20.4 New-Universe traversal readiness threshold

A brand-new Universe may take longer to create than an already materialized one. The user-facing rule remains: **opening the door collapses Superposition immediately**. Generation latency is handled after collapse through a short, server-authoritative passage stabilization phase.

Passage state:

```text
COLLAPSING
   -> DESTINATION_PREPARING
   -> STABILIZING
   -> PASSAGE_READY
```

During `STABILIZING`:

- the player is already back inside the normal finite 7 × 7 × 7 Chamber;
- the selected physical door may be open, but an invisible server-authoritative threshold collision prevents crossing;
- no alternate doors/corridor return; the measurement result is already irreversible;
- use ordinary resource-pack/shader-safe cues such as door-state sound, particles, subtle vanilla-compatible fog/energy surface, or controller indication;
- do not show a Nether portal overlay, loading GUI, or cinematic transition;
- do not teleport the player to a temporary safety room merely because generation is slow.

#### Minimum readiness gate

`PASSAGE_READY` is allowed only after all of the following are true:

1. destination `UniverseRecord` is durably persisted;
2. destination Universe dimension family/target `ServerWorld` is registered and loaded;
3. destination GlobalTime has been synchronized;
4. every chunk intersecting the destination Chamber's **7 × 7 × 7 invariant bounding box expanded by one block** is at `FULL`/server-playable status;
5. the destination Chamber Projection has been materialized and verified;
6. the door-side immediate exterior collision volume can be queried authoritatively (solid block, water, lava, air, void-edge conditions are all valid results);
7. the client has received the minimum destination world/chunk synchronization required by the transfer backend to avoid crossing into an unresolved world.

The expanded-by-one-block rule deliberately verifies only the Chamber and immediate threshold, not destination safety. A wall, lava source, ocean, or open air directly outside remains valid.

#### Background warm-up

After the minimum readiness gate is satisfied, traversal is enabled immediately. The server may continue generating/loading surrounding chunks asynchronously using ordinary chunk tickets. Recommended default warm-up target for a **new** Universe is a `3 × 3` chunk area centered on the Chamber's containing chunk, with optional configurable expansion to `5 × 5`. This background warm-up must not delay `PASSAGE_READY` once the minimum gate is met.

#### Tick-budget rule

Destination generation must not synchronously freeze the server tick waiting for terrain generation. Preparation should be advanced over normal server ticks / chunk futures. A configurable per-tick preparation budget may be added after profiling; correctness must not depend on a specific millisecond value.

#### Failure / timeout rule

There is **no lore-level timeout that rerolls the selected Universe**. If preparation fails because of an actual backend/generation error:

```text
selected Universe remains selected
passage becomes ERROR / SEALED
players remain safely in source Chamber
server logs/persists diagnostic state
no alternative Universe is silently substituted
```

An administrator/recovery path may retry preparation. This preserves the invariant that measurement cannot be undone merely because generation was slow or failed.

If the selected physical bulkhead is manually closed before `PASSAGE_READY` or before anyone crosses, the `PassageBinding` ends without rerolling; any newly allocated Universe remains permanently recorded/materialized. Reopening that door later does not resurrect the old passage. A new destination requires a fresh potion/activation cycle.

### 20.5 Existing-Universe fast path

For an already materialized Universe, the same readiness gate applies, but in the common case only world load + Chamber chunk ticket + projection verification are needed. No new Universe profile or world identity is created.

---

## 21. Rendering, Iris/Shaders, Sodium, and Resource Packs

### 21.1 Compatibility contract

The core rendering strategy is intentionally conservative:

**Do not require for correctness:**

- custom framebuffer replacement;
- stencil-buffer portal composition;
- recursive world rendering;
- custom global post-processing shader;
- mixins into Iris or Sodium internals;
- hardcoded OpenGL state manipulation;
- persistent cached GL texture IDs across resource reloads.

The base experience should render as ordinary Minecraft world geometry.

This is the main mechanism by which the mod targets broad shader/resource-pack compatibility.

### 21.2 Iris / shader packs

Target compatibility should be defined as:

> The mod must remain functional with Iris enabled and with shader packs enabled because core gameplay does not replace Iris's world shader pipeline.

For the Minecraft 1.21 baseline, **Iris 1.7.3+1.21** is a useful explicit compatibility test target; that release supports Minecraft 1.21–1.21.1 and is in the historical Sodium 0.5.11 generation.

Individual third-party shader packs may still contain their own rendering defects. We should test representative packs rather than promise compatibility with every GLSL pack ever published.

### 21.3 Sodium

Use ordinary block/chunk/entity rendering wherever possible.

The mod should not require a Sodium mixin or internal renderer hook merely to display the chamber/corridor.

If Fabric Rendering API features become necessary later, isolate them behind client rendering code and test carefully against the exact Sodium/Indium combination because the 1.21-era ecosystem had version-sensitive Fabric Rendering API/Indium interactions.

### 21.4 Optional visual effects

Quantum distortion, chromatic effects, unusual fog, etc. must be **cosmetic**.

Preferred order:

1. vanilla particles/fog/light/sounds;
2. vanilla-compatible render layers;
3. optional client effect;
4. effect automatically disabled or degraded safely when Iris/shader environment is incompatible.

The player must still be able to identify Superposition and Collapse with all custom effects disabled.

### 21.5 Resource pack support

Use normal Minecraft asset conventions:

```text
assets/<modid>/blockstates/
assets/<modid>/models/block/
assets/<modid>/models/item/
assets/<modid>/textures/block/
assets/<modid>/textures/item/
assets/<modid>/sounds.json
assets/<modid>/lang/
```

Rules:

- do not bake texture pixel assumptions into gameplay;
- do not depend on a specific texture resolution;
- allow 16x/32x/64x/etc. resource packs;
- use vanilla bedrock resource location when actual vanilla bedrock is rendered;
- custom doors/controllers/corridor pieces must expose stable resource locations;
- resource reload (`F3+T`) must not invalidate gameplay state;
- any client caches containing model/sprite references must be rebuilt on resource reload;
- sounds used for activation/collapse should be replaceable through standard sound resources.

Standard vanilla/Fabric resource packs are the compatibility guarantee. OptiFine-specific pack extensions (CTM/CIT/custom emissives) require the corresponding Fabric ecosystem mod and should be tested separately rather than treated as vanilla resource-pack behavior.

### 21.6 Immersive Portals backend

Immersive Portals remains a useful **reference and optional enhancement**, especially for:

- true see-through destination doors;
- multi-dimension client synchronization;
- seamless portal traversal;
- destination chunk synchronization.

However it should not be a mandatory backend for the first Minecraft 1.21.0 release because:

- it deeply modifies multi-world client rendering;
- its documentation describes Sodium/Iris compatibility as version-sensitive;
- the original Fabric repository is no longer actively maintained;
- currently reviewed 1.21-line artifacts/references are centered on Minecraft 1.21.1, while this project is deliberately targeting 1.21.0;
- shader compatibility is a first-class requirement for this mod.

Architectural rule:

```java
interface PortalBackend {
    boolean isAvailable();
    void createVisualPassage(...);
    void removeVisualPassage(...);
}
```

The default implementation may be a no-op/direct-transfer visual backend. A future Immersive Portals implementation can be loaded only when a known-compatible mod stack is detected.

---

## 22. Networking Architecture

### 22.1 Server-authoritative events

The following must originate from / be validated by the server:

- chamber activation;
- membership in Observation Cohort;
- logical door interaction;
- selected `DoorKey`;
- candidate resolution;
- collapse;
- destination Universe UUID;
- projection readiness;
- actual universe transfer;
- chamber disable/destruction.

### 22.2 Client sync

Clients need only enough information to present the state:

```text
SessionStarted
SessionEnded
LogicalCorridorOffsetChanged
CollapseOccurred
PassageReady
ChamberStateChanged
```

Do not send an unobserved Universe's complete seed/profile merely so the client can decorate a door. This avoids leaking destination information before measurement and keeps the server authoritative.

### 22.3 Door interaction

Prefer normal Minecraft block interaction packets validated server-side rather than inventing a separate client-trusted "select universe" packet.

For custom synchronization payloads on Fabric API 0.102.0+1.21, use the object payload API (`CustomPayload`, `PayloadTypeRegistry.playS2C()/playC2S()`, `ServerPlayNetworking` / `ClientPlayNetworking`) rather than legacy raw channel handling. Register payload codecs on both sides before receivers.

---

## 23. Persistence and Crash Recovery

### 23.1 PersistentState responsibilities

At minimum persist:

```text
UniverseRegistryState
ChamberRegistryState
SaveSchemaVersion
```

Projection materialization state may be reconstructed from chunks where possible, but important lifecycle facts should remain in the Chamber Registry.

Universe materialization must use explicit durable states such as:

```text
ALLOCATED -> MATERIALIZING -> READY
                         `-> ERROR
```

Persist the Universe UUID/profile/key allocation **before** terrain generation begins. On crash/restart, resume or mark the same allocated Universe as recoverable; never allocate a replacement identity for the same completed measurement.

All candidate resolution, Universe allocation, and first-write registry mutations execute on the logical server thread (or behind a single server-thread serialization gate). Two simultaneous door interactions must not allocate conflicting IDs or resolve two winners for one Session.

### 23.2 Active sessions

Superposition Sessions are conceptually temporary, but server crashes/restarts must not strand a player in an invalid corridor cell.

Recommended recovery rule:

- persist enough session recovery metadata to know source Universe + Chamber;
- on restart, invalidate incomplete Superposition Sessions;
- safely restore affected players to the source Chamber before normal gameplay continues;
- never resolve a new Universe merely because the server restarted mid-session.

Exact recovery placement is an implementation detail but must be deterministic.

### 23.3 Schema migrations

Every persistent record should carry a schema/version path from the first public build.

Do not rely on Java serialization.

Use Minecraft codecs/NBT-compatible data structures so migration code can be written explicitly.

---

## 24. Global Time Architecture

Maintain one authoritative global quantum clock, preferably anchored to the server/primary world persistent state.

Loaded Universes periodically synchronize their `timeOfDay` to this value.

Rules:

- Universe load -> immediately sync time;
- Universe remains loaded -> prevent significant drift;
- Universe unload -> no need to tick solely for time;
- Universe reload -> jump to current GlobalTime;
- weather remains Universe-local unless later changed.

Do not simulate every unloaded Universe tick-by-tick merely to preserve time equality.

---

## 25. Compatibility Test Matrix

Minimum CI/manual compatibility targets for the first playable build:

### 25.1 Base runtime

```text
Minecraft 1.21.0
Fabric Loader 0.17.2
Fabric API 0.102.0+1.21
Java 21
```

Test both:

- integrated single-player server;
- dedicated Fabric server with multiple clients.

### 25.2 Rendering matrix

Test at least:

```text
A. Vanilla renderer, no shader
B. Sodium 0.5.11-class 1.21 stack
C. Iris 1.7.3+1.21 + matching Sodium generation, shaders OFF
D. Iris + representative shader pack, shaders ON
```

Do not add Immersive Portals to the baseline pass/fail matrix until a specific known-good 1.21.0 combination is proven.

### 25.3 Resource-pack matrix

Test:

- vanilla assets;
- normal resource pack replacing bedrock;
- pack replacing custom Chamber/door textures/models;
- higher-resolution texture pack;
- resource reload (`F3+T`) while idle;
- resource reload while Chamber exists;
- resource reload after entering/leaving Superposition.

### 25.4 Gameplay/persistence matrix

Test:

- create first Chamber;
- create Chamber in a parallel Universe;
- verify projections at identical XYZ;
- projection into mountain/water/lava/open air;
- server restart after Universe creation;
- return to previously visited Universe and verify modifications persist;
- multiple Chambers reaching the same global Universe;
- origin disable -> projection disable;
- origin destruction -> projections severed without Universe deletion;
- multiplayer cohort traversal;
- one player follows, one remains behind;
- separated players later reunite in same Universe;
- disconnect during Superposition;
- server restart during Superposition;
- death around Chamber/parallel Universe;
- door spam / simultaneous door interaction race.

### 25.5 Corridor stress matrix

Test:

- repeated forward walking for long periods;
- multiple players separated by large corridor distance;
- dropped items at recycle boundary;
- arrows/projectiles crossing recycle boundary;
- mobs if later allowed inside;
- door candidate stability across many recycling operations;
- no visible terminal corridor end;
- no duplicate physical door causing duplicate candidate resolution.

---

## 26. Configuration Contract

Use a dependency-free server config for the first release, e.g. `config/quantumchamber.json`, loaded/validated server-side. Do not require Cloth Config/Mod Menu for correctness.

Minimum schema:

```json
{
  "schemaVersion": 1,
  "candidateWeights": {
    "currentUniverse": 5,
    "discoveredUniverse": 20,
    "newUniverse": 75
  },
  "newUniverseStrategyWeights": {
    "randomSeed": 50,
    "sameSeedFresh": 30,
    "mutatedProfile": 20,
    "branchSnapshot": 0
  },
  "destinationWarmupRadiusChunks": 1,
  "quantumPotionDurationTicks": 3600,
  "corridor": {
    "pageLength": 96,
    "doorSpacing": 8,
    "pageSlotSpacing": 160
  }
}
```

`destinationWarmupRadiusChunks=1` means a 3 × 3 background warm-up. Invalid/negative weights or dimensions fall back to shipped defaults with a clear server log message. Balance/config changes must not alter the identity of already materialized Universes.

Do not automatically delete old Universes to satisfy disk pressure. Persistence is a gameplay invariant. Provide diagnostics/admin visibility instead of garbage collection.

---

## 27. Performance Rules

1. Never tick all persistent Universes merely because they exist.
2. Never keep all Universes loaded.
3. Never iterate all Chambers on every chunk tick.
4. Use `ProjectionIndex` for spatial lookup.
5. Generate only the corridor cell/segments needed by active sessions.
6. Candidate generation must remain lightweight and must not generate chunks.
7. Destination chunk loading begins only after measurement, unless a future design explicitly allows pre-observation materialization.
8. Cache only immutable/lightweight candidate descriptors; do not hold entire ServerWorld graphs in session objects.
9. Remove temporary corridor entities/session allocations promptly after collapse/end.
10. Profile dynamic dimension creation, projection materialization, and destination chunk generation separately; they are different latency sources.

---

## 28. Recommended Implementation Milestones

### M0 — project bootstrap / compatibility skeleton

- standalone Git repository outside `.minecraft`;
- Minecraft 1.21.0 Fabric project;
- Java 21;
- Loader 0.17.2;
- Fabric API 0.102.0+1.21;
- Yarn 1.21+build.9;
- pinned Loom baseline and Gradle Wrapper;
- split client/common sources;
- empty compatibility detector;
- isolated baseline/render/dedicated-server run directories;
- `gradlew build` baseline;
- dedicated-server launch test;
- Iris/Sodium development launch profiles.

### M1 — Chamber without multiverse

- locked 7 × 7 × 7 Chamber multiblock/controller;
- vanilla redstone rising-edge activation;
- comparator status output;
- activation validation with closed door + all-occupant QuantumState buff;
- Brewing/effect registry skeleton;
- Origin/Projection data model;
- Chamber UUID;
- global collision validation;
- persistent Chamber Registry;
- protection rules.

### M2 — Superposition corridor prototype

- static Superposition Dimension;
- one session entrance replica cell;
- bilateral doors;
- logical door indexing;
- paged corridor allocation/recycling;
- same-dimension page-boundary repositioning;
- multiplayer cohort synchronization;
- no Universe transfer yet.

### M3 — one persistent alternate Universe + dynamic-dimension feasibility

- mandatory 1.21.0 runtime dynamic-dimension feasibility spike and implementation note;
- DynamicDimensionBackend;
- Universe Registry;
- one alternate Overworld role first;
- same-role/same-coordinate cross-Universe transfer;
- Universe dimension-family identity reserved (Overworld/Nether/End roles);
- global time sync;
- destination projection materialization;
- save/reload/crash-during-materialization test.

### M4 — infinite candidate system

- deterministic `DoorKey -> QuantumCandidate`;
- existing-vs-new Universe selection;
- lazy materialization;
- probability/profile configuration.

### M5 — multiplayer collapse / separation

- shared measurement;
- passage binding;
- follower/left-behind behavior;
- reunion tests;
- disconnect/crash recovery.

### M6 — Universe generation diversity + full dimension families

- random seed;
- same-seed fresh world;
- generation-profile mutation;
- profile registry/API;
- Nether/End generation and persistence parity per Universe family;
- `DimensionFamilyRoutingService` for Nether/End portals and gateways;
- `RespawnRoutingService` fallback behavior across custom families;
- same-`DimensionRole` Chamber projection/transfer tests.

### M7 — literal branch/snapshot Universe — separate design gate

M7 is intentionally **not implementation-ready from the current document alone** because literal cloning of an already-modified live world requires a storage-consistency decision. Before writing production M7 code:

- stop and write `docs/superpowers/specs/m7-branch-snapshot-design.md` (or equivalent project design note);
- choose and justify snapshot consistency boundary;
- compare full-copy, stopped-world snapshot, region-level copy, and copy-on-write approaches;
- define region/POI/entity/player-data handling for all three DimensionRoles;
- define disk-space/error/crash semantics;
- obtain human approval for that sub-design;
- then implement and run large-world storage/performance tests.

This gate does not block M0–M6 or M8 compatibility work.

### M8 — compatibility hardening

- Iris/shader matrix;
- Sodium matrix;
- resource-pack reload matrix;
- optional PortalBackend spike;
- profiling and leak checks.

---

## 29. Remaining Open Design Questions

The project is sufficiently specified to begin implementation planning. The following items remain deliberately tunable and do **not** change the core architecture.

### 29.1 Chamber construction / polish (non-blocking)

MVP geometry is locked in Section 3.1. Still open only for later gameplay/polish:

- survival acquisition/construction ritual;
- ownership UI;
- Controller-facing redstone aesthetics.

The code agent must implement the M1 geometry as specified and must not block core development on a survival recipe.

### 29.2 Brewing balance / polish (non-blocking)

M1 IDs, recipe, duration and consumption semantics are locked in Section 5.1 as development defaults. Later balance may change:

- ingredient rarity/renewability;
- duration;
- potion presentation/name localization;
- splash/lingering balance.

### 29.3 Candidate probability distribution

The placeholder weights in Section 7.4 exist only for prototype testing. Final balance for current/known/new and generation-profile types remains open.

### 29.4 Historical branch semantics

A literal copy of a modified existing world is technically very different from "same seed, fresh generation". The exact snapshot point and copy-on-write/storage model remains intentionally unresolved until the basic dynamic Universe system works.

### 29.5 Dead Chamber presentation

Locked behavior: projections remain as inert Dead Chambers after Origin destruction. Still to decide their presentation:

- unchanged bedrock shell;
- visibly cracked/dead controller;
- permanently open/closed door;
- unique particles/sound or total silence.

### 29.6 Threshold presentation

The readiness algorithm is defined in Section 20.4. Only cosmetics remain open: visual/sound language for `STABILIZING`, `READY`, and `ERROR` that remains safe with Iris/shader/resource packs.

---

## 30. Implementation Research / References

### 30.1 PortalChest

Repository:

https://github.com/Snapperrr/PortalChest

PortalChest is an implementation reference, not a dependency or architectural authority.

Its current `main` project is Minecraft **1.21.1**, with its own newer loader/API configuration, so code must be adapted rather than copied mechanically into this project's Minecraft 1.21.0 environment.

Relevant areas to study:

- `ChestPocketManager`
  - dedicated hallway/pocket dimension;
  - bilateral corridor doors;
  - door-open detection;
  - portal entity lifecycle;
  - destination chunk preparation;
  - per-tick portal management.
- `ChestPocketState`
  - `PersistentState`-based persistent destination indexing.
- portal UUID tracking and cleanup.

Most reusable concepts:

```text
persistent logical destination
 -> physical door interaction
 -> destination preparation
 -> passage lifecycle
 -> cleanup
```

Do **not** copy PortalChest's finite corridor-growth model as the final corridor architecture. This project uses bounded physical space plus logical infinity/repositioning.

### 30.2 Immersive Portals / DimLib

References:

https://github.com/iPortalTeam/ImmersivePortalsMod
https://github.com/iPortalTeam/DimLib
https://qouteall.fun/immptl/wiki/API-for-Other-Mods

Useful concepts:

- runtime dimension registration/removal;
- persistence of dynamic dimension configuration;
- remote-dimension chunk synchronization;
- see-through portal API;
- destination chunk loading.

These should be studied behind `DynamicDimensionBackend` / `PortalBackend`, not allowed to leak into the core domain model.

### 30.3 Iris

Reference:

https://github.com/IrisShaders/Iris

Architecture consequence:

The safest compatibility strategy is to avoid requiring custom framebuffer/world-render replacement. Iris compatibility should be validated through runtime tests rather than through direct Iris internals.

### 30.4 Fabric 1.21 API references

Pinned API/Javadoc references useful to the implementing agent:

- Fabric API 0.102.0+1.21 overview: https://maven.fabricmc.net/docs/fabric-api-0.102.0%2B1.21/
- Brewing builder: https://maven.fabricmc.net/docs/fabric-api-0.102.0%2B1.21/net/fabricmc/fabric/api/registry/FabricBrewingRecipeRegistryBuilder.html
- Fabric GameTest: https://maven.fabricmc.net/docs/fabric-api-0.102.0%2B1.21/net/fabricmc/fabric/api/gametest/v1/FabricGameTest.html
- Fabric Maven API artifact: https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.102.0%2B1.21/
- Yarn 1.21+build.9: https://maven.fabricmc.net/net/fabricmc/yarn/1.21%2Bbuild.9/
- Loader 0.17.2: https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.17.2/
- Loom 1.7.4: https://maven.fabricmc.net/net/fabricmc/fabric-loom/1.7.4/

---

## 31. Revision History / 0.7 Handoff Audit

### 31.1 Revision 0.7 — code-agent handoff audit

Added/refined for an executable handoff:

- exact MVP 7 × 7 × 7 internal/bulkhead geometry and development construction assumption;
- exact mod ID/package/build-wrapper baseline;
- dimension-role-aware Chamber projection and collision rules;
- locked M1 Brewing IDs/recipe/effect consumption semantics;
- hidden replica transfer model for the static Superposition Dimension;
- paged logical corridor replacing the insufficient single sliding window;
- explicit DimensionFamilyRoutingService requirement for parallel Nether/End portals;
- explicit RespawnRoutingService requirement for custom Universe fallback spawn;
- immediate projection materialization for already-loaded target chunks;
- explicit config schema, Universe materialization states, and concurrency serialization;
- shader compatibility acceptance criteria distinguish functional support from zero-frame seamlessness.

### 31.1a Revision 0.6 Decisions

Locked/refined in that revision:

- vanilla redstone circuitry can arm a valid Chamber through its Controller;
- activation uses a redstone **rising edge**, so sustained power cannot repeatedly create Sessions;
- redstone activation still requires a sealed valid 7 × 7 × 7 Chamber and `QuantumState` on every participant;
- redstone arming is separate from Origin-only global disable/destruction authority;
- the Controller reserves comparator-readable status output for vanilla automation;
- development is performed in a standalone Git repository outside `.minecraft`;
- recommended primary workstation is Windows + IntelliJ IDEA + JDK 21 + Git + committed Gradle Wrapper;
- the M0 baseline pins Minecraft 1.21, Yarn 1.21+build.9, Loader 0.17.2, Fabric API 0.102.0+1.21, and Loom 1.7.4;
- baseline, Iris/Sodium render testing, and dedicated-server run directories remain isolated;
- first implementation slice is M0 plus a redstone-aware Chamber Controller skeleton before dynamic-Universe work.

### 31.2 Revision 0.5 Decisions

Previously locked:

- Chamber external bounding cube = `7 × 7 × 7`;
- activation potion and Brewing recipe are first-party mod systems;
- every participant in a shared Superposition Session requires the buff;
- candidate probabilities remain provisional/configurable;
- death/respawn stays in the current Universe;
- destroyed Origin leaves inert Dead Chamber projections;
- new-Universe traversal uses minimum readiness gate + background warm-up;
- every Universe owns separate Overworld, Nether, and End dimension roles.

---

## 32. Code Agent Execution Contract

This section is written specifically so the same Markdown file can be handed to Claude Code, GPT Codex, or another repository-capable coding agent.

### 32.1 Authority and scope

- This document is the **single source of truth for gameplay and architecture** until the human owner revises it.
- Implement **Minecraft 1.21.0 only** first. Do not silently upgrade to 1.21.1 because a dependency is easier there.
- Preserve the locked runtime: Java 21, Fabric Loader 0.17.2 test environment, Fabric API 0.102.0+1.21, Yarn 1.21+build.9.
- Start as one Fabric module with mod ID `quantumchamber` and package root `dev.quantumchamber`.
- Do not add Immersive Portals, Sodium, Iris, Cloth Config, Mod Menu, Cardinal Components, DimLib, or another library as a mandatory dependency without a documented reason and explicit human approval.
- PortalChest/DimLib are implementation references. Prefer independent implementation. If substantial MIT-licensed code is copied, retain the legally required copyright/license notice and document exactly what was copied.

### 32.2 Execution order

Do **not** attempt M0–M8 in one giant change. Execute milestones in order. Each milestone ends with build/test evidence and a small Git commit series.

Required gate:

```text
M0 build + client + dedicated server clean
    -> M1
M1 tests clean
    -> M2
M2 multiplayer corridor prototype clean
    -> M3 dynamic-dimension feasibility spike
```

Before writing the full M3 backend, perform a focused **runtime dynamic-dimension feasibility spike on Minecraft 1.21.0**. Study DimLib/Immersive Portals techniques as references, identify the minimum required registry/network/save hooks, and write `docs/implementation-notes/m3-dynamic-dimensions.md`. If 1.21.0 requires a narrow vendored/backported mechanism, propose it. Do **not** silently change Minecraft version or fake "infinite Universes" using separate server processes.

M7 has a second explicit gate: do not implement literal live-world branching until the separate branch/snapshot storage design described in M7 is written and approved. M8 shader/resource-pack hardening may proceed without waiting for M7.

### 32.3 Required verification per milestone

At minimum, run and record:

```text
./gradlew clean build
```

plus the relevant Fabric GameTests/integration tests. M0/M1 and every milestone touching common/server code must also launch a dedicated server successfully with no client-class loading errors.

For manual render milestones, record the exact test stack (Minecraft/Fabric API/Loader/Sodium/Iris/shader pack/resource pack) in `docs/implementation-notes/compatibility.md`.

Never claim shader/resource-pack support from compilation alone.

### 32.4 Testing discipline

Prefer tests around pure domain state machines before Minecraft integration. Use Fabric GameTest for multiblock/redstone/persistence behavior where practical. Important race/state tests include:

- two players try to open different logical doors in the same tick: exactly one measurement wins;
- redstone held high across chunk unload/reload does not produce a false rising edge;
- failed activation does not consume `QuantumState`;
- successful activation consumes it for every cohort member atomically;
- a crash after Universe allocation but before `READY` recovers the same Universe UUID;
- a newly created Chamber materializes immediately in already-loaded same-role target chunks;
- a Chamber built in Overworld never projects into Nether/End roles;
- parallel Nether/End portal routing never leaks to canonical vanilla dimensions;
- fallback death respawn remains in the current Universe family.

### 32.5 Do not invent gameplay silently

Sections under `Remaining Open Design Questions` are non-blocking polish/balance unless marked otherwise. Use the explicit development defaults already present in this document. If implementation discovers a true architecture blocker or a gameplay contradiction, stop that milestone, document the issue and options, and ask the human owner rather than silently changing a Hard Invariant.

### 32.6 Implementation notes are mandatory

Maintain:

```text
docs/quantum_superposition_chamber_design.md   <- this spec
docs/implementation-notes/m0-bootstrap.md
docs/implementation-notes/m1-chamber.md
docs/implementation-notes/m2-corridor.md
docs/implementation-notes/m3-dynamic-dimensions.md
...
```

Each note records actual file/API choices, mixins introduced, tests run, known compatibility limitations, and deviations explicitly approved by the human owner. This keeps future Minecraft-version ports maintainable.

---

## 33. Change Policy

This document remains the project's **living specification**.

Rules:

- approved gameplay rules remain Hard Invariants until explicitly revised;
- implementation discoveries may refine architecture but must not silently change gameplay semantics;
- unresolved topics remain under `Remaining Open Design Questions`;
- Minecraft-version-specific work must be documented separately from loader-independent/domain rules;
- a future 1.21.1+ port should add an adaptation section rather than retroactively pretending the 1.21.0 baseline never existed;
- compatibility fixes for Iris/Sodium/resource packs should remain isolated from Universe/Chamber domain logic.
