package team08024301.extraction;

import adf.core.agent.action.Action;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.police.ActionClear;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.extaction.ExtAction;
import adf.impl.extaction.DefaultExtActionClear;
import java.util.Arrays;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.PoliceForce;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.worldmodel.EntityID;

/**
 * 在官方 DefaultExtActionClear 之上加一层"自救"：如果警察自己正站在某个路障里
 * （出生点刚好落在路障多边形内），先把脚下这个路障用 AKClear 直接清掉。
 *
 * 原因：警察站在路障里时，服务端 traffic 模拟器会把它标记为 immobile（动不了），
 * 而默认清障逻辑只会对"远处的目标路"发 AKClearArea，那个矩形清障区是朝远处打的，
 * 够不到自己脚下的路障，于是这个警察会一直卡在原地直到仿真结束。
 * AKClear（按具体路障 ID 清）会直接扣该路障的 repair cost，扣完整个路障消失，
 * 警察就能走出来继续干活。
 */
public class SampleExtActionClear extends DefaultExtActionClear {

  // 卡住检测：警察位置连续多拍不变，说明被路障挡着走不动了（不是正常清障——清障时
  // result 是 ActionClear，不走这个分支）。
  private EntityID lastPosition;
  private int stuckCount;
  private static final int STUCK_TICKS = 5;

  public SampleExtActionClear(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
  }


  @Override
  public ExtAction calc() {
    // 最高优先级：先清掉自己脚下压着的路障（如果确实站在路障里）。
    Action selfRescue = clearBlockadeUnderfoot();
    if (selfRescue != null) {
      this.result = selfRescue;
      return this;
    }

    // 默认清障逻辑。
    super.calc();

    // 卡住兜底：默认逻辑只清"目标路"或"红白被卡住的路"上的路障，警察在中间路 /
    // 建筑旁被路障围住时它找不到该清哪个（result 为 null），或者一直发 ActionMove
    // 却被路障挡着原地踏步。这里补一刀：长时间没动，就清当前路上离自己最近的路障。
    trackStuck();
    boolean stuckMoving = this.result instanceof ActionMove
        && this.stuckCount >= STUCK_TICKS;
    if (this.result == null || stuckMoving) {
      Action clearNearest = clearNearestBlockade();
      if (clearNearest != null) {
        this.result = clearNearest;
        this.stuckCount = 0;
      }
    }
    return this;
  }


  /**
   * 记录当前位置，位置不变则卡住计数 +1，变了则清零。
   */
  private void trackStuck() {
    EntityID pos = this.agentInfo.getPosition();
    if (this.lastPosition != null && this.lastPosition.equals(pos)) {
      this.stuckCount++;
    } else {
      this.stuckCount = 0;
    }
    this.lastPosition = pos;
  }


  /**
   * 找当前所在路上离警察最近的路障：够得着就 AKClear 直清，够不着就朝它走过去。
   * 解决"警察检测不到最近的障碍物"卡住的问题（中间障碍物、建筑旁被围都是这个原因）。
   */
  private Action clearNearestBlockade() {
    PoliceForce police = (PoliceForce) this.agentInfo.me();
    StandardEntity position = this.worldInfo.getPosition(police);
    if (!(position instanceof Road)) {
      return null;
    }
    Road road = (Road) position;
    if (!road.isBlockadesDefined() || road.getBlockades().isEmpty()) {
      return null;
    }
    Blockade nearest = null;
    double bestDist = Double.MAX_VALUE;
    for (EntityID id : road.getBlockades()) {
      StandardEntity entity = this.worldInfo.getEntity(id);
      if (!(entity instanceof Blockade)) {
        continue;
      }
      Blockade blockade = (Blockade) entity;
      if (!blockade.isApexesDefined()) {
        continue;
      }
      double d = Math.hypot(blockade.getX() - police.getX(),
          blockade.getY() - police.getY());
      if (d < bestDist) {
        bestDist = d;
        nearest = blockade;
      }
    }
    if (nearest == null) {
      return null;
    }
    int clearDistance = this.scenarioInfo.getClearRepairDistance();
    if (bestDist <= clearDistance) {
      return new ActionClear(nearest);
    }
    // 够不到：先朝最近的路障走过去，进入清障距离后下一拍再清。
    return new ActionMove(Arrays.asList(road.getID()), nearest.getX(),
        nearest.getY());
  }


  /**
   * 若警察当前所在的路有路障、且警察的 (x, y) 落在某个路障多边形内部，
   * 返回针对该路障的 AKClear（按 ID 直清）；否则返回 null。
   */
  private Action clearBlockadeUnderfoot() {
    PoliceForce police = (PoliceForce) this.agentInfo.me();
    StandardEntity position = this.worldInfo.getPosition(police);
    if (!(position instanceof Road)) {
      return null;
    }
    Road road = (Road) position;
    if (!road.isBlockadesDefined() || road.getBlockades().isEmpty()) {
      return null;
    }
    int px = police.getX();
    int py = police.getY();
    for (EntityID id : road.getBlockades()) {
      StandardEntity entity = this.worldInfo.getEntity(id);
      if (!(entity instanceof Blockade)) {
        continue;
      }
      Blockade blockade = (Blockade) entity;
      if (blockade.isApexesDefined() && containsPoint(px, py,
          blockade.getApexes())) {
        return new ActionClear(blockade);
      }
    }
    return null;
  }


  /**
   * 射线法判定点是否在多边形内。apexes 是 [x0, y0, x1, y1, ...] 的扁平数组，
   * 多边形隐含首尾闭合（最后一个顶点连回第一个顶点）。
   */
  private boolean containsPoint(int px, int py, int[] apexes) {
    boolean inside = false;
    int n = apexes.length;
    for (int i = 0, j = n - 2; i < n; j = i, i += 2) {
      double xi = apexes[i];
      double yi = apexes[i + 1];
      double xj = apexes[j];
      double yj = apexes[j + 1];
      if (((yi > py) != (yj > py))
          && (px < (xj - xi) * (py - yi) / (yj - yi) + xi)) {
        inside = !inside;
      }
    }
    return inside;
  }
}
