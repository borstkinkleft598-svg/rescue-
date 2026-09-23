package team08024301.module.complex;

import static rescuecore2.standard.entities.StandardEntityURN.AMBULANCE_TEAM;
import static rescuecore2.standard.entities.StandardEntityURN.CIVILIAN;
import static rescuecore2.standard.entities.StandardEntityURN.FIRE_BRIGADE;
import static rescuecore2.standard.entities.StandardEntityURN.GAS_STATION;
import static rescuecore2.standard.entities.StandardEntityURN.POLICE_FORCE;
import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.information.MessageAmbulanceTeam;
import adf.core.agent.communication.standard.bundle.information.MessageFireBrigade;
import adf.core.agent.communication.standard.bundle.information.MessagePoliceForce;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.complex.RoadDetector;
import adf.core.debug.DefaultLogger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.log4j.Logger;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

public class SampleRoadDetector extends RoadDetector {

  // 已经清通的路（清障完成后才加入，防止清障中途被打断）。
  private Set<Area> openedAreas = new HashSet<>();
  // 通信收集到的红白位置 / 目标。
  private HashSet<EntityID> heardRedWhitePositions = new HashSet<>();
  private HashSet<EntityID> heardRedWhiteTargets = new HashSet<>();
  // 红白被路障卡住时发来的清障请求（CommandPolice ACTION_CLEAR，目标=被堵的路）。
  private HashSet<EntityID> clearRequests = new HashSet<>();
  // 别的警察正在清/要去的目标（路或建筑），key=目标 area ID，value=认领警察的最小 ID。
  // 用"ID 小的赢"做确定性仲裁：ID 更小的警察保留当前目标继续走，ID 更大的让路去别的
  // 街道。这样同一条街至少有一个警察（ID 最小的那个）在走，不会互相让路卡死。
  private HashMap<EntityID, Integer> policeClaimedTargets = new HashMap<>();
  private Clustering clustering;
  private PathPlanning pathPlanning;

  private EntityID result;
  private Logger logger;

  public SampleRoadDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    logger = DefaultLogger.getLogger(agentInfo.me());
    this.pathPlanning = moduleManager.getModule(
        "SampleRoadDetector.PathPlanning",
        "adf.impl.module.algorithm.DijkstraPathPlanning");
    this.clustering = moduleManager.getModule("SampleRoadDetector.Clustering",
        "adf.impl.module.algorithm.KMeansClustring");
    registerModule(this.clustering);
    registerModule(this.pathPlanning);
    this.result = null;
  }


  @Override
  public RoadDetector updateInfo(MessageManager messageManager) {
    logger.debug("Time:" + agentInfo.getTime());
    super.updateInfo(messageManager);

    // 只把"已经清完"的路记入 openedAreas（路障消失才算清完）；
    // 反之，路障又出现（余震产生新路障）的路要移出 openedAreas，否则会永远漏清。
    for (EntityID id : this.worldInfo.getChanged().getChangedEntities()) {
      StandardEntity entity = this.worldInfo.getEntity(id);
      if (entity instanceof Road) {
        Road road = (Road) entity;
        if (!road.isBlockadesDefined() || road.getBlockades().isEmpty()) {
          this.openedAreas.add((Area) entity);
        } else {
          this.openedAreas.remove((Area) entity);
        }
      }
    }

    // 红白的广播：位置 + 目标（红白是动态实体，靠通信才能知道远处的红白）。
    this.heardRedWhitePositions.clear();
    this.heardRedWhiteTargets.clear();
    for (CommunicationMessage msg : messageManager
        .getReceivedMessageList(MessageFireBrigade.class)) {
      MessageFireBrigade m = (MessageFireBrigade) msg;
      EntityID pos = m.getPosition();
      if (pos != null) {
        this.heardRedWhitePositions.add(pos);
      }
      EntityID target = m.getTargetID();
      if (target != null) {
        this.heardRedWhiteTargets.add(target);
      }
    }
    for (CommunicationMessage msg : messageManager
        .getReceivedMessageList(MessageAmbulanceTeam.class)) {
      MessageAmbulanceTeam m = (MessageAmbulanceTeam) msg;
      EntityID pos = m.getPosition();
      if (pos != null) {
        this.heardRedWhitePositions.add(pos);
      }
      EntityID target = m.getTargetID();
      if (target != null) {
        this.heardRedWhiteTargets.add(target);
      }
    }

    // 红白被卡住时的清障请求（硬信号，最高优先级）。
    this.clearRequests.clear();
    for (CommunicationMessage msg : messageManager
        .getReceivedMessageList(CommandPolice.class)) {
      CommandPolice c = (CommandPolice) msg;
      if (c.getAction() == CommandPolice.ACTION_CLEAR && c.getTargetID() != null) {
        this.collectRoad(this.clearRequests, c.getTargetID());
      }
    }
    if (!this.clearRequests.isEmpty()) {
      logger.debug("heard clear requests: " + this.clearRequests);
    }

    // 别的警察正在清/要去的目标，用于避让，避免多个警察挤去同一条街。
    this.policeClaimedTargets.clear();
    for (CommunicationMessage msg : messageManager
        .getReceivedMessageList(MessagePoliceForce.class)) {
      MessagePoliceForce m = (MessagePoliceForce) msg;
      if (m.getAgentID().equals(this.agentInfo.getID())) {
        continue; // 自己不算
      }
      EntityID target = m.getTargetID();
      if (target == null) {
        continue;
      }
      // 清障时广播的是路障 ID，换成路障所在的路；移动/休息广播的是区域本身。
      EntityID claimed = target;
      if (m.getAction() == MessagePoliceForce.ACTION_CLEAR) {
        StandardEntity e = this.worldInfo.getEntity(target);
        if (e instanceof Blockade && ((Blockade) e).isPositionDefined()) {
          claimed = ((Blockade) e).getPosition();
        }
      }
      putMinClaim(this.policeClaimedTargets, claimed,
          m.getAgentID().getValue());
    }
    return this;
  }


  @Override
  public RoadDetector calc() {
    EntityID positionID = this.agentInfo.getPosition();
    StandardEntity currentPosition = worldInfo.getEntity(positionID);
    // 只有"当前这条路真的没有路障"才标记为已开。原来无条件加入，会把"警察卡在
    // 上面的、还有路障的路"也当成已开，于是 calcTargets 里的 nearestBlockedRoad /
    // collectBlockedRoads 直接跳过它 → RoadDetector 返回空 → 警察转去 search，
    // 又被路障挡着走不动，结果卡死在障碍物旁边（障碍物很近但没压在脚下）。
    // 注意：建筑（搜救时警察会走进去）仍无条件标记，保持原来的行为。
    boolean roadBlocked = currentPosition instanceof Road
        && ((Road) currentPosition).isBlockadesDefined()
        && !((Road) currentPosition).getBlockades().isEmpty();
    if (!roadBlocked) {
      openedAreas.add((Area) currentPosition);
    }
    if (positionID.equals(result)) {
      logger.debug("reach to " + currentPosition + " resetting target");
      this.result = null;
      // 局部清干净：清完一条路后，先把旁边还堵着的相邻路也顺手清掉（把整个路口/街区
      // 清干净再走），别清一条就跳去远处另一条请求——否则红白刚清通一条、下一栋楼门口
      // 又堵着，还得再叫一次警察，聚集点半天清不完。相邻路都清完后再回到全局优先级。
      EntityID adjacent = nearestAdjacentBlockedRoad();
      if (adjacent != null) {
        this.result = adjacent;
        logger.debug("continue clearing adjacent blocked road " + adjacent);
      }
    }

    // 让路：我的当前目标被 ID 更小的警察认领了就放弃、去别的街道；
    // ID 更小的警察不让，所以每条街至少有一个警察在走，不会互相让到卡死。
    if (this.result != null && isClaimedBySmaller(this.result)) {
      logger.debug("yield " + this.result + " claimed by smaller police");
      this.result = null;
    }

    if (this.result == null) {
      HashSet<Area> currentTargets = calcTargets();
      logger.debug("Targets: " + currentTargets);
      if (currentTargets.isEmpty()) {
        this.result = null;
        return this;
      }
      this.pathPlanning.setFrom(positionID);
      this.pathPlanning.setDestination(toEntityIds(currentTargets));
      List<EntityID> path = this.pathPlanning.calc().getResult();
      if (path != null && path.size() > 0) {
        this.result = path.get(path.size() - 1);
      }
      logger.debug("Selected Target: " + this.result);
    }
    return this;
  }


  // 优先级：被困伤员门口（检测到伤员就清障）> 清障请求（红白被卡，硬信号）
  //        > 红白绿聚集地带 > 兜底找遍地图。
  private HashSet<Area> calcTargets() {
    // 1. 检测到伤员就清障：建筑里有受伤的人、门口被堵 → 优先清门口，把困在门口的
    //    伤员放出来（这往往就是聚集点里红白反复叫警察、却一直没人去清的入口）。警察
    //    因此留在聚集点附近干活，而不是被叫去远处不重要的位置扎堆。
    HashSet<Area> trapped = calcVictimTrappedRoads();
    if (!trapped.isEmpty()) {
      return trapped;
    }
    // 2. 红白被路障卡住时发来的清障请求：一个请求一个警察。
    HashSet<Area> req = filterClearRequests();
    if (!req.isEmpty()) {
      return req;
    }
    // 3. 红白绿（消防/救护/平民）聚集地带：所有警察都优先清这些地方的路障。
    HashSet<Area> gathering = calcGatheringZoneTargets();
    if (!gathering.isEmpty()) {
      return gathering;
    }
    // 4. 兜底：自己的聚类里找其他障碍物，把地图找遍。
    return calcBackupTargets();
  }


  // 检测到伤员就清障：建筑里有受伤的人（HP>0 且受伤，含被埋的），且门口的路被路障
  // 堵住——伤员自己出不来、红白救援也进不去。警察优先清这些门口（被红白叫的过程中
  // 路过，也能顺手把这些"能自己走、却被障碍物困住"的伤员放出来）。
  private HashSet<Area> calcVictimTrappedRoads() {
    HashSet<Area> trapped = new HashSet<>();
    for (StandardEntity e : this.worldInfo.getEntitiesOfType(CIVILIAN,
        FIRE_BRIGADE, AMBULANCE_TEAM)) {
      if (!(e instanceof Human)) {
        continue;
      }
      Human h = (Human) e;
      if (!h.isHPDefined() || h.getHP() <= 0 || !h.isPositionDefined()) {
        continue;
      }
      if (!h.isDamageDefined() || h.getDamage() <= 0) {
        continue; // 只救受伤的伤员，别为了没受伤的人乱跑
      }
      StandardEntity pos = this.worldInfo.getEntity(h.getPosition());
      if (!(pos instanceof Building)) {
        continue;
      }
      for (EntityID nid : ((Area) pos).getNeighbours()) {
        StandardEntity n = this.worldInfo.getEntity(nid);
        if (!(n instanceof Road)) {
          continue;
        }
        Road r = (Road) n;
        if (r.isBlockadesDefined() && !r.getBlockades().isEmpty()
            && !this.openedAreas.contains(r)) {
          trapped.add(r);
        }
      }
    }
    removeClaimedBySmaller(trapped);
    return trapped;
  }


  // 最高优先级：红白被路障卡住时的清障请求，一个请求一个警察（认领去重），挑离自己最近的。
  //
  // 两处关键修正（SF 这张障碍物极多的图尤其重要）：
  //   1) 不要因为"这条路还没有路障信息"就丢请求——请求是卡住的红白发的，警察还没亲自
  //      看到路障不代表没路障，应该相信请求、先过去，到了自然能看到并清掉。只有"已经
  //      确认清通"（有信息且为空）才跳过。
  //   2) 不限定自己聚类：卡住的红白无论在哪，最近的警察就该过去，跨聚类也值得跑一趟。
  private HashSet<Area> filterClearRequests() {
    HashSet<Area> requests = new HashSet<>();
    for (EntityID id : this.clearRequests) {
      StandardEntity e = this.worldInfo.getEntity(id);
      if (e instanceof Road) {
        Road r = (Road) e;
        if (r.isBlockadesDefined() && r.getBlockades().isEmpty()) {
          continue; // 已经确认清通了
        }
        if (this.openedAreas.contains(r)) {
          continue;
        }
        if (this.policeClaimedTargets.containsKey(id)) {
          continue; // 别的警察已经在处理
        }
        requests.add(r);
      }
    }
    if (requests.isEmpty()) {
      return new HashSet<>();
    }
    // 挑离自己最近的那个请求（全局，不限定聚类）。
    Area nearest = null;
    int bestDist = Integer.MAX_VALUE;
    for (Area a : requests) {
      int d = this.worldInfo.getDistance(this.agentInfo.me(), a);
      if (d < bestDist) {
        bestDist = d;
        nearest = a;
      }
    }
    HashSet<Area> result = new HashSet<>();
    if (nearest != null) {
      result.add(nearest);
      logger.debug("respond clear request " + nearest);
    }
    return result;
  }


  // 兜底逻辑：目标是"必须到达的目的地"——庇护所/加油站 + 受伤人员位置（人员密集区）。
  // 警察朝这些目的地行进时会顺手清掉沿途路障，从而打通救人/送庇护所的路；
  // 目的地都通了之后，退回去清"被堵住的路"（优先自己 cluster 内，再全局），把地图找遍。
  private HashSet<Area> calcBackupTargets() {
    HashSet<Area> targetAreas = new HashSet<>();
    for (StandardEntity e : this.worldInfo.getEntitiesOfType(REFUGE,
        GAS_STATION)) {
      targetAreas.add((Area) e);
    }
    for (StandardEntity e : this.worldInfo.getEntitiesOfType(CIVILIAN,
        AMBULANCE_TEAM, FIRE_BRIGADE, POLICE_FORCE)) {
      if (isValidHuman(e)) {
        Human h = (Human) e;
        targetAreas.add((Area) worldInfo.getEntity(h.getPosition()));
      }
    }
    HashSet<Area> inClusterTarget = filterInCluster(targetAreas);
    inClusterTarget.removeAll(openedAreas);
    removeClaimedBySmaller(inClusterTarget);

    if (inClusterTarget.isEmpty()) {
      HashSet<Area> blockedRoads = collectBlockedRoads();
      inClusterTarget = filterInCluster(blockedRoads);
      if (inClusterTarget.isEmpty()) {
        inClusterTarget = blockedRoads;
      }
      inClusterTarget.removeAll(openedAreas);
      removeClaimedBySmaller(inClusterTarget);
    }

    // 兜底：上面的候选要么已被开掉、要么被别的警察认领，但地图上可能还有没清的路障。
    // 此时别休息，去清离自己最近的被堵路（忽略认领——认领只是软避让，不是硬排除），
    // 这样空闲警察会自然接手剩下的活、把地图找遍，而不是站在原地发呆。
    if (inClusterTarget.isEmpty()) {
      Area nearest = nearestBlockedRoad();
      if (nearest != null) {
        inClusterTarget.add(nearest);
      }
    }
    return inClusterTarget;
  }


  // 离自己最近的一条"还有路障"的路，给空闲警察兜底：没别的活就去清障。
  // 优先挑"没被 ID 更小警察认领"的路，避免一群空闲警察挤去同一条；全被认领时
  // 兜底还是用全局最近的那条（别让警察饿死原地发呆）。
  private Area nearestBlockedRoad() {
    int myID = this.agentInfo.getID().getValue();
    Area nearest = null;
    Area nearestFree = null;
    int bestDist = Integer.MAX_VALUE;
    int bestFreeDist = Integer.MAX_VALUE;
    for (StandardEntity e : this.worldInfo
        .getEntitiesOfType(StandardEntityURN.ROAD)) {
      if (!(e instanceof Road)) {
        continue;
      }
      Road road = (Road) e;
      if (!road.isBlockadesDefined() || road.getBlockades().isEmpty()) {
        continue;
      }
      if (this.openedAreas.contains(road)) {
        continue;
      }
      int d = this.worldInfo.getDistance(this.agentInfo.me(), road);
      if (d < bestDist) {
        bestDist = d;
        nearest = road;
      }
      Integer claimer = this.policeClaimedTargets.get(road.getID());
      if ((claimer == null || claimer >= myID) && d < bestFreeDist) {
        bestFreeDist = d;
        nearestFree = road;
      }
    }
    return nearestFree != null ? nearestFree : nearest;
  }


  // 找当前所在路旁边（含当前路）离警察最近、还堵着的路，用于"清完一条顺手清相邻"。
  // 只扫直接相邻的路（一个路口/街区的范围），不会越扫越远。
  private EntityID nearestAdjacentBlockedRoad() {
    StandardEntity pos = this.worldInfo.getEntity(this.agentInfo.getPosition());
    if (!(pos instanceof Area)) {
      return null;
    }
    EntityID best = null;
    int bestDist = Integer.MAX_VALUE;
    List<EntityID> candidates = new ArrayList<>();
    candidates.add(pos.getID());
    for (EntityID nid : ((Area) pos).getNeighbours()) {
      candidates.add(nid);
    }
    for (EntityID cid : candidates) {
      StandardEntity e = this.worldInfo.getEntity(cid);
      if (!(e instanceof Road)) {
        continue;
      }
      Road r = (Road) e;
      if (!r.isBlockadesDefined() || r.getBlockades().isEmpty()) {
        continue;
      }
      if (this.openedAreas.contains(r)) {
        continue;
      }
      int d = this.worldInfo.getDistance(this.agentInfo.me(), r);
      if (d < bestDist) {
        bestDist = d;
        best = cid;
      }
    }
    return best;
  }


  // 红白绿（消防/救护/平民）聚集地带：通信听到的红白位置/目标 + 直接感知到的红白绿
  // 当前位置，转成它们所在/目标的那条路，提前去清入口路障（别等真把人堵住）。
  private HashSet<Area> calcGatheringZoneTargets() {
    HashSet<Area> gatheringRoads = new HashSet<>();
    for (EntityID pos : this.heardRedWhitePositions) {
      addGatheringRoad(gatheringRoads, pos);
    }
    for (EntityID target : this.heardRedWhiteTargets) {
      addGatheringRoad(gatheringRoads, target);
    }
    for (StandardEntity e : this.worldInfo.getEntitiesOfType(FIRE_BRIGADE,
        AMBULANCE_TEAM, CIVILIAN)) {
      Human h = (Human) e;
      if (h.isHPDefined() && h.getHP() > 0 && h.isPositionDefined()) {
        addGatheringRoad(gatheringRoads, h.getPosition());
      }
    }
    gatheringRoads.removeAll(this.openedAreas);
    removeClaimedBySmaller(gatheringRoads);
    // 只留"还堵着"的路：别让警察白跑到一条根本没堵的路去——paris 这种只有 10 个警察、
    // 红白却有 74 个，警察白跑一趟就少清一条真正堵的路，红白就多卡一会。只有"确认有
    // 路障且没清完"的路才值得去清。
    gatheringRoads.removeIf(r -> {
      if (!(r instanceof Road)) {
        return true;
      }
      Road road = (Road) r;
      return !road.isBlockadesDefined() || road.getBlockades().isEmpty();
    });
    return gatheringRoads;
  }


  // 收集所有"有路障的路"。
  private HashSet<Area> collectBlockedRoads() {
    HashSet<Area> blockedRoads = new HashSet<>();
    for (StandardEntity e : this.worldInfo
        .getEntitiesOfType(StandardEntityURN.ROAD)) {
      if (e instanceof Road) {
        Road road = (Road) e;
        if (road.isBlockadesDefined() && !road.getBlockades().isEmpty()) {
          blockedRoads.add(road);
        }
      }
    }
    return blockedRoads;
  }


  // 把红白绿的位置或目标，转成警察能清的"路"：是路直接加；是建筑换成它门口的路。
  private void addGatheringRoad(HashSet<Area> targets, EntityID id) {
    if (id == null) {
      return;
    }
    StandardEntity e = this.worldInfo.getEntity(id);
    if (e instanceof Road) {
      targets.add((Road) e);
    } else if (e instanceof Building) {
      for (EntityID nid : ((Area) e).getNeighbours()) {
        StandardEntity n = this.worldInfo.getEntity(nid);
        if (n instanceof Road) {
          targets.add((Road) n);
        }
      }
    }
  }




  // 去掉被"ID 更小"的警察认领的路（ID 小的赢），避免多个警察挤去同一条路；
  // ID 更大的警察不在这步让路，它们自己会去别处，保证至少有一个警察在走。
  private void removeClaimedBySmaller(HashSet<Area> roads) {
    int myID = this.agentInfo.getID().getValue();
    roads.removeIf(r -> {
      Integer claimer = this.policeClaimedTargets.get(r.getID());
      return claimer != null && claimer < myID;
    });
  }


  // 认领者取 ID 较小的那个（确定性仲裁：ID 小的警察优先保留目标）。
  private void putMinClaim(HashMap<EntityID, Integer> map, EntityID key,
      int claimer) {
    Integer old = map.get(key);
    if (old == null || claimer < old) {
      map.put(key, claimer);
    }
  }


  // 目标是否被 ID 更小的警察认领了（是则我让路）。
  private boolean isClaimedBySmaller(EntityID target) {
    Integer claimer = this.policeClaimedTargets.get(target);
    return claimer != null && claimer < this.agentInfo.getID().getValue();
  }


  // 把一个目标（路/建筑）转成"路"记入集合（用于红白清障请求）。
  private void collectRoad(HashSet<EntityID> set, EntityID id) {
    if (id == null) {
      return;
    }
    StandardEntity e = this.worldInfo.getEntity(id);
    if (e instanceof Road) {
      set.add(id);
    } else if (e instanceof Building) {
      for (EntityID nid : ((Area) e).getNeighbours()) {
        StandardEntity n = this.worldInfo.getEntity(nid);
        if (n instanceof Road) {
          set.add(nid);
        }
      }
    }
  }


  private HashSet<Area> filterInCluster(HashSet<Area> targetAreas) {
    int clusterIndex = clustering.getClusterIndex(this.agentInfo.getID());
    if (clusterIndex < 0) {
      return new HashSet<>();
    }
    HashSet<Area> clusterTargets = new HashSet<>();
    HashSet<StandardEntity> inCluster = new HashSet<>(
        clustering.getClusterEntities(clusterIndex));
    for (Area target : targetAreas) {
      if (inCluster.contains(target))
        clusterTargets.add(target);
    }
    return clusterTargets;
  }


  private Collection<EntityID>
      toEntityIds(Collection<? extends StandardEntity> entities) {
    ArrayList<EntityID> eids = new ArrayList<>();
    for (StandardEntity standardEntity : entities) {
      eids.add(standardEntity.getID());
    }
    return eids;
  }


  @Override
  public EntityID getTarget() {
    return this.result;
  }


  private boolean isValidHuman(StandardEntity entity) {
    if (entity == null)
      return false;
    if (!(entity instanceof Human))
      return false;

    Human target = (Human) entity;
    if (!target.isHPDefined() || target.getHP() == 0)
      return false;
    if (!target.isPositionDefined())
      return false;
    if (!target.isDamageDefined() || target.getDamage() == 0)
      return false;
    if (!target.isBuriednessDefined())
      return false;

    StandardEntity position = worldInfo.getPosition(target);
    if (position == null)
      return false;

    StandardEntityURN positionURN = position.getStandardURN();
    if (positionURN == REFUGE || positionURN == AMBULANCE_TEAM)
      return false;

    return true;
  }
}
