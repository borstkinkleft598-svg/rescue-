package team08024301.module.algorithm;

import static rescuecore2.standard.entities.StandardEntityURN.AMBULANCE_CENTRE;
import static rescuecore2.standard.entities.StandardEntityURN.AMBULANCE_TEAM;
import static rescuecore2.standard.entities.StandardEntityURN.BUILDING;
import static rescuecore2.standard.entities.StandardEntityURN.FIRE_BRIGADE;
import static rescuecore2.standard.entities.StandardEntityURN.FIRE_STATION;
import static rescuecore2.standard.entities.StandardEntityURN.GAS_STATION;
import static rescuecore2.standard.entities.StandardEntityURN.POLICE_FORCE;
import static rescuecore2.standard.entities.StandardEntityURN.POLICE_OFFICE;
import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;
import static rescuecore2.standard.entities.StandardEntityURN.ROAD;

import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import rescuecore2.misc.Pair;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

/**
 * 以「庇护所」为簇心的聚类，替代默认的 KMeansClustering。
 *
 * 思路：受害者聚集点通常紧邻庇护所（平民被安排在那里方便撤离），所以把兵力按庇护所分组、
 * 每组只负责自己那个庇护所周边的区域，比"一人一块均匀分地"更快找到各聚集点。
 *
 * 具体做法：
 *   1. 把地图所有区域（路 + 各类建筑）按"离哪个庇护所最近"做一次 Voronoi 划分；
 *   2. 每个庇护所区域内，按离庇护所远近排序，再轮流分给该组的 agent（rank % 庇护所数 == 该庇护所）。
 * 这样每个 agent 的 cluster = 它那个庇护所周边、互不重叠的一小片区域；
 * cluster 编号直接等于 agent 在同类型里的序号（按 ID 排序），`getClusterIndex(我的ID)` 返回该序号。
 */
public class RefugeClustering extends Clustering {

  private int clusterSize;                 // 本类型 agent 数：红 46 / 白 28 / 警 10
  private int selfRank = -1;               // 自己在同类型 agent 里的序号
  private List<List<EntityID>> clusterEntityIDsList;
  private StandardEntityURN selfType;

  public RefugeClustering(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    this.selfType = ai.me().getStandardURN();
    if (this.selfType == AMBULANCE_TEAM) {
      this.clusterSize = si.getScenarioAgentsAt();
    } else if (this.selfType == FIRE_BRIGADE) {
      this.clusterSize = si.getScenarioAgentsFb();
    } else if (this.selfType == POLICE_FORCE) {
      this.clusterSize = si.getScenarioAgentsPf();
    } else {
      this.clusterSize = 1;
    }
    this.clusterEntityIDsList = null;
  }


  @Override
  public Clustering preparate() {
    super.preparate();
    if (this.clusterEntityIDsList == null) {
      this.calcClusters();
    }
    return this;
  }


  @Override
  public Clustering calc() {
    if (this.clusterEntityIDsList == null) {
      this.calcClusters();
    }
    return this;
  }


  @Override
  public int getClusterNumber() {
    return this.clusterSize;
  }


  @Override
  public int getClusterIndex(StandardEntity entity) {
    return this.getClusterIndex(entity.getID());
  }


  @Override
  public int getClusterIndex(EntityID id) {
    if (this.clusterEntityIDsList == null) {
      this.calcClusters();
    }
    if (id.equals(this.agentInfo.getID())) {
      return this.selfRank;
    }
    for (int i = 0; i < this.clusterEntityIDsList.size(); i++) {
      if (this.clusterEntityIDsList.get(i).contains(id)) {
        return i;
      }
    }
    return -1;
  }


  @Override
  public Collection<StandardEntity> getClusterEntities(int index) {
    if (this.clusterEntityIDsList == null) {
      this.calcClusters();
    }
    List<EntityID> ids = this.clusterEntityIDsList.get(index);
    List<StandardEntity> result = new ArrayList<>(ids.size());
    for (EntityID id : ids) {
      StandardEntity e = this.worldInfo.getEntity(id);
      if (e != null) {
        result.add(e);
      }
    }
    return result;
  }


  @Override
  public Collection<EntityID> getClusterEntityIDs(int index) {
    if (this.clusterEntityIDsList == null) {
      this.calcClusters();
    }
    return this.clusterEntityIDsList.get(index);
  }


  private void calcClusters() {
    this.clusterEntityIDsList = new ArrayList<>(this.clusterSize);
    for (int i = 0; i < this.clusterSize; i++) {
      this.clusterEntityIDsList.add(new ArrayList<>());
    }

    // 自己在同类型 agent 里的序号（按 ID 排序）。
    List<StandardEntity> agents = new ArrayList<>();
    if (this.selfType == AMBULANCE_TEAM) {
      agents.addAll(this.worldInfo.getEntitiesOfType(AMBULANCE_TEAM));
    } else if (this.selfType == FIRE_BRIGADE) {
      agents.addAll(this.worldInfo.getEntitiesOfType(FIRE_BRIGADE));
    } else if (this.selfType == POLICE_FORCE) {
      agents.addAll(this.worldInfo.getEntitiesOfType(POLICE_FORCE));
    }
    Collections.sort(agents,
        Comparator.comparingInt(a -> a.getID().getValue()));
    for (int i = 0; i < agents.size(); i++) {
      if (agents.get(i).getID().equals(this.agentInfo.getID())) {
        this.selfRank = i;
        break;
      }
    }

    List<StandardEntity> refuges = new ArrayList<>(
        this.worldInfo.getEntitiesOfType(REFUGE));
    Collections.sort(refuges,
        Comparator.comparingInt(a -> a.getID().getValue()));
    int refugeCount = refuges.size();

    // 所有"区域"实体（路 + 各类建筑），搜索/救人/清障都要用。
    List<StandardEntity> areas = new ArrayList<>();
    areas.addAll(this.worldInfo.getEntitiesOfType(ROAD));
    areas.addAll(this.worldInfo.getEntitiesOfType(BUILDING));
    areas.addAll(this.worldInfo.getEntitiesOfType(GAS_STATION));
    areas.addAll(this.worldInfo.getEntitiesOfType(AMBULANCE_CENTRE));
    areas.addAll(this.worldInfo.getEntitiesOfType(FIRE_STATION));
    areas.addAll(this.worldInfo.getEntitiesOfType(POLICE_OFFICE));
    areas.addAll(this.worldInfo.getEntitiesOfType(REFUGE));

    // 没有庇护所：退化，按 ID 轮流分给所有 agent，保证互不重叠。
    if (refugeCount == 0) {
      Collections.sort(areas,
          Comparator.comparingInt(a -> a.getID().getValue()));
      for (int i = 0; i < areas.size(); i++) {
        this.clusterEntityIDsList.get(i % this.clusterSize)
            .add(areas.get(i).getID());
      }
      return;
    }

    // 每个区域归到最近的庇护所（Voronoi 划分）。
    List<List<StandardEntity>> perRefuge = new ArrayList<>(refugeCount);
    for (int i = 0; i < refugeCount; i++) {
      perRefuge.add(new ArrayList<>());
    }
    for (StandardEntity area : areas) {
      int nearest = 0;
      double best = Double.MAX_VALUE;
      for (int i = 0; i < refugeCount; i++) {
        double d = distance(refuges.get(i), area);
        if (d < best) {
          best = d;
          nearest = i;
        }
      }
      perRefuge.get(nearest).add(area);
    }

    // 每个庇护所区域内：按离庇护所远近排序，轮流分给该组的 agent。
    for (int r = 0; r < refugeCount; r++) {
      List<StandardEntity> region = perRefuge.get(r);
      final StandardEntity center = refuges.get(r);
      Collections.sort(region, (a, b) -> Double.compare(distance(center, a),
          distance(center, b)));
      List<Integer> memberRanks = new ArrayList<>();
      for (int rank = r; rank < this.clusterSize; rank += refugeCount) {
        memberRanks.add(rank);
      }
      int groupSize = memberRanks.size();
      for (int i = 0; i < region.size(); i++) {
        int slot = i % groupSize;
        this.clusterEntityIDsList.get(memberRanks.get(slot))
            .add(region.get(i).getID());
      }
    }
  }


  private double distance(StandardEntity a, StandardEntity b) {
    Pair<Integer, Integer> la = this.worldInfo.getLocation(a);
    Pair<Integer, Integer> lb = this.worldInfo.getLocation(b);
    if (la == null || lb == null) {
      return Double.MAX_VALUE;
    }
    int dx = la.first() - lb.first();
    int dy = la.second() - lb.second();
    return Math.hypot(dx, dy);
  }
}
