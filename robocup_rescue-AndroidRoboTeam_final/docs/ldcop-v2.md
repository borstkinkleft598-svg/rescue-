# L-DCOP v2：分层多任务时序调度

本次只深化 L-DCOP，不新增 Binary Max-Sum、ADVP、伪通信服务器或其他独立算法。
项目原有的警察分配等模块保持原样。没有火灾调度或灭火功能改动。

## 依据与实现范围

依据 Matsunaga 等，2023，*Design of Task Assignments based on Ordering and
Time Window Constraints for RoboCupRescue Agents*，第 III.B–E 节，图 4–6。
论文将任务分成救援层和运输层，通过 STN 安排任务的开始、完成和执行顺序。
其实验实际使用虚拟救护车（III.D），不是真实的双类型 Agent 完整协商。

此实现将论文的分层、顺序边和 STN 插入机制用于真实救护车。
分层任务归属采用确定性的可行插入评分：先考虑截止时间较早的任务，
相同截止时间选择增加工期最少的 Agent，最后按 ID 打破平局。
没有实现论文中的完整分布式 DCOP 求解器，也不声称获得全局最优解。

## 与 v1 的差异

| v1 | v2 |
|---|---|
| 单个候选任务的耗时检查 | 每个 Agent 最多 3 个任务的完整时间表 |
| 只有当前任务和粗略后续时间 | 救援层完成时间成为运输层的最早服务时间 |
| 独立排序，可能重复选人 | 本地分层分配约束一个任务一个负责人，结合短期预约 |
| 无新任务插入检查 | 尝试所有插入位置，重算所有已有任务的时间窗 |
| 后续运输依靠大致估计 | 要求有真实、未载人的救护车，并检查实际运输层排程 |
| 没有候选则回退原规则 | v2 不回退原选人规则，返回空目标供原战术探索 |

## 时间约束

对于固定的任务顺序，使用无环时间约束网的前向传播：

```
start_i  = max(finish_previous + travel(previous_end, pickup_i), release_i)
finish_i = start_i + duration_i
finish_i + follow_up_i <= latest_finish_i
```

- 救援层：`duration=buriedness`，保守假定一名规划中的救援人员，
  不凭空假定多名 Agent 同时在场。
- 运输层：`release=对应救援任务的预计完成时间`；到达可与挖掘并行，
  装载必须等待挖掘完成。未埋压平民的 release 为当前时间。
- 运输 duration 含装载、去避难所和卸载；下一个任务从避难所出发。
- 救援层先用运输耗时下界过滤；完成运输层分配后，移除没有可行运输任务
  的平民救援任务。被埋压队员只需救援，不要求救护车运输。
- `latest_finish = observation_time + floor((HP-1)/damage)`。
  未更新的 HP 不会在每次重规划时把截止时间向后推。
- 无避难所、不可达、已死亡、已在避难所、已装载的平民不进入候选。

STN 实现针对固定顺序的单向时间约束，不是任意差分约束图的通用求解器。
每次只搜索新任务的所有插入位置，不枚举已有任务的全部排列，
因此不能声称实现论文讨论的 n! 全排列最优搜索。

## 跨 Agent 信息与限制

各 Agent 在自己的 WorldInfo 上构造本地计划；没有共享内存、全图真值或额外服务器。
复用标准 FireBrigade/AmbulanceTeam 消息的 action/target 字段，
每个重规划周期广播一次 human-target MOVE 意向，作为限时预约。
接收救援/装载状态也会更新预约；同一任务多个预约以较小 Agent ID 决定归属。
预约有有效期，并非无限占用。仅广播当前第一项任务，不广播整个队列。

本地快照一致时分配确定且没有重复归属；通信丢失、观察不一致时，
不能保证全队绝无重复任务或精确同步，不能称为完整分布式一致性协议。
缓存任务会在下一次重规划、目标失效或收到冲突预约时重新安排。

## 估计误差与待验证效果

- 自己当前位置出发的路径使用现有 PathPlanning 验证。
- 其他 Agent 的路段、未来任务间路段用 WorldInfo 距离并乘 1.3 安全系数估计，
  仍可能低估绕路、堵塞及等待，不是可达性保证。
- 一个救援任务只规划一名救援人员；这是保守限制，可能漏掉必须多人合作
  才能及时救出的平民。未实现组队调度。
- 多任务队列按周期滚动重建，不是全局固定不变的承诺。
- 保留原执行动作与车上乘客优先运送逻辑；新调度器改变目标选择。
- 没有完成相同地图和随机种子的比赛 A/B 测试，成绩提升尚未证实。

## 配置与对照

`config/develop.json`：

```
LDcop.enabled             true   # 总开关
LDcop.layeredScheduling   true   # v2 分层多任务时间表
LDcop.maxCandidates      12     # 按绝对截止时间截取候选
LDcop.maxTasksPerAgent    3      # 每个 Agent 的队列上限（最多 6）
LDcop.replanInterval      5      # 重规划与意向广播间隔
LDcop.claimTTL            7      # 预约有效期，至少 interval+2
LDcop.travelPercent     130     # 路程时间估计安全系数
```

- 原版选人：`LDcop.enabled=false`。
- v1：`LDcop.enabled=true, LDcop.layeredScheduling=false`。
- v2：两项都为 true。

每次切换都应记录配置并重新构建/启动。不要用 clean 清理正在使用的预计算文件。

## 诊断与验证

运行时 Agent 日志输出 `[LDCOP-v2]`：时间步、Agent、角色、候选数及
`target@start-finish` 任务序列。可用：

```
rg '\[LDCOP-v2\]' <run-agent.log>
```

单元测试覆盖任务插入、既有任务截止时间、运输等待救援、避难所作为下一次出发点、
无真实救护车、实际接驳太晚、唯一运输归属、队列上限、预约归属及观测时间锚定。
比较比赛成绩时使用同地图、同种子、同预算，分别比较原版、v1、v2；
记录最终分数、救出人数、送达人数、目标切换数和决策耗时。
