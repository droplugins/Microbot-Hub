# DroRecorder

Passive recording starts when the plugin is enabled. Output remains in
`~/.runelite/dro-recorder/<recording>/session.jsonl` with discrete events mirrored
to `events.jsonl`. Existing controls and marker keys retain their behavior.

Schema 1.1 adds capture automatically, without new configuration steps:

- Every record includes tick, client game cycle, sequence, monotonic elapsed time
  and time since the latest observed game tick. Mouse callbacks use the latest
  client-thread clock observation, identified by `clockSource`.
- NPCs and projectiles have recording-local `entityId` values. NPC transformations
  retain identity; despawn/reused indices receive a fresh identity. Trackers use
  weak references. Graphics, actor spot animations and explicit deaths are captured.
- Attack records preserve raw IDs and include `style`, `evidence`, and `evidenceId`.
  The initial catalogue covers elemental spell/magic-dart projectiles and common arrows, crossbow bolts, darts
  and javelins and the
  existing verified DroFirecape Fight Caves attack animations. Other IDs remain
  `UNKNOWN`. `observedSourceAttack` describes the source's current animation; it
  is not proof that animation produced the projectile.
- Tiles include a packed unsigned key: plane bits 29–28, X bits 27–14, Y bits 13–0.
  Scope keys using world-view and scene information. Template coordinates and
  copied instance chunk mappings allow instance reconstruction.
- Objects include definition names/actions, transformed IDs, sizes, orientations
  and game-object scene bounds. Dynamic definition changes produce
  `object_transformed`; object changes invalidate cached path results.
- Ground-item spawn/despawn/quantity, skill changes, special-attack energy/state,
  attack style, poison/venom raw state, game messages and dialogue changes are
  captured. Only game-message/spam channels are included; player names are omitted
  from actor records. Skill snapshot triples mean `[base,boosted,xp]`.
- Menu clicks resolve NPC/object/item/widget targets and subsequent observations
  carry recent action sequence references (maximum 16 actions, expiring after
  12 observed ticks). These references support analysis, not a claim of causation
  or action success. Tick snapshots include the observed movement destination.
- Prayer transitions are checked on client variable changes as well as game ticks,
  including same-tick switches. Pause/resume records report gap duration and
  refresh scene/ground-item/graphics baselines on the next ready game tick.
- The background writer serializes copied record data, flushes on idle intervals,
  drains on shutdown, and counts queue losses by event type. The queue is bounded
  to 4096 data records with reserved terminal/shutdown slots. `recording_gap`
  reports newly observed losses. A writer failure is logged and exposed through
  asynchronous completion; shutdown never waits on the client thread.

Spatial snapshots still cover the player's loaded plane. Path analysis ignores
actor blocking; raw collision and graphics do not by themselves prove a tile is
safe. Increased capture produces larger files. Compilation and focused tests do
not establish real-client timing, classification coverage, or performance.

Version 1.1.1 records `focus_changed` events automatically: `scope` distinguishes
`window`, `canvas`, and the RuneLite event (`runelite`); `focused` is the observed
boolean. Window/canvas attachment emits a `baseline`, followed by `change` records.
Records include the normal timestamp, tick and sequence fields, plus `temporary`
for canvas transitions. Focus recording pauses with the recorder. Mouse boundary
events remain separate. This observes focus only and does not request focus or
change game packets. Output remains local in the existing JSONL files.

Version 1.1.4 keeps the overlay as a translucent 204 × 26 status strip with
elapsed time. There is no expand button or detail panel. The recording dot turns
into a pause symbol when paused; the indicator turns amber if records are dropped.
The timer stays visible in every state. Alt-drag to reposition. Marker bindings
and **Open Output Folder** remain available in the plugin settings.

## Public Hub installation

Requires Microbot 2.6.33 or newer. Enable **[Dro] Recorder** to begin local
recording. F7 marks a safe position, F8 a mistake, F9 an important action, and
F10 pauses or resumes capture. Hotkeys can be changed in plugin settings.

**Open Output Folder** works while the plugin is enabled, including while
recording is paused. When the plugin is disabled, open
`~/.runelite/dro-recorder/` directly in your file manager. The public Hub plugin
uses standard config events and does not require private client UI hooks.

Recording files stay on your computer; the plugin does not upload them. Review
recordings before sharing them: they contain gameplay, menu/widget text and
inventory/equipment data.
