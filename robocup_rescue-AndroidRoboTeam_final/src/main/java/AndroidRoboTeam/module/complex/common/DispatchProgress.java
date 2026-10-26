package AndroidRoboTeam.module.complex.common;

/** A renewed radio lease is not evidence of useful movement. No shared state. */
public record DispatchProgress(int target, int position, int bestDistance, int progressedAt,
                               int seenAt, boolean working) {
    public static DispatchProgress update(DispatchProgress previous, int target, int position,
                                          int distance, boolean working, int now) {
        int validDistance = distance < 0 ? Integer.MAX_VALUE : distance;
        if (previous == null || previous.target != target)
            return new DispatchProgress(target, position, validDistance, now, now, working);
        boolean improvement = validDistance < previous.bestDistance || (working && validDistance == 0);
        return new DispatchProgress(target, position, Math.min(previous.bestDistance, validDistance),
                improvement ? now : previous.progressedAt, now, working);
    }

    public boolean productive(int now, int stallTime) {
        return now - progressedAt < stallTime;
    }

    public static boolean mayDivertToAssist(int currentTravel, int assistTravel, int saving,
                                            int currentSlack, int requiredMargin) {
        return assistTravel < TemporalSchedule.UNREACHABLE && assistTravel >= 0
                && saving >= requiredMargin && currentSlack > saving
                && currentTravel >= assistTravel + requiredMargin;
    }
}
