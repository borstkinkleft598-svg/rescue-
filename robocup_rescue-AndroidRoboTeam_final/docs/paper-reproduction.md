# L-DCOP 论文流程复现模式（独立实验）

## 模式边界

保留比赛版的默认配置、v1/v2 选择器和真实运输。只有显式使用
`config/paper-module.cfg`、`config/paper-develop.json` 才进入论文模式。
本模式是根据论文重新实现的实验流程，不是作者原始源码的逐行移植，
也不声称已经复现其表 I 的数值。仍需真实地图对照实验验证端到端效果。

依据：Matsunaga 等，2023，Design of Task Assignments based on Ordering and
Time Window Constraints for RoboCupRescue Agents，III.B–E，图 4–8，表 I。
用户本地原文在 `/home/fei/下载/lunwen/`。

## 实现对应

|论文机制|实现|
|---|---|
|第一层挖掘、第二层运输的顺序约束|`PaperLdcopEngine` 的救援 STN 加虚拟运输后续时间；运输账本只在埋压归零后启动|
|每层 DCOP/BMS 分配|`DistributedBms` 使用项目已有 BMS 库的 Selector、Weighting、Cardinality 节点|
|分布式计算|每个 Agent 仅计算自己的变量节点和按 ID 分区负责的任务节点；其他节点的消息经过 TCP 转发器|
|STN 插入|复用 `TemporalSchedule`，尝试所有新任务插入位置，重新检查整条队列的时间窗，保留完成时间最短方案|
|任务同步、移除已分配任务、重复分配|每轮交换完整接受队列并验证唯一归属，随后进入下一轮，直到无可分配任务|
|按时间表执行|队列跨时间步保留；只因完成、死亡、实际剩余耗时变化导致不可行时移除相应任务|
|虚拟运输|`VirtualTransportLedger` 在救出后等待估计运输时间，检查到达时是否活着，不实际加载乘客|
|DCOP 对照|相同 BMS/感知/地图/种子，关闭跨任务 STN 与时间窗筛选，每步重新选当前任务|

论文 III.E 实际只对救援人员运行 BMS，因为运输由虚拟救护车完成。
本实验同样如此，不把真实救护车的分配扩展混入论文实验。

## 重要的假设与未复现细节

这些设置不是原文提供的精确参数，必须在报告中列出：

1. **未取得作者原始源码、具体 12 个灾害场景和全部代价参数。**
   此处的地图来自本地项目；相同城市名不代表与论文原场景相同。
   不能把本实验数字与 74.2%、79.0%、76.2% 直接当作严格数值复现比较。
2. **BMS 代价**：未服务任务罚值默认 10000；一名负责人费用为 0；
   多名负责人为同一任务时罚值 1e12。边权采用 STN 插入增加的工期；
   DCOP 对照采用赶路时间。使用 32 次同步迭代、0.5 阻尼和微小确定性破平局值。
   这些都是显式工程选择，不是声称论文使用了相同数值。
3. **协作人数**：默认一个任务一个负责人，因此时长公式中的人数 n=1。
   尚未实现一般 n>1 的联合到达、同时挖掘与多条 STN 联合承诺。
   这是对论文一般模型的限制，不能称为任意多 Agent 合作模型的完整复现。
4. **STN 范围**：针对当前固定队列的单向时序约束做前向传播和插入。
   这是图 5 的插入流程，不是任意差分约束图的通用求解器，也不声称穷举 n! 全排列。
5. **时间估计**：距离使用 WorldInfo 距离，速度默认 31445 mm/step；
   生存截止时间用观测时间加 `floor((HP-1)/damage)`；damage 未知或为零时默认 20。
   交通阻塞可能令实际到达迟于预测。所有参数保存在配置快照中。
6. **全城通信**：实验使用有同步屏障的本机 loopback 转发器，
   不受 RRS 无线电丢包或带宽限制。它只转发，不在服务器集中求解任务。
   这模拟论文全城通信条件，不是比赛可直接使用的无线电协议。
7. **BMS 非收敛**：固定迭代不保证全局最优；冲突决定按代价和 ID 裁决，
   拒绝重复承诺，并记录冲突。没有加入 ADVP。
8. **论文中的虚拟救护车不是真实官方分数。** 本实现不修改平民世界状态、
   HP 或官方评分。虚拟运输率和官方 kernel score 必须分开报告。

## 虚拟运输统计

观测到曾经被埋压的平民，在其埋压首次归零且存活时记录救出时刻；
到达时刻 = 救出时刻 + 至最近避难所的估计运输时间。到达时仍存活才计入
`virtualTransported`。成功后后续真实世界中的死亡不会撤销先前虚拟到达。
若到达时刻的观测缺失，后来只看到被截断为零的 HP，则记录 UNKNOWN，
不通过倒推 HP 伪造运输成功。原本健康且未被埋压的平民不计为成功救出。

## 使用方法

在项目根目录构建（不使用 clean，不清除当前预计算）：

```bash
cd /data/fei/RESCUE/robocup_rescue-AndroidRoboTeam_final
./gradlew --offline test build
python3 -m unittest discover -s scripts/tests -v
./gradlew --offline -I scripts/paper-selftest.gradle paperProcessSelfTest
```

先准备配置，不启动仿真：

```bash
python3 scripts/run_paper_experiment.py test --mode ldcop --seed 32425 --steps 12 --prepare-only
```

等当前比赛跑图结束，再运行短测：

```bash
python3 scripts/run_paper_experiment.py test --mode ldcop --seed 32425 --steps 12
```

单张地图完整对照，省略 `--steps` 则保留地图原长度：

```bash
python3 scripts/run_paper_comparison.py --maps test --seeds 32425
```

多地图、多种子：

```bash
python3 scripts/run_paper_comparison.py --maps berlin kobe paris --seeds 32425 32426 32427
```

启动器发现 27931 或实验端口已占用时会拒绝启动，避免占用当前比赛的内存和 CPU。
默认实验端口 28031、JSON 端口 28032、转发端口 28033。每次创建独立 server 配置副本和 agent 文件系统，
原地图、默认配置、预计算文件、旧成绩均不覆盖。

`PaperCommandCollector` 只放在实验用的 kernel classpath 中，等待 Agent 真正提交
动作就继续下一步，最长 60 秒。不是每步固定睡 60 秒；未提交动作会标记实验无效。
它不改变救援或物理规则。

`PaperFullCityPerception` 也只放在实验 kernel 中，直接给所有 Agent 提供全城
动态状态和精确 HP/埋压值，不依赖旧感知模块的空间索引发现人类实体。
这用于实现论文的理想全城感知条件，不是比赛允许的默认感知。
首次发送全量动态状态，随后共享每步增量，避免旧框架为未变化的全城属性反复保存历史。
实验 Agent JVM 上限为 4 GB；大地图应预留足够系统内存。

## 结果与检查

结果默认在 `/data/fei/RESCUE/paper_ldcop_experiments/`：

- `manifest.json`：地图、种子、长度覆盖、端口、实验假设。
- `agent.jar`、`agent-source.tar.gz`、`agent-jar.sha256`、`dependencies.sha256`：运行版本。
- `agent-fs/config/`、`server/maps/.../config/`：实际运行配置。
- `metrics/agent-*.json`：各 Agent 的救出/运输统计、目标切换、决策耗时。
- `summary.json` / `.md`：单次实验的状态和指标；检查 `metrics_consistent`。
- `comparison.json` / `.md`：DCOP/L-DCOP 成对实验结果，每次实验后更新。
- `run-agent.log`、`relay.log`、`run-kernel/rescue.log.7z`：日志和回放。

同步失败、参与者缺失、非法重复任务或动作超时都会终止本次实验并保存失败原因，
不会静默回退 v2 算法。短步数测试只能证明运行链路，不能作为比赛成绩结论。

## 已验证与尚待验证

已验证：39 项 Java 测试、4 项启动器测试、真实 TCP 屏障、两个独立 JVM 的节点分区求解、
整条队列同步、时间窗拒绝、虚拟运输时序与死亡边界、配置隔离和相同种子对照生成。

真实 RCRS 验证：小地图 test 的 8 步 L-DCOP 链路，以及 Berlin 在种子 32425 下
DCOP/L-DCOP 各 8 步的成对短测均完成。Berlin 两次均观测到 46 个被埋压平民，
5 个救援 Agent 的统计一致，JAR SHA-256 一致。L-DCOP 首轮完成 10 轮分配并同步
46 个任务；该短测期间目标切换为 0，DCOP 对照为 5。这只验证链路和决策行为，
8 步内两次均未完成救出或虚拟送达，不能用它证明救援率提升。

记录在 `/data/fei/RESCUE/paper_ldcop_experiments/validation_20261022/VALIDATION.md`。
尚待验证：完整地图长度、多随机种子对照成绩，以及论文原始场景上的数值复现。

## 2026-10-24 日志驱动的运行参数调整

10 月 22 日批次中，eindhoven、istanbul、joao、ny、paris 的 Agent 日志均有
`OutOfMemoryError: Java heap space`，部分发生在世界模型合并阶段。
因此行动超时、协议错误不能直接归因于 BMS 参数；不能靠延长等待解决堆耗尽。

独立实验脚本现在读取 `config/paper-runtime.json`：

| 参数 | 旧值 | 新值 |
|---|---:|---:|
| Agent 最大 Java 堆 | 4096 MB | 6144 MB |
| 单步行动等待上限 | 60000 ms | 120000 ms |
| 服务器启动/连接等待 | 180000 ms | 300000 ms |
| 预计算 Agent 等待 | 180 s | 300 s |

Agent 启用 `-XX:+ExitOnOutOfMemoryError`，出现堆溢出立即退出，运行器明确记录失败，
不继续输出看似有效的成绩。每轮保存 `runtime-settings.json` 和 manifest 参数。
机器仅约 16 GB 物理内存，服务器及桌面还要占用内存，因此先用 6 GB，
不直接将 Agent 堆调至 10–12 GB；仍需大图验证，可能需要后续内存优化。

BMS 32 轮、无限制任务候选集、惩罚、速度、伤害估计、45 s 同步屏障、地图及种子
保持不变。行动等待延长只是实验容错，不表示满足正式竞赛的实时限制。
单图正式阶段的 120 分钟上限不变；不会自动重跑完整批次或覆盖旧结果。

验证：6 项 Python 测试通过；Eindhoven 同种子 32425 的 4 步真实短测完成，
40 份救援 Agent 指标一致，首次分配已执行，最大记录决策耗时 14027 ms，
本次没有堆溢出。此前该地图在第 3 步前后发生堆耗尽。
结果：`/data/fei/RESCUE/paper_ldcop_experiments/parameter_validation_20261024/20261024_221603_049297_eindhoven_ldcop_seed32425/summary.json`。
短测中机器交换空间使用量一度约 2 GB，说明内存余量仍有限；不保证完整大图稳定，
也不能从 4 步内的零救出数推断最终救援效果。
