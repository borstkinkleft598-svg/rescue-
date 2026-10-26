package AndroidRoboTeam.module.complex.common;

import adf.core.agent.info.WorldInfo;

/** The competition modules read current state only. Retain ADF rollback history
 * only for explicit debugging; this does not affect server replay recording. */
public final class WorldHistoryPolicy {
    private WorldHistoryPolicy() {}
    public static void configure(WorldInfo world, boolean keepHistory) {
        if (keepHistory) world.requestRollback();
    }
}
