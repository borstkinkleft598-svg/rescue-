package gis2.scenario;

import gis2.GisScenario;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * A snapshot of a {@link GisScenario}.
 */
public class ScenarioSnapshot {
    private final Collection<Integer> civilians;
    private final Collection<Integer> fireBrigades;
    private final Collection<Integer> ambulanceTeams;
    private final Collection<Integer> policeForces;
    private final Collection<Integer> fireStations;
    private final Collection<Integer> ambulanceCentres;
    private final Collection<Integer> policeOffices;
    private final HashMap<Integer, Integer> refuges;
    private final Set<Integer> hydrants;
    private final Set<Integer> gasStations;
    private final Set<Integer> fires;

    /**
     * Create a snapshot of the given {@link GisScenario}.
     *
     * @param scenario The scenario to snapshot.
     */
    public ScenarioSnapshot(GisScenario scenario) {
        civilians        = new HashSet<>(scenario.getCivilians());
        fireBrigades     = new HashSet<>(scenario.getFireBrigades());
        ambulanceTeams   = new HashSet<>(scenario.getAmbulanceTeams());
        policeForces     = new HashSet<>(scenario.getPoliceForces());
        fireStations     = new HashSet<>(scenario.getFireStations());
        ambulanceCentres = new HashSet<>(scenario.getAmbulanceCentres());
        policeOffices    = new HashSet<>(scenario.getPoliceOffices());
        refuges          = new HashMap<>(scenario.getRefugeBedCapacity());
        hydrants         = new HashSet<>(scenario.getHydrants());
        gasStations      = new HashSet<>(scenario.getGasStations());
        fires            = new HashSet<>(scenario.getFires());
    }

    /**
     * Restore this snapshot into the given {@link GisScenario}.
     *
     * @param scenario The scenario to restore into.
     */
    public void restore(GisScenario scenario) {
        scenario.setCivilians(civilians);
        scenario.setFireBrigades(fireBrigades);
        scenario.setAmbulanceTeams(ambulanceTeams);
        scenario.setPoliceForces(policeForces);
        scenario.setFireStations(fireStations);
        scenario.setAmbulanceCentres(ambulanceCentres);
        scenario.setPoliceOffices(policeOffices);
        scenario.setRefuges(refuges);
        scenario.setHydrants(hydrants);
        scenario.setGasStations(gasStations);
        scenario.setFires(fires);
    }
}
