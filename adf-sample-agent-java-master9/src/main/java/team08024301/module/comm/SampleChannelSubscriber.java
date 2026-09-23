package team08024301.module.comm;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.impl.module.comm.DefaultChannelSubscriber;
import rescuecore2.standard.entities.StandardEntityURN;

/**
 * 修复多无线频道图上"订阅根本不生效"的问题。
 *
 * 默认的 DefaultChannelSubscriber 只在 time==1 时订阅频道，但 istanbul / VC / paris / SF
 * 的 kernel.agents.ignoreuntil=3，Agent 要等到 time>=3 才第一次调用 subscribe()，于是
 * time==1 的守卫永远不触发，所有 agent 都退回 MessageManager 里默认的单频道 [1]。
 * 结果是频道 2/3 根本没人订阅——而 SampleMessageCoordinator 恰恰把红白的清障请求
 * CommandPolice 路由到频道 2，于是这些请求全部石沉大海，警察一条都收不到，也就永远
 * 不去救被困的红白（红白发 703 次请求、警察收到 0 次，就是这个 bug）。
 *
 * 这里改成"第一次被调用时就订阅"，不再依赖 time==1。
 */
public class SampleChannelSubscriber extends DefaultChannelSubscriber {

  private boolean subscribed = false;

  @Override
  public void subscribe(AgentInfo agentInfo, WorldInfo worldInfo,
      ScenarioInfo scenarioInfo, MessageManager messageManager) {
    if (this.subscribed) {
      return;
    }
    this.subscribed = true;

    // 0 号频道是 voice，真正的 radio 频道数是 count - 1。
    int numChannels = scenarioInfo.getCommsChannelsCount() - 1;
    int maxChannelCount = isPlatoonAgent(agentInfo, worldInfo)
        ? scenarioInfo.getCommsChannelsMaxPlatoon()
        : scenarioInfo.getCommsChannelsMaxOffice();

    StandardEntityURN agentType = getAgentType(agentInfo, worldInfo);
    int[] channels = new int[maxChannelCount];
    for (int i = 0; i < maxChannelCount; i++) {
      channels[i] = DefaultChannelSubscriber.getChannelNumber(agentType, i,
          numChannels);
    }

    messageManager.subscribeToChannels(channels);
  }
}
