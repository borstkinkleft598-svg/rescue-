package AndroidRoboTeam.module.complex.paper;

import java.util.*;

/** Virtual transport is an experiment metric; it NEVER changes the RRS world or kernel score. */
public final class VirtualTransportLedger {
    public enum Status { BURIED, RESCUED, TRANSPORTED, DEAD, UNKNOWN }
    public record Observation(int id, int buriedness, int hp, int damage, int transportTime, int refuge) {}
    public record Entry(int id, Status status, int rescuedAt, int arrivalAt, int refuge) {}
    private final Map<Integer, Entry> entries = new TreeMap<>();

    public void observe(int time, Collection<Observation> observations) {
        for (Observation o : observations) {
            Entry old = entries.get(o.id());
            // The cohort contains initially observed buried civilians, not every healthy civilian.
            if (old == null && o.buriedness() <= 0) continue;
            if (old == null) old = new Entry(o.id(), Status.BURIED, -1, -1, o.refuge());
            if (old.status() == Status.TRANSPORTED || old.status() == Status.DEAD || old.status() == Status.UNKNOWN) { entries.put(o.id(), old); continue; }
            if (old.status() == Status.BURIED && o.buriedness() <= 0 && o.hp() > 0)
                old = new Entry(o.id(), Status.RESCUED, time,
                        o.refuge() < 0 ? Integer.MAX_VALUE : time + Math.max(0, o.transportTime()), o.refuge());
            if (old.status() == Status.RESCUED && old.arrivalAt() <= time) {
                // HP is clamped at zero: a dead, late observation cannot prove survival at arrival.
                Status status = o.hp() > 0 ? Status.TRANSPORTED
                        : time == old.arrivalAt() ? Status.DEAD : Status.UNKNOWN;
                old = new Entry(old.id(), status,
                        old.rescuedAt(), old.arrivalAt(), old.refuge());
            } else if (o.hp() <= 0) old = new Entry(old.id(), Status.DEAD, old.rescuedAt(), old.arrivalAt(), old.refuge());
            entries.put(o.id(), old);
        }
    }
    public Set<Integer> cohort() { return Set.copyOf(entries.keySet()); }
    public Collection<Entry> entries() { return List.copyOf(entries.values()); }
    public long rescued() { return entries.values().stream().filter(e -> e.rescuedAt() >= 0).count(); }
    public long transported() { return entries.values().stream().filter(e -> e.status() == Status.TRANSPORTED).count(); }
}
