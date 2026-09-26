package AndroidRoboTeam.world;

import rescuecore2.config.Config;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public final class SEUWorldConfigConstants {

    public int maxRayDistance;

    public final boolean debug = false;

    public final int timestep;

    public final int thinkTime;

    private static final String CHANNELS_KEY_PREFIX = "comms.channels.";

    public final int ignoreUntil;

    public final int channelCount;

    public final int subscribePlatoonSize;

    public final int subscribeCenterSize;

    public final Map<Integer, VoiceChannel> voiceChannels = new HashMap<Integer, VoiceChannel>();

    public final Map<Integer, RadioChannel> radioChannels = new HashMap<Integer, RadioChannel>();

    public final double woodenIgnition;

    public final double steelIgnition;

    public final double concreteIgnition;

    public final int viewDistance;

    public final int hpPrecision;

    public final int hpMax;

    public final int damagePrecision;

    public final int buildingIDMax;

    public final int maxTankCapacity;

    public final int maxPower;

    public final int tankRefillRate;

    public final int tankRefillHydrantRate;

    public final int extinguishableDistance;

    public final int repairDistance;

    public final int repairRate;

    public final int repairRad;

    public final int MIN_X;

    public final int MAX_X;

    public final int MIN_Y;

    public final int MAX_Y;

    public final Random random;

    public final double collapse_k;

    public final double collapse_l;

    public final double collapse_mean;

    public final double collapse_sd;

    public final double bury_k;

    public final double bury_l;

    public final double bury_mean;

    public final double bury_sd;

    public final double bury_wood_slight;

    public final double bury_wood_serious;

    public final double bury_wood_critical;

    public final double bury_steel_slight;

    public final double bury_steel_serious;

    public final double bury_steel_critical;

    public final double bury_concrete_slight;

    public final double bury_concrete_serious;

    public final double bury_concrete_critical;

    public final double collapse_wood_slight;

    public final double collapse_wood_serious;

    public final double collapse_wood_critical;

    public final double collapse_steel_slight;

    public final double collapse_steel_serious;

    public final double collapse_steel_critical;

    public final double collapse_concrete_slight;

    public final double collapse_concrete_serious;

    public final double collapse_concrete_critical;

    public final int bury_slight;

    public final int bury_serious;

    public final int bury_critical;

    public final int collapse_slight;

    public final int collapse_serious;

    public final int collapse_critical;

    public final int maxRoundHP;

    static public abstract class Channel {
        public final int channel;

        protected Channel(int c) {
            channel = c;
        }
    }

    static public class VoiceChannel extends Channel {

        public final int id;

        public final int range;

        public final int size;

        public final int maxNum;

        private VoiceChannel(int channel, final Config config) {
            super(channel);
            this.id = channel;

            final String RANGE_SUFFIX = ".range";
            final String MESSAGE_SIZE_SUFFIX = ".messages.size";
            final String MESSAGE_MAX_SUFFIX = ".messages.max";

            range = config.getIntValue(CHANNELS_KEY_PREFIX + channel + RANGE_SUFFIX);
            size = config.getIntValue(CHANNELS_KEY_PREFIX + channel + MESSAGE_SIZE_SUFFIX);
            maxNum = config.getIntValue(CHANNELS_KEY_PREFIX + channel + MESSAGE_MAX_SUFFIX);
        }
    }

    static public class RadioChannel extends Channel {

        public final int id;

        final public int bandwidth;

        public double inputFailureRate;

        public double outputFailureRate;

        public double inputDropoutRate;

        public double outputDropoutRate;

        final String INPUT_FAILURE_SUFFIX = ".noise.input.failure.p";
        final String INPUT_FAILURE_USE_SUFFIX = ".noise.input.failure.use";
        final String OUTPUT_FAILURE_SUFFIX = ".noise.output.failure.p";
        final String OUTPUT_FAILURE_USE_SUFFIX = ".noise.output.failure.use";

        final String INPUT_DROPOUT_SUFFIX = ".noise.input.dropout.p";
        final String INPUT_DROPOUT_USE_SUFFIX = ".noise.input.dropout.use";
        final String OUTPUT_DROPOUT_SUFFIX = ".noise.output.dropout.p";
        final String OUTPUT_DROPOUT_USE_SUFFIX = ".noise.output.dropout.use";

        private RadioChannel(int channel, final Config config) {
            super(channel);
            this.id = channel;

            final String RADIO_BAND_WIDTH_KEY = ".bandwidth";
            bandwidth = config.getIntValue(CHANNELS_KEY_PREFIX + channel + RADIO_BAND_WIDTH_KEY);

            try {
                if (config.getBooleanValue(CHANNELS_KEY_PREFIX + channel + INPUT_FAILURE_USE_SUFFIX)) {
                    inputFailureRate = config.getFloatValue(CHANNELS_KEY_PREFIX
                            + channel + INPUT_FAILURE_SUFFIX);
                } else {
                    inputFailureRate = 0.0;
                }
            } catch (Exception e) {
                inputFailureRate = 0.0;
            }

            try {
                if (config.getBooleanValue(CHANNELS_KEY_PREFIX + channel + OUTPUT_FAILURE_USE_SUFFIX)) {
                    outputFailureRate = config.getFloatValue(CHANNELS_KEY_PREFIX
                            + channel + OUTPUT_FAILURE_SUFFIX);
                } else {
                    outputFailureRate = 0.0;
                }
            } catch (Exception e) {
                outputFailureRate = 0.0;
            }

            try {
                if (config.getBooleanValue(CHANNELS_KEY_PREFIX + channel + INPUT_DROPOUT_USE_SUFFIX)) {
                    inputDropoutRate = config.getFloatValue(CHANNELS_KEY_PREFIX
                            + channel + INPUT_DROPOUT_SUFFIX);
                } else {
                    inputDropoutRate = 0.0;
                }
            } catch (Exception e) {
                inputDropoutRate = 0.0;
            }

            try {
                if (config.getBooleanValue(CHANNELS_KEY_PREFIX + channel + OUTPUT_DROPOUT_USE_SUFFIX)) {
                    outputDropoutRate = config.getFloatValue(CHANNELS_KEY_PREFIX
                            + channel + OUTPUT_DROPOUT_SUFFIX);
                } else {
                    outputDropoutRate = 0.0;
                }
            } catch (Exception e) {
                outputDropoutRate = 0.0;
            }
        }
    }

    public SEUWorldConfigConstants(final Config config, SEUWorldService worldService) {

        final String IGNORE_UNTIL_KEY = "kernel.agents.ignoreuntil";

        final int DEFAULT_IGNORE_UNTIL = 3;
        ignoreUntil = config.getIntValue(IGNORE_UNTIL_KEY, DEFAULT_IGNORE_UNTIL);

        final String TIMESTEP_KEY = "kernel.timesteps";

        final int DEFAULT_TIMESTEP = 300;
        timestep = config.getIntValue(TIMESTEP_KEY, DEFAULT_TIMESTEP);

        final String THINK_TIME_KEY = "kernel.agents.think-time";

        final int DEFAULT_THINK_TIME = 1000;
        thinkTime = config.getIntValue(THINK_TIME_KEY, DEFAULT_THINK_TIME);

        final String PERCEPTION_LOST_MAX_DISTANCE = "perception.los.max-distance";
        final int DEFAULT_PERCEPTION_LOST_MAX_DISTANCE = 30000;
        maxRayDistance = config.getIntValue(PERCEPTION_LOST_MAX_DISTANCE, DEFAULT_PERCEPTION_LOST_MAX_DISTANCE);

        final String CHANNELS_COUNT_KEY = CHANNELS_KEY_PREFIX + "count";
        channelCount = config.getIntValue(CHANNELS_COUNT_KEY);

        final String SUBSCRIBE_PLATOON_KEY = CHANNELS_KEY_PREFIX + "max.platoon";
        subscribePlatoonSize = config.getIntValue(SUBSCRIBE_PLATOON_KEY, 0);

        final String SUBSCRIBE_CENTER_KEY = CHANNELS_KEY_PREFIX + "max.centre";
        subscribeCenterSize = config.getIntValue(SUBSCRIBE_CENTER_KEY, 0);

        final String CHANNELS_TYPE_KEY = ".type";
        for (int i = 0; i < channelCount; i++) {
            final String type = config.getValue(CHANNELS_KEY_PREFIX + i + CHANNELS_TYPE_KEY);
            if (type.startsWith("voice")) {
                voiceChannels.put(i, new VoiceChannel(i, config));
            } else if (type.startsWith("radio")) {
                radioChannels.put(i, new RadioChannel(i, config));
            }
        }

        final String WOODEN_IGNITION_KEY = "resq-fire.wooden_ignition";
        final double DEFAULT_WOODEN_IGNITION = 47.0f;
        woodenIgnition = config.getFloatValue(WOODEN_IGNITION_KEY, DEFAULT_WOODEN_IGNITION);

        final String STEEL_IGNITION_KEY = "resq-fire.steel_ignition";
        final double DEFAULT_STEEL_IGNITION = 47.0f;
        steelIgnition = config.getFloatValue(STEEL_IGNITION_KEY, DEFAULT_STEEL_IGNITION);

        final String CONCRETE_IGNITION_KEY = "resq-fire.concrete_ignition";
        final double DEFAULT_CONCRETE_IGNITION = 47.0f;
        concreteIgnition = config.getFloatValue(CONCRETE_IGNITION_KEY, DEFAULT_CONCRETE_IGNITION);

        final String VIEW_DISTANCE_KEY = "perception.los.max-distance";
        final int DEFAULT_VIEW_DISTANCE = 30000;
        viewDistance = config.getIntValue(VIEW_DISTANCE_KEY, DEFAULT_VIEW_DISTANCE);

        final String HP_PERSEPTION_KEY = "perception.los.precision.hp";
        final int DEFAULT_HP_PERSEPTION = 1000;
        hpPrecision = config.getIntValue(HP_PERSEPTION_KEY, DEFAULT_HP_PERSEPTION);

        final String DAMAGE_PERSEPTION_KEY = "perception.los.precision.damage";
        final int DEFAULT_DAMAGE_PERSEPTION = 100;
        damagePrecision = config.getIntValue(DAMAGE_PERSEPTION_KEY, DEFAULT_DAMAGE_PERSEPTION);

        final String MAX_WATER_KEY = "fire.tank.maximum";
        final int DEFAULT_TANK_CAPACITY = 15000;
        maxTankCapacity = config.getIntValue(MAX_WATER_KEY, DEFAULT_TANK_CAPACITY);

        final String MAX_EXTINGUISHABLE_KEY = "fire.extinguish.max-distance";
        final int DEFAULT_EXTINGUISHABLE_DISTANCE = 30000;
        extinguishableDistance = config.getIntValue(MAX_EXTINGUISHABLE_KEY, DEFAULT_EXTINGUISHABLE_DISTANCE);

        final String MAX_POWER_KEY = "fire.extinguish.max-sum";
        final int DEFAULT_POWER = 1000;
        maxPower = config.getIntValue(MAX_POWER_KEY, DEFAULT_POWER);

        final String WATER_REFILL_KEY = "fire.tank.refill-rate";
        final int DEFAULT_REFILL_RATE = 2000;
        tankRefillRate = config.getIntValue(WATER_REFILL_KEY, DEFAULT_REFILL_RATE);

        final String WATER_REFILL_HYDRANT_KEY = "fire.tank.refill_hydrant_rate";
        final int DEFAULT_REFILL_HUDRANT_RATE = 150;
        tankRefillHydrantRate = config.getIntValue(WATER_REFILL_HYDRANT_KEY, DEFAULT_REFILL_HUDRANT_RATE);

        final String DISTANCE_KEY = "clear.repair.distance";
        final int DEFAULT_REPAIR_DISTANCE = 10000;
        repairDistance = config.getIntValue(DISTANCE_KEY, DEFAULT_REPAIR_DISTANCE);

        final int DEFAULT_REPAIR_RATE = 10;
        final String REPAIR_RATE_KEY = "clear.repair.rate";
        repairRate = config.getIntValue(REPAIR_RATE_KEY, DEFAULT_REPAIR_RATE);

        final String REPAIR_RAD_KEY = "clear.repair.rad";
        repairRad = config.getIntValue(REPAIR_RAD_KEY);

        MIN_X = worldService.getWorldInfo().getWorldBounds().first().first();
        MIN_Y = worldService.getWorldInfo().getWorldBounds().first().second();
        MAX_X = worldService.getWorldInfo().getWorldBounds().second().first();
        MAX_Y = worldService.getWorldInfo().getWorldBounds().second().second();

        int maxHP = 0;
        for (StandardEntity se : worldService.getHumansWithURN(worldService.getWorldInfo())) {
            Human hm = (Human) se;
            if (hm.isHPDefined()) {
                maxHP = Math.max(maxHP, hm.getHP());
            }
        }

        hpMax = maxHP;

        int maxBuildingID = 0;
        for (StandardEntity se : worldService.getBuildingsWithURN(worldService.getWorldInfo())) {
            if (maxBuildingID < se.getID().getValue())
                maxBuildingID = se.getID().getValue();
        }
        buildingIDMax = maxBuildingID;

        random = config.getRandom();
        collapse_k = 0.00025;
        collapse_l = 0.01;
        collapse_mean = 0.1;
        collapse_sd = 0.01;

        bury_k = 0.000035;
        bury_l = 0.01;
        bury_mean = 0.1;
        bury_sd = 0.01;

        collapse_wood_slight = 0.5;
        collapse_wood_serious = 0.3;
        collapse_wood_critical = 0.02;

        collapse_steel_slight = 0.1;
        collapse_steel_serious = 0.03;
        collapse_steel_critical = 0.005;

        collapse_concrete_slight = 0.1;
        collapse_concrete_serious = 0.03;
        collapse_concrete_critical = 0.005;

        bury_wood_slight = 0.4;
        bury_wood_serious = 0.5;
        bury_wood_critical = 0.1;

        bury_steel_slight = 0.4;
        bury_steel_serious = 0.5;
        bury_steel_critical = 0.1;

        bury_concrete_slight = 0.4;
        bury_concrete_serious = 0.5;
        bury_concrete_critical = 0.1;

        collapse_slight = 2;
        collapse_serious = 10;
        collapse_critical = 10000;

        bury_slight = 3;
        bury_serious = 15;
        bury_critical = 100;

        if ((this.hpMax % this.hpPrecision) > 0) {
            maxRoundHP = (hpMax / this.hpPrecision + 1) * this.hpPrecision;
        } else
            maxRoundHP = hpMax;
    }

}