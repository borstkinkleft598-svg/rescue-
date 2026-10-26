# Rolling L-DCOP integration

An opt-in paper experiment is now available: see [paper-reproduction.md](paper-reproduction.md).
It uses distributed BMS partitions, full-city experimental perception, persistent STN queues,
and virtual transport. It is separate from the default competition configuration.

The default now enables the v2 layered multi-task temporal planner.
See [ldcop-v2.md](ldcop-v2.md) for the current implementation, constraints,
tests, and limitations. The description below documents the retained v1 selector
(`LDcop.enabled=true`, `LDcop.layeredScheduling=false`). No new BMS, ADVP or
pseudo-communication algorithm is introduced by v2.

This project uses a bounded rolling-horizon version of L-DCOP for the
human-rescue scenario. Fire suppression is intentionally outside the scope.

## Layers

1. `RESCUE`: a Fire Brigade acts as the extraction/rescue agent and removes
   buriedness from a human.
2. `TRANSPORT`: an Ambulance Team loads the rescued civilian and transports
   them to a refuge.

The precedence constraint is `RESCUE -> TRANSPORT`. A candidate is feasible
only when travel, rescue/load, transport and unload are expected to finish
before the human's latest-finish time derived from HP and damage.

## Why the implementation is bounded

The paper's full STN insertion can inspect all `n!` task orders. That is too
expensive inside every simulator timestep. This implementation evaluates the
next task plus its dependent layer, then replans on the next timestep. It keeps
a target for a configurable lock period to avoid oscillation, but an urgent
task with substantially lower slack can pre-empt the lock.

## Configuration

The following keys are in `config/develop.json`:

- `LDcop.enabled`: enables the new selector. If it produces no feasible target,
  the previous selection logic remains as a fallback.
- `LDcop.targetLockTime`: target commitment duration in timesteps.
- `LDcop.preemptionMargin`: slack advantage required to pre-empt a lock.
- `LDcop.loadTime` and `LDcop.unloadTime`: fixed action-time estimates.
- `LDcop.defaultDamage`: fallback damage rate when damage is zero or unknown.

## Evaluation

Compare the same maps and seeds with `LDcop.enabled` set to `true` and `false`.
Record score, rescued/transported humans, target switches, missed time windows,
runtime and communication count.
