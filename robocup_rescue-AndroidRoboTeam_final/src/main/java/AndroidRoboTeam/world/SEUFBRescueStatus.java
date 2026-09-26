package AndroidRoboTeam.world;

import rescuecore2.worldmodel.EntityID;

public class SEUFBRescueStatus {

    private EntityID fireBrigadeId;
    private int lastRescueTime;
    private int rescueCount;
    private int noRescueTurnCount;
    private EntityID lastTarget;
    private boolean isTimeout;
    private int timeoutStartTime;

    public SEUFBRescueStatus(EntityID fireBrigadeId) {
        this.fireBrigadeId = fireBrigadeId;
        this.lastRescueTime = -1;
        this.rescueCount = 0;
        this.noRescueTurnCount = 0;
        this.lastTarget = null;
        this.isTimeout = false;
        this.timeoutStartTime = -1;
    }

    public void updateRescueSuccess(int currentTime, EntityID targetId) {
        this.lastRescueTime = currentTime;
        this.rescueCount++;
        this.noRescueTurnCount = 0;
        this.lastTarget = targetId;
        this.isTimeout = false;
        this.timeoutStartTime = -1;
    }

    public void updateNoRescue(int currentTime) {
        if (lastRescueTime >= 0) {
            noRescueTurnCount = currentTime - lastRescueTime;
        } else {
            noRescueTurnCount = currentTime + 1;
        }
    }

    public boolean isRescueTimeout(int timeoutTurns) {
        if (lastRescueTime < 0) {

            return noRescueTurnCount >= timeoutTurns;
        }
        return noRescueTurnCount >= timeoutTurns;
    }

    public void markTimeoutStart(int currentTime) {
        this.isTimeout = true;
        this.timeoutStartTime = currentTime;
    }

    public void resetTimeout() {
        this.isTimeout = false;
        this.timeoutStartTime = -1;
    }

    public int getTimeoutDuration(int currentTime) {
        if (isTimeout && timeoutStartTime >= 0) {
            return currentTime - timeoutStartTime;
        }
        return 0;
    }

    public EntityID getFireBrigadeId() {
        return fireBrigadeId;
    }

    public int getLastRescueTime() {
        return lastRescueTime;
    }

    public int getRescueCount() {
        return rescueCount;
    }

    public int getNoRescueTurnCount() {
        return noRescueTurnCount;
    }

    public EntityID getLastTarget() {
        return lastTarget;
    }

    public boolean isTimeout() {
        return isTimeout;
    }

    public int getTimeoutStartTime() {
        return timeoutStartTime;
    }

    @Override
    public String toString() {
        return String.format("RescueStats{id=%s, lastRescue=%d, rescueCount=%d, noRescueTurns=%d, timeout=%s}",
                fireBrigadeId, lastRescueTime, rescueCount, noRescueTurnCount, isTimeout);
    }
}