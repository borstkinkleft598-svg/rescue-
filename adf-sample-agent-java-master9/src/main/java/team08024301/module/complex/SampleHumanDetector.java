package team08024301.module.complex;

import static rescuecore2.standard.entities.StandardEntityURN.AMBULANCE_TEAM;
import static rescuecore2.standard.entities.StandardEntityURN.CIVILIAN;
import static rescuecore2.standard.entities.StandardEntityURN.FIRE_BRIGADE;
import static rescuecore2.standard.entities.StandardEntityURN.POLICE_FORCE;
import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.information.MessageAmbulanceTeam;
import adf.core.agent.communication.standard.bundle.information.MessageCivilian;
import adf.core.agent.communication.standard.bundle.information.MessageFireBrigade;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.complex.HumanDetector;
import adf.core.debug.DefaultLogger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import org.apache.log4j.Logger;
import rescuecore2.standard.entities.AmbulanceTeam;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

public class SampleHumanDetector extends HumanDetector {

  private Clustering clustering;

  // 自己是红（消防员）还是白（救护车）：红只能挖（buriedness>0），白只能运（buriedness==0）。
  private StandardEntityURN selfType;

  // 认领关系：目标（伤员本人或其所在建筑）-> 认领者的 agent ID（数值）。
  // 同一个目标被多个同色红白认领时 ID 小的赢，保证"一个伤员只被一个红/白认领"。
  private HashMap<EntityID, Integer> claimedBy = new HashMap<>();

  // 自身卡住检测：记录上次位置与移动时间，位置长时间不变说明被堵住/目标不可达。
  private EntityID lastPosition;
  private int lastMovedTime;
  private static final int STUCK_TICKS = 5;

  // 备份梯队（rank%3==1）转搜救的门限：无人认领的伤员超过这个数就加入搜救。
  // 聚集点刚被发现时伤员多 → 备份一起挖；伤员少 → 备份继续探索，避免扎堆。
  private static final int BACKUP_TRIGGER = 3;

  // 后备梯队（rank%3==2）转搜救的门限：只有伤员特别多（大聚集点爆发）时才全员压上，
  // 平时这批人继续搜索，保证还能发现新的聚集点。
  private static final int LAST_RESORT_TRIGGER = 8;

  // 搜索组（备份/后备梯队）空转太久的兜底：连续搜索了这么久还没找到新伤员，就去支援
  // 现有的伤员（有伤员但没超门限时也去），别让聚集点里的人一直干等。选到伤员后清零。
  private int searchingSince = -1;
  private static final int SEARCH_TIMEOUT = 120;

  // 暂时放弃的目标（被堵/不可达），记下放弃时刻，一段时间内不再选它。
  private HashMap<EntityID, Integer> abandoned = new HashMap<>();
  private static final int ABANDON_TICKS = 30;

  // 白专用：红正在挖的救援位置（建筑）。白没有可运的伤员时，过去接应（红挖完 → 白立刻装）。
  private HashSet<EntityID> redRescueBuildings = new HashSet<>();

  // 已经广播过的伤员（本人 ID）。发现新伤员时全局广播一次，之后不再重复，
  // 让远处"全局搜索"的红白也能知道这里有人要救，从而转过来一起搜救。
  private HashSet<EntityID> broadcastVictims = new HashSet<>();

  // 卡住/被堵时向警察发清障请求（CommandPolice ACTION_CLEAR），带节流避免每 tick 刷屏。
  private static final int REQUEST_INTERVAL = 10;
  private MessageManager messageManager;
  private int lastRequestTime = -REQUEST_INTERVAL;

  private EntityID result;

  private Logger logger;

  public SampleHumanDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    logger = DefaultLogger.getLogger(agentInfo.me());
    this.selfType = agentInfo.me().getStandardURN();
    this.clustering = moduleManager.getModule("SampleHumanDetector.Clustering",
        "adf.impl.module.algorithm.KMeansClustering");
    registerModule(this.clustering);
  }


  @Override
  public HumanDetector updateInfo(MessageManager messageManager) {
    logger.debug("Time:" + agentInfo.getTime());
    super.updateInfo(messageManager);
    this.messageManager = messageManager;

    // 发现新伤员 → 全局广播（MessageCivilian），让远处"全局搜索"的红白也知道这里有人要救，
    // 从而转过来一起搜救（这是"当有人发现伤员时全局通信"的落实）。
    broadcastNewVictims();

    // 通过通信收集"同类型"队友正在救/运的目标，用于分工避免扎堆。
    // 只认自己这一拨人：红只读 MessageFireBrigade、白只读 MessageAmbulanceTeam 做认领，
    // 避免红白互相污染认领图（红挖埋着的、白运挖出来的，目标本就不重叠）。
    this.claimedBy.clear();
    this.redRescueBuildings.clear();
    EntityID myID = this.agentInfo.getID();
    if (this.selfType == FIRE_BRIGADE) {
      for (CommunicationMessage msg : messageManager
          .getReceivedMessageList(MessageFireBrigade.class)) {
        MessageFireBrigade m = (MessageFireBrigade) msg;
        if (!m.getSenderID().equals(myID)) {
          recordClaimer(m.getTargetID(), m.getSenderID());
        }
      }
    } else {
      for (CommunicationMessage msg : messageManager
          .getReceivedMessageList(MessageAmbulanceTeam.class)) {
        MessageAmbulanceTeam m = (MessageAmbulanceTeam) msg;
        if (!m.getSenderID().equals(myID)) {
          recordClaimer(m.getTargetID(), m.getSenderID());
        }
      }
      // 白额外听红的 ACTION_RESCUE 广播：红正在挖的建筑就是"马上有伤员可运"的地方，
      // 记下来，白没伤员可运时过去接应。
      for (CommunicationMessage msg : messageManager
          .getReceivedMessageList(MessageFireBrigade.class)) {
        MessageFireBrigade m = (MessageFireBrigade) msg;
        if (m.getAction() == MessageFireBrigade.ACTION_RESCUE
            && m.getTargetID() != null) {
          StandardEntity e = this.worldInfo.getEntity(m.getTargetID());
          if (e instanceof Human) {
            Human h = (Human) e;
            if (h.isPositionDefined()) {
              this.redRescueBuildings.add(h.getPosition());
            }
          } else if (e instanceof Area) {
            this.redRescueBuildings.add(m.getTargetID());
          }
        }
      }
    }
    return this;
  }


  // 把"我看到的受伤平民"广播出去：每个伤员只广播一次（用 broadcastVictims 去重），
  // 让远处全局搜索的红白也能知道这里有人要救。收到的人会在 MessageTool.reflectMessage
  // 里把 MessageCivilian 反射进自己的 worldInfo，从而在 calcTarget 里看到这个伤员。
  private void broadcastNewVictims() {
    for (StandardEntity e : this.worldInfo.getEntitiesOfType(CIVILIAN)) {
      if (!(e instanceof Civilian)) {
        continue;
      }
      Civilian c = (Civilian) e;
      // 只广播"受伤"（damage>0）且知道位置的平民——那才是需要救的伤员。
      if (!c.isDamageDefined() || c.getDamage() <= 0) {
        continue;
      }
      if (!c.isPositionDefined()) {
        continue;
      }
      if (this.broadcastVictims.contains(c.getID())) {
        continue;
      }
      this.broadcastVictims.add(c.getID());
      this.messageManager.addMessage(new MessageCivilian(true, c));
    }
  }


  // 记下"谁认领了哪个目标"：只认领伤员本人，不再认领它所在的整栋建筑。
  // 之前同时认领建筑会把"一栋楼里埋了很多人"错误地当成"这栋楼已被认领"，
  // 其它红全让位，结果一栋楼只有一个人去挖（建筑认领还顺带废掉了三分原则的升级：
  // 未认领伤员数被压成 ~0，备份/后备梯队"伤员超阈值就压上"的条件永远不触发）。
  // 同一个目标被多个同色认领时，ID 最小的认领者赢（putMinClaim），保证确定性。
  private void recordClaimer(EntityID targetID, EntityID claimerID) {
    if (targetID == null || claimerID == null) {
      return;
    }
    int claimer = claimerID.getValue();
    putMinClaim(this.claimedBy, targetID, claimer);
  }


  // 认领者取 ID 较小的那个（确定性分工）。
  private void putMinClaim(HashMap<EntityID, Integer> map, EntityID key,
      int claimer) {
    Integer old = map.get(key);
    if (old == null || claimer < old) {
      map.put(key, claimer);
    }
  }


  @Override
  public HumanDetector calc() {
    Human transportHuman = this.agentInfo.someoneOnBoard();
    if (transportHuman != null) {
      logger.debug("someoneOnBoard:" + transportHuman);
      this.result = transportHuman.getID();
      return this;
    }
    // 自身卡住检测：位置长时间不变 → 放弃当前目标换一个（可能被路障堵住/目标不可达）。
    detectSelfStuck();
    if (this.result != null) {
      StandardEntity targetEntity = this.worldInfo.getEntity(this.result);
      if (targetEntity instanceof Human) {
        Human target = (Human) targetEntity;
        if (!isValidHuman(target) || !isMyKindOfTarget(target)) {
          // 目标死了/不见了/或者已经不用自己管了（红挖完变白运、白已运走），换下一个。
          logger.debug("Done Human:" + target + " ==>reset target");
          this.result = null;
        } else if (!isReachable(target)) {
          // 目标门口被路障堵住：留着这个目标（继续走过去、在门口等警察清障），
          // 别急着重置去全局搜索——否则"一栋楼里埋好几个人"会变成挖一个、出去
          // 找一圈、再回来挖，白白浪费时间。真卡住时 detectSelfStuck 会按邻居
          // 路障发请求并换目标。
          requestClear(target.getPosition());
        }
      } else {
        // 目标实体不存在或已不是人（正常只会有 Human 或 null），重置换下一个。
        this.result = null;
      }
    }
    if (this.result == null) {
      this.result = calcTarget();
    }
    // 白：每个 tick 都广播"我当前要运的这个伤员"（尽早认领），别的白听到就知道
    // "这伤员已经有人去运了"，不会一堆白挤向同一个伤员——实现"一个建筑 5 个伤员
    // 就来 5 个白分别运走、1 个伤员只有 1 个白去"。红没这个问题（挖埋着的，
    // tactics 每 tick 已用 ACTION_RESCUE 广播伤员本人，认领足够早）。
    if (this.selfType == AMBULANCE_TEAM && this.result != null
        && this.messageManager != null) {
      StandardEntity e = this.worldInfo.getEntity(this.result);
      if (e instanceof Human) {
        this.messageManager.addMessage(new MessageAmbulanceTeam(true,
            (AmbulanceTeam) this.agentInfo.me(),
            MessageAmbulanceTeam.ACTION_RESCUE, this.result));
      }
    }
    return this;
  }


  // 自身位置检测：位置一直不变（被路障堵住/目标不可达）就放弃当前目标。
  // 已经到目标身边（正在挖/运，站着不动是正常的）不算卡住。
  private void detectSelfStuck() {
    EntityID pos = this.agentInfo.getPosition();
    int time = this.agentInfo.getTime();
    if (this.lastPosition != null && this.lastPosition.equals(pos)) {
      if (!isAtTarget() && time - this.lastMovedTime >= STUCK_TICKS) {
        logger.debug("Self stuck at " + pos + ", abandon target:" + this.result);
        requestClearStuck(pos);
        abandon(this.result);
        this.result = null;
        this.lastMovedTime = time;
      }
    } else {
      this.lastMovedTime = time;
    }
    this.lastPosition = pos;
  }


  // 是否已经站在当前目标身边（伤员所在位置或目标建筑）。
  private boolean isAtTarget() {
    if (this.result == null) {
      return false;
    }
    EntityID pos = this.agentInfo.getPosition();
    StandardEntity t = this.worldInfo.getEntity(this.result);
    if (t instanceof Human) {
      Human h = (Human) t;
      return h.isPositionDefined() && h.getPosition().equals(pos);
    }
    return t != null && t.getID().equals(pos);
  }


  // 把一个目标记入"暂时放弃"，一段时间内不再选它。
  private void abandon(EntityID targetID) {
    if (targetID == null) {
      return;
    }
    this.abandoned.put(targetID, this.agentInfo.getTime());
  }


  // 卡住/被堵时向警察发清障请求：目标 = 被堵的路 / 伤员所在建筑（警察侧会转成其门口路）。
  // 带节流（REQUEST_INTERVAL tick 最多发一次），警察侧已按 rank 摊派且会去重，重复发无害。
  private void requestClear(EntityID targetID) {
    if (this.messageManager == null || targetID == null) {
      return;
    }
    int time = this.agentInfo.getTime();
    if (time - this.lastRequestTime < REQUEST_INTERVAL) {
      return;
    }
    this.lastRequestTime = time;
    logger.debug("REQ_CLEAR target=" + targetID);
    this.messageManager.addMessage(new CommandPolice(true, null, targetID,
        CommandPolice.ACTION_CLEAR));
  }


  // 从一批"进不去"的伤员里挑最近的一个，叫警察去清它门口的路（节流见 requestClear）。
  private void requestClearNearest(List<Human> humans) {
    List<Human> copy = new ArrayList<>(humans);
    copy.sort(new DistanceSorter(this.worldInfo, this.agentInfo.me()));
    for (Human h : copy) {
      if (h.isPositionDefined()) {
        requestClear(h.getPosition());
        return;
      }
    }
  }


  // 卡住时向警察请求清障。关键：不能只请求"我站的位置"——我卡住通常不是脚下的路有路障，
  // 而是邻居路被堵住过不去，而我脚下这条路往往根本没路障，警察侧会因为"这条路没路障"把
  // 请求丢掉。所以这里改找"邻居里真正有路障的路"去请求；看不到邻居路障时才兜底请求自己。
  private void requestClearStuck(EntityID pos) {
    StandardEntity e = this.worldInfo.getEntity(pos);
    if (!(e instanceof Area)) {
      requestClear(pos);
      return;
    }
    // 脚下这条路/建筑本身就有路障（出生在路障里）→ 直接请求清自己。
    if (e instanceof Road) {
      Road r = (Road) e;
      if (r.isBlockadesDefined() && !r.getBlockades().isEmpty()) {
        requestClear(pos);
        return;
      }
    }
    // 否则找邻居里"可能有路障"的路，清最近的一条——清掉一条就能动起来。
    // 关键：只跳过"已经确认清通"的邻居；"还没确认有没有路障"的邻居也要纳入候选——
    // 卡住时真正的路障往往在我视线盲区（被建筑挡、太远），我看到的邻居可能显示
    // isBlockadesDefined()==false，但它恰恰是挡住我的那条路。警察侧已改为"相信请求、
    // 没确认也过去看"，所以发这种"未确认"的邻居请求警察也会去清。
    EntityID best = null;
    int bestDist = Integer.MAX_VALUE;
    for (EntityID nid : ((Area) e).getNeighbours()) {
      StandardEntity n = this.worldInfo.getEntity(nid);
      if (!(n instanceof Road)) {
        continue;
      }
      Road r = (Road) n;
      if (r.isBlockadesDefined() && r.getBlockades().isEmpty()) {
        continue; // 确认清通了才跳过
      }
      int d = this.worldInfo.getDistance(this.agentInfo.me(), r);
      if (d < bestDist) {
        bestDist = d;
        best = nid;
      }
    }
    if (best != null) {
      requestClear(best);
    } else {
      // 兜底：确实没有任何候选邻居（都被确认清通），仍请求自己的位置，至少发出信号。
      requestClear(pos);
    }
  }


  private EntityID calcTarget() {
    // 自己在同类型里的序号（0..N-1），用来做"三分原则"和确定性摊派。
    int rank = this.clustering.getClusterIndex(this.agentInfo.getID());
    if (rank < 0) {
      rank = Math.abs(this.agentInfo.getID().getValue());
    }
    int clusterNumber = this.clustering.getClusterNumber();

    // 三分原则（三级弹性）：
    //   rank%3==0 —— 主力搜救（1/3），一直认领伤员；
    //   rank%3==1 —— 备份搜救（1/3），默认搜索，伤员超过 BACKUP_TRIGGER 就加入；
    //   rank%3==2 —— 后备搜救（1/3），默认搜索，只有伤员特别多（大聚集点）才全员压上。
    // 这样伤员少 → 1/3 救 + 2/3 搜；伤员变多 → 2/3 救；大聚集点爆发 → 全员救。
    int tier = rank % 3;

    // 全局搜救序号：主力 0..(N/3-1)、备份 N/3..(2N/3-1)、后备 2N/3..(N-1)，
    // 三段连续不重叠，同一批伤员不会被主力 0 号、备份 0 号、后备 0 号同时选走。
    int eagerCount = (clusterNumber + 2) / 3; // ceil(N/3)
    int rescuerIndex;
    if (tier == 0) {
      rescuerIndex = rank / 3;
    } else if (tier == 1) {
      rescuerIndex = eagerCount + (rank - 1) / 3;
    } else {
      rescuerIndex = 2 * eagerCount + (rank - 2) / 3;
    }

    // 红（消防员）除了平民，还要能挖被掩埋的警察/消防/救护车（同行被埋时也能救）；
    // 白（救护车）仍只运平民——默认的装车动作只认 CIVILIAN，别让白去装一个装不动的警察。
    Collection<StandardEntity> sources = this.worldInfo
        .getEntitiesOfType(CIVILIAN);
    if (this.selfType == FIRE_BRIGADE) {
      sources = this.worldInfo.getEntitiesOfType(CIVILIAN, POLICE_FORCE,
          FIRE_BRIGADE, AMBULANCE_TEAM);
    }
    List<Human> rescueTargets = filterRescueTargets(sources);

    if (rescueTargets.isEmpty()) {
      // 白：没有可运的伤员，过去接应最近正在挖人的红（红挖完 → 白立刻装）。
      if (this.selfType != FIRE_BRIGADE) {
        EntityID assist = nearestRedRescueBuilding();
        if (assist != null) {
          logger.debug("No victim; assist red at building:" + assist);
          return assist;
        }
      }
      logger.debug("Targets:[]");
      return null;
    }

    // 搜救员：全局认领伤员（不再限定自己 cluster，配合搜索的 2/3 把地图铺开）。
    List<Human> base = rescueTargets;

    // 放弃过（被堵/不可达）的目标先剔除，但全被放弃时兜底还是用原来的。
    List<Human> notAbandoned = filterNotAbandoned(base);
    if (!notAbandoned.isEmpty()) {
      base = notAbandoned;
    }

    // 分工：优先选"没人认领且门口进得去"的伤员。
    // 全进不去 → 别硬选去扎堆，叫警察清障、自己继续探索（扎堆根因）；
    // 全被认领 → 让位去探索，别回退重选挤同一个伤员（一人挖一个就够，其他人接着探索）。
    List<Human> free = filterNotClaimed(base);
    List<Human> freeReachable = filterReachable(free);

    // 备份梯队（rank%3==1）默认搜索：只有当"没人认领的伤员"超过门限（聚集点刚被发现、
    // 主力忙不过来）时才临时加入搜救；伤员少就继续探索，避免一堆人挤在同一个聚集点。
    // 兜底：连续搜索了 SEARCH_TIMEOUT tick 还没找到新伤员，就去支援现有伤员（有伤员但
    // 没超门限时也去），别让搜索组一直空转、聚集点里的人一直干等。
    int time = this.agentInfo.getTime();
    if (tier == 1 && free.size() <= BACKUP_TRIGGER) {
      if (!free.isEmpty() && this.searchingSince >= 0
          && time - this.searchingSince >= SEARCH_TIMEOUT) {
        logger.debug("backup search timeout -> join rescue, free="
            + free.size());
      } else {
        if (this.searchingSince < 0) {
          this.searchingSince = time;
        }
        logger.debug("Targets:[] (backup searching, free=" + free.size() + ")");
        return null;
      }
    }
    // 后备梯队（rank%3==2）平时搜索；伤员特别多（大聚集点爆发）才全员压上一起挖。
    if (tier == 2 && free.size() <= LAST_RESORT_TRIGGER) {
      if (!free.isEmpty() && this.searchingSince >= 0
          && time - this.searchingSince >= SEARCH_TIMEOUT) {
        logger.debug("reserve search timeout -> join rescue, free="
            + free.size());
      } else {
        if (this.searchingSince < 0) {
          this.searchingSince = time;
        }
        logger.debug("Targets:[] (reserve searching, free=" + free.size() + ")");
        return null;
      }
    }

    List<Human> selected;
    if (!freeReachable.isEmpty()) {
      selected = freeReachable;
    } else if (!free.isEmpty()) {
      // 有伤员但门口全被路障堵住：别返回 null 去全局搜索（会越走越远，导致"挖一个、
      // 出去找一圈、再回来挖"），而是把这些堵住的伤员当作候选，挑一个走过去在门口等
      // 警察清障——警察一通立刻进去继续挖。下面的 sort+偏移会把不同搜救员摊到不同的
      // 堵住伤员上，不会都扎到同一个门口。
      selected = free;
      requestClearNearest(free);
    } else {
      logger.debug("Targets:[] (all claimed)");
      return null;
    }

    logger.debug("Targets:" + selected);
    // 优先级：先救被埋的消防员（多一个挖人的）、再救警察（多一个清障的）、再救护车、
    // 最后平民。同一优先级内再按距离排。这样搜救序号靠前的搜救员会先拿到高优先级伤员。
    selected.sort(new RescuePrioritySorter(this.worldInfo, this.agentInfo.me()));

    // 就近清空：如果我现在正站在这栋楼里、楼里还有要我挖/运的伤员，先把它挖完再走，
    // 别按全局序号被摊派到另一栋楼去——否则"一栋楼里埋好几个人"会变成挖一个就跑，
    // 剩下的人要等别的红慢慢赶来。楼里多个伤员时，用序号在楼里的多个红之间摊派。
    List<Human> sameBuilding = new ArrayList<>();
    EntityID myPosition = this.agentInfo.getPosition();
    for (Human v : selected) {
      if (v.isPositionDefined() && v.getPosition().equals(myPosition)) {
        sameBuilding.add(v);
      }
    }
    Human chosen;
    if (!sameBuilding.isEmpty()) {
      chosen = sameBuilding.get(rescuerIndex % sameBuilding.size());
    } else {
      // 确定性摊派：按"第几个搜救员"做偏移，让不同搜救员选不同的伤员。
      chosen = selected.get(rescuerIndex % selected.size());
    }
    this.searchingSince = -1; // 选到伤员、进入搜救了，清空"空转搜索"计时
    logger.debug("Selected:" + chosen);
    return chosen.getID();
  }


  // 分工：剔除"被 ID 更小的别人认领"的伤员（伤员本人或其所在建筑被认领都算）。
  private List<Human> filterNotClaimed(List<Human> targets) {
    List<Human> free = new ArrayList<>();
    for (Human h : targets) {
      if (isClaimedByOther(h))
        continue;
      free.add(h);
    }
    return free;
  }


  // 别人认领了该伤员我是否让位。只查伤员本人，不再查它所在的建筑——
  // 建筑级认领会把"一栋楼里埋了很多人"误判成"已被认领"，导致一栋楼只去一个红。
  // 认领权用"就近原则"：谁离伤员近谁去挖。否则会出现"伤员就在我脚下这栋楼里，
  // 我却要让给远处还在赶路的队友（他 ID 更小），害这栋楼的人要等半天才被挖完"。
  // 距离相同时（两个红都站在这栋楼里）才退回 ID 小者赢，保证确定性。
  private boolean isClaimedByOther(Human h) {
    int myID = this.agentInfo.getID().getValue();
    Integer c = this.claimedBy.get(h.getID());
    if (c == null || c >= myID) {
      return false;
    }
    if (h.isPositionDefined()) {
      StandardEntity claimer = this.worldInfo.getEntity(new EntityID(c));
      if (claimer instanceof Human) {
        if (h.getPosition().equals(this.agentInfo.getPosition())) {
          return false; // 我正站在这栋楼里，距离 0，肯定我先挖
        }
        Human claimerHuman = (Human) claimer;
        if (claimerHuman.isPositionDefined()) {
          int myDist = this.worldInfo.getDistance(this.agentInfo.me(), h);
          int theirDist = this.worldInfo.getDistance(claimerHuman, h);
          if (myDist < theirDist) {
            return false; // 我离得更近，我先挖
          }
        }
      }
    }
    return true;
  }


  // 剔除"暂时放弃"（被堵/不可达）的伤员。
  private List<Human> filterNotAbandoned(List<Human> targets) {
    int time = this.agentInfo.getTime();
    List<Human> kept = new ArrayList<>();
    for (Human h : targets) {
      Integer t = this.abandoned.get(h.getID());
      if (t != null && time - t < ABANDON_TICKS)
        continue;
      if (h.isPositionDefined()) {
        t = this.abandoned.get(h.getPosition());
        if (t != null && time - t < ABANDON_TICKS)
          continue;
      }
      kept.add(h);
    }
    return kept;
  }


  // 剔除"门口被路障堵住"的伤员。
  private List<Human> filterReachable(List<Human> targets) {
    List<Human> reachable = new ArrayList<>();
    for (Human h : targets) {
      if (isReachable(h))
        reachable.add(h);
    }
    return reachable;
  }


  // 伤员埋在某栋建筑里，判断"现在进不进得去"：
  // 已经站进这栋楼里（正在挖/运）就肯定进得去；否则只要还有一条入口路没被堵就能进，
  // 只有所有入口路都被堵住才认为"暂时进不去"（原来任一入口被堵就误伤整栋楼）。
  private boolean isReachable(Human h) {
    if (!h.isPositionDefined())
      return true;
    StandardEntity pos = this.worldInfo.getPosition(h);
    if (!(pos instanceof Building))
      return true;
    if (pos.getID().equals(this.agentInfo.getPosition()))
      return true;
    boolean hasRoad = false;
    for (EntityID nid : ((Area) pos).getNeighbours()) {
      StandardEntity n = this.worldInfo.getEntity(nid);
      if (n instanceof Road) {
        hasRoad = true;
        Road r = (Road) n;
        if (!(r.isBlockadesDefined() && !r.getBlockades().isEmpty())) {
          return true;
        }
      }
    }
    return !hasRoad;
  }


  // 白：挑一个最近的红救援位置（建筑）过去接应。
  private EntityID nearestRedRescueBuilding() {
    EntityID best = null;
    int bestDist = Integer.MAX_VALUE;
    for (EntityID buildingID : this.redRescueBuildings) {
      StandardEntity e = this.worldInfo.getEntity(buildingID);
      if (!(e instanceof Area)) {
        continue;
      }
      int d = this.worldInfo.getDistance(this.agentInfo.me(), e);
      if (d < bestDist) {
        bestDist = d;
        best = buildingID;
      }
    }
    return best;
  }


  @Override
  public EntityID getTarget() {
    return this.result;
  }


  private List<Human>
      filterRescueTargets(Collection<? extends StandardEntity> list) {
    List<Human> rescueTargets = new ArrayList<>();
    for (StandardEntity next : list) {
      if (!(next instanceof Human))
        continue;
      Human h = (Human) next;
      if (!isValidHuman(h))
        continue;
      boolean buried = h.isBuriednessDefined() && h.getBuriedness() > 0;
      // 红只能挖还埋着的（buriedness>0），白只能运已经挖出来的（buriedness==0）。
      if (this.selfType == FIRE_BRIGADE) {
        if (!buried)
          continue;
      } else {
        if (buried)
          continue;
      }
      rescueTargets.add(h);
    }
    return rescueTargets;
  }


  // 这个伤员现在是不是"归我管"：红管埋着的（buriedness>0，要挖），
  // 白管已经挖出来的（buriedness==0，要运）。目标不再归我管时重置、重新选。
  private boolean isMyKindOfTarget(Human h) {
    boolean buried = h.isBuriednessDefined() && h.getBuriedness() > 0;
    return this.selfType == FIRE_BRIGADE ? buried : !buried;
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


  private class DistanceSorter implements Comparator<StandardEntity> {

    private StandardEntity reference;
    private WorldInfo worldInfo;

    DistanceSorter(WorldInfo wi, StandardEntity reference) {
      this.reference = reference;
      this.worldInfo = wi;
    }


    public int compare(StandardEntity a, StandardEntity b) {
      int d1 = this.worldInfo.getDistance(this.reference, a);
      int d2 = this.worldInfo.getDistance(this.reference, b);
      return d1 - d2;
    }
  }


  // 搜救优先级：消防员(0) > 警察(1) > 救护车(2) > 平民(3)。救回一个消防员能多一个挖人的，
  // 救回一个警察能多一个清障的，越早救回报酬越高，所以排到平民前面。
  private int rescuePriority(Human h) {
    StandardEntityURN urn = h.getStandardURN();
    if (urn == FIRE_BRIGADE) {
      return 0;
    }
    if (urn == POLICE_FORCE) {
      return 1;
    }
    if (urn == AMBULANCE_TEAM) {
      return 2;
    }
    return 3;
  }


  private class RescuePrioritySorter implements Comparator<StandardEntity> {

    private StandardEntity reference;
    private WorldInfo worldInfo;

    RescuePrioritySorter(WorldInfo wi, StandardEntity reference) {
      this.reference = reference;
      this.worldInfo = wi;
    }


    public int compare(StandardEntity a, StandardEntity b) {
      int p1 = rescuePriority((Human) a);
      int p2 = rescuePriority((Human) b);
      if (p1 != p2) {
        return p1 - p2;
      }
      int d1 = this.worldInfo.getDistance(this.reference, a);
      int d2 = this.worldInfo.getDistance(this.reference, b);
      return d1 - d2;
    }
  }
}
