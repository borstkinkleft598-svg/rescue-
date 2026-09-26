package AndroidRoboTeam.world;

import javolution.util.FastSet;
import rescuecore2.log.Logger;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

public class SEUBuilding {
    public static Map<EntityID, Map<EntityID, SEUBuilding>> VIEWER_BUILDING_MAP = new HashMap<>();

    private List<EntityID> areasInExtinguishableRange;

    private List<SEUBuilding> radiationNeighbourBuildings;

    private List<EntityID> radiationNeighbourBuildingsId;

    private SEULineOfSightPerception lineOfSightPerception;
    private List<EntityID> observableAreas;

    private List<SEUWall> walls;
    private double totalWallArea;
    private List<SEUWall> allWalls;

    private Hashtable<SEUBuilding, Integer> connectedBuildingTable;

    private List<SEUBuilding> connectedBuildings;

    private List<Float> connectedValues;
    private Building selfBuilding;
    private SEUWorldService worldService;

    private boolean visited;

    private Set<SEUBuilding> neighbourDangerBuildings;

    public double BUILDING_VALUE = Double.MIN_VALUE;
    public double priority = Double.MIN_VALUE;
    private int ignitionTime = -1;

    private int zoneId;

    private double advantageRatio;

    private boolean isVisible = false;
    private double hitRate = 0;
    protected int totalHits;
    private int lastSeenTime;
    private int lastUpdateTime;

    public SEUBuilding(StandardEntity entity, SEUWorldService worldService) {
        this.worldService = worldService;
        this.selfBuilding = (Building) entity;
        this.connectedBuildingTable = new Hashtable<>(30);
        this.connectedBuildings = new ArrayList<>();
        this.connectedValues = new ArrayList<>();

        this.radiationNeighbourBuildings = new ArrayList<>();
        this.radiationNeighbourBuildingsId = new ArrayList<>();
        this.neighbourDangerBuildings = new FastSet<>();

        this.lineOfSightPerception = new SEULineOfSightPerception(worldService);

        this.visited = false;

        this.initWalls(worldService);
        this.initSimulatorValues();
    }

    public void addNeighbourBuilding(SEUBuilding neighbour) {
        this.radiationNeighbourBuildings.add(neighbour);
        this.radiationNeighbourBuildingsId.add(neighbour.getSelfBuilding()
                .getID());
        this.allWalls.addAll(neighbour.getWalls());
    }

    private void initWalls(SEUWorldService worldService) {
        int[] apexList = this.selfBuilding.getApexList();
        int firstX = apexList[0], firstY = apexList[1];
        int lastX = firstX, lastY = firstY;
        SEUWall wall;
        this.walls = new ArrayList<>();
        this.allWalls = new ArrayList<>();

        for (int i = 2; i < apexList.length; i++) {
            int tempX = apexList[i], tempY = apexList[++i];
            wall = new SEUWall(lastX, lastY, tempX, tempY, this);
            if (wall.validate()) {
                this.walls.add(wall);
                this.totalWallArea += FLOOR_HEIGHT * wall.length * 1000;
            } else {
                Logger.warn("Ignoring odd wall at building "
                        + selfBuilding.getID().getValue());
            }
            lastX = tempX;
            lastY = tempY;
        }
        wall = new SEUWall(lastX, lastY, firstX, firstY, this);
        if (wall.validate()) {
            this.walls.add(wall);
        }
        allWalls.addAll(walls);
        this.totalWallArea = this.totalWallArea / 1000000d;
    }

    public List<SEUWall> getWalls() {
        return this.walls;
    }

    public List<SEUWall> getAllWalls() {
        return this.allWalls;
    }

    public Hashtable<SEUBuilding, Integer> getConnectedBuildingTable() {
        return connectedBuildingTable;
    }

    public void setIgnitionTime(int ignitionTime) {
        this.ignitionTime = ignitionTime;
    }

    static final int FLOOR_HEIGHT = 3;
    static float RADIATION_COEFFICIENT = 0.011f;
    static final double STEFAN_BOLTZMANN_CONSTANT = 0.000000056704;

    private int startTime = -1;

    private float fuel;
    private float initFuel = -1;

    private float volume;
    private double energy;

    private float capacity;

    private float prevBurned;

    private int waterQuantity = 0;

    private int lwater = 0;
    private int lwTime = -1;
    private boolean wasEverWatered = false;
    private boolean inflammable = true;

    public static float woodIgnition = 47.0f;
    public static float steelIgnition = 47.0f;
    public static float concreteIgnition = 47.0f;

    public static float woodCapacity = 1.1f;
    public static float steelCapacity = 1.0f;
    public static float concreteCapacity = 1.5f;

    public static float woodEnergy = 2400.0f;
    public static float steelEnergy = 800.0f;
    public static float concreteEnergy = 350.0f;

    public void initSimulatorValues() {
        volume = selfBuilding.getGroundArea() * selfBuilding.getFloors()
                * FLOOR_HEIGHT;
        fuel = getInitialFuel();
        capacity = (volume * getThermoCapacity());
        energy = 0;
        initFuel = -1;
        prevBurned = 0;

        lwater = 0;
        lwTime = -1;
        wasEverWatered = false;

        Logger.info("Initialised the simulator values for building "
                + selfBuilding.getID() + ": ground area = "
                + selfBuilding.getGroundArea() + ", floors = "
                + selfBuilding.getFloors() + ", volume = " + volume
                + ", initial fuel = " + initFuel + ", energy capacity = "
                + getCapacity());
    }

    public float getInitialFuel() {
        if (initFuel < 0)
            initFuel = getFuelDensity() * volume;
        return initFuel;
    }

    public float getThermoCapacity() {
        switch (selfBuilding.getBuildingCode()) {
            case 0:
                return woodCapacity;
            case 1:
                return steelCapacity;
            default:
                return concreteCapacity;
        }
    }

    public float getIgnitionPoint() {
        switch (selfBuilding.getBuildingCode()) {
            case 0:
                return woodIgnition;
            case 1:
                return steelIgnition;
            default:
                return concreteIgnition;
        }
    }

    public float getFuelDensity() {
        switch (selfBuilding.getBuildingCode()) {
            case 0:
                return woodEnergy;
            case 1:
                return steelEnergy;
            default:
                return concreteEnergy;
        }
    }

    public double getEstimatedTemperature() {
        double rv = energy / capacity;

        if (Double.isNaN(rv)) {
            Logger.warn("Building " + selfBuilding.getID()
                    + " getTemperature returned NaN");
            new RuntimeException().printStackTrace();
            Logger.warn("Energy: " + energy);
            Logger.warn("Capacity: " + getCapacity());
            Logger.warn("Volume: " + volume);
            Logger.warn("Thermal capacity: " + getThermoCapacity());
            Logger.warn("Ground area: " + selfBuilding.getGroundArea());
            Logger.warn("Floors: " + selfBuilding.getFloors());

        }
        if (rv == Double.NaN || rv == Double.POSITIVE_INFINITY
                || rv == Double.NEGATIVE_INFINITY)
            rv = Double.MAX_VALUE * 0.75;
        return rv;
    }

    public int getEstimatedFieryness() {
        if (!isInflammable())
            return 0;
        if (getEstimatedTemperature() >= getIgnitionPoint()) {
            if (fuel >= getInitialFuel() * 0.66)
                return 1;
            if (fuel >= getInitialFuel() * 0.33)
                return 2;
            if (fuel > 0)
                return 3;
        }
        if (fuel == getInitialFuel())
            if (wasEverWatered)
                return 4;
            else
                return 0;
        if (fuel >= getInitialFuel() * 0.66)
            return 5;
        if (fuel >= getInitialFuel() * 0.33)
            return 6;
        if (fuel > 0)
            return 7;
        return 8;
    }

    public Building getSelfBuilding() {
        return selfBuilding;
    }

    public float getCapacity() {
        return this.capacity;
    }

    public void setEnergy(double value, String invokeMethod) {
        if (value == Double.NaN || value == Double.POSITIVE_INFINITY
                || value == Double.NEGATIVE_INFINITY)
            value = Double.MAX_VALUE * 0.75d;

        this.energy = value;
    }

    public float getFuel() {
        return this.fuel;
    }

    public void setFuel(float fuel) {
        this.fuel = fuel;
    }

    public boolean isInflammable() {
        return this.inflammable;
    }

    public void setInflammable(boolean inflammable) {
        this.inflammable = inflammable;
    }

    public EntityID getId() {
        return this.selfBuilding.getID();
    }

    public void setVisible(boolean visible) {
        this.isVisible = visible;
    }

    @Override
    public String toString() {
        return "SEUBuilding: [" + this.selfBuilding + "]";
    }

    public void updateValues(Building building) {
        switch (building.getFieryness()) {
            case 0:
                this.setFuel(this.getInitialFuel());
                if (getEstimatedTemperature() >= getIgnitionPoint()) {
                    setEnergy(getIgnitionPoint() / 2, "updateValues");
                }
                break;
            case 1:
                if (getFuel() < getInitialFuel() * 0.66) {
                    setFuel((float) (getInitialFuel() * 0.75));
                } else if (getFuel() == getInitialFuel()) {
                    setFuel((float) (getInitialFuel() * 0.90));
                }
                break;

            case 2:
                if (getFuel() < getInitialFuel() * 0.33
                        || getFuel() > getInitialFuel() * 0.66) {
                    setFuel((float) (getInitialFuel() * 0.50));
                }
                break;

            case 3:
                if (getFuel() < getInitialFuel() * 0.01
                        || getFuel() > getInitialFuel() * 0.33) {
                    setFuel((float) (getInitialFuel() * 0.15));
                }
                break;

            case 8:
                setFuel(0);
                break;
        }
    }

    public void setLastSeenTime(int lastSeenTime) {
        this.lastSeenTime = lastSeenTime;
    }

    public void setLastUpdateTime(int lastUpdateTime) {
        this.lastUpdateTime = lastUpdateTime;
    }
}