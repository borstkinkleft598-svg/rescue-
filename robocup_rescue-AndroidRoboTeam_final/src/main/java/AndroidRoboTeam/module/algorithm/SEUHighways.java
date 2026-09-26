package AndroidRoboTeam.module.algorithm;

import static java.util.Comparator.*;
import static java.util.stream.Collectors.*;
import static rescuecore2.standard.entities.StandardEntityURN.*;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.algorithm.*;
import java.util.*;
import java.util.stream.*;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

public class SEUHighways extends StaticClustering {

  private Set<EntityID> highways = new HashSet<>();
  private PathPlanning pathplanner;

  private static final int SAMPLE_NUMBER = 10;

  private static final String MODULE_NAME = "AndroidRoboTeam.module.algorithm.SEUHighways";
  private static final String PD_HIGHWAYS = MODULE_NAME + ".highways";

  public SEUHighways(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd);
    this.pathplanner = mm.getModule("AndroidRoboTeam.Algorithm.SEUHighways.PathPlanning");
    this.registerModule(this.pathplanner);
  }

  @Override
  public Clustering calc() {
    return this;
  }

  @Override
  public Clustering updateInfo(MessageManager mm) {
    super.updateInfo(mm);
    if (this.getCountUpdateInfo() > 1) {
      return this;
    }
    return this;
  }

  @Override
  public Clustering precompute(PrecomputeData pd) {
    super.precompute(pd);
    if (this.getCountPrecompute() > 1) {
      return this;
    }
    this.build();

    pd.setEntityIDList(PD_HIGHWAYS, new ArrayList<>(this.highways));
    return this;
  }

  @Override
  public Clustering resume(PrecomputeData pd) {
    super.resume(pd);
    if (this.getCountResume() > 1) {
      return this;
    }

    this.highways = new HashSet<>(pd.getEntityIDList(PD_HIGHWAYS));
    return this;
  }

  @Override
  public Clustering preparate() {
    super.preparate();
    if (this.getCountPreparate() > 1) {
      return this;
    }
    return this;
  }

  @Override
  public int getClusterNumber() {
    return 1;
  }

  @Override
  public int getClusterIndex(StandardEntity entity) {
    return this.getClusterIndex(entity.getID());
  }

  @Override
  public int getClusterIndex(EntityID id) {
    return this.highways.contains(id) ? 0 : -1;
  }

  @Override
  public Collection<EntityID> getClusterEntityIDs(int i) {
    return i == 0 ? new HashSet<>(this.highways) : null;
  }

  @Override
  public Collection<StandardEntity> getClusterEntities(int i) {
    Collection<EntityID> ids = this.getClusterEntityIDs(i);
    if (ids == null) {
      return null;
    }

    Stream<StandardEntity> entities = ids.stream().map(this.worldInfo::getEntity);
    return entities.collect(toList());
  }

  private void build() {
    final int n = SAMPLE_NUMBER;
    List<EntityID> samples = new ArrayList<>(this.sample(n));

    for (int i = 0; i < n; ++i) {
      for (int j = i + 1; j < n; ++j) {
        this.pathplanner.setFrom(samples.get(i));
        this.pathplanner.setDestination(samples.get(j));
        this.pathplanner.calc();
        this.highways.addAll(this.pathplanner.getResult());
      }
    }
  }

  private Set<EntityID> sample(int n) {
    List<StandardEntity> entities = new ArrayList<>(this.worldInfo.getEntitiesOfType(
        BUILDING, GAS_STATION, REFUGE, FIRE_STATION, AMBULANCE_CENTRE, POLICE_OFFICE));
    entities.sort(comparing(e -> e.getID().getValue()));
    final int size = entities.size();

    EntityID[] is = new EntityID[size];
    double[] xs = new double[size];
    double[] ys = new double[size];

    for (int i = 0; i < size; ++i) {
      Area area = (Area) entities.get(i);
      is[i] = area.getID();
      xs[i] = area.getX();
      ys[i] = area.getY();
    }

    SEUKmeansPPUtil kmeans = new SEUKmeansPPUtil(is, xs, ys, n);
    kmeans.execute(10);

    Set<EntityID> ret = new HashSet<>();
    for (int i = 0; i < n; ++i) {
      final double cx = kmeans.getClusterX(i);
      final double cy = kmeans.getClusterY(i);

      Optional<Area> sampled = kmeans.getClusterMembers(i)
          .stream()
          .map(this.worldInfo::getEntity)
          .map(Area.class::cast)
          .min((m1, m2) -> {
            final double x1 = m1.getX();
            final double y1 = m1.getY();
            final double x2 = m2.getX();
            final double y2 = m2.getY();

            final double d1 = Math.hypot(x1 - cx, y1 - cy);
            final double d2 = Math.hypot(x2 - cx, y2 - cy);

            return Double.compare(d1, d2);
          });

      sampled.ifPresent(m -> ret.add(m.getID()));
    }

    return ret;
  }
}
