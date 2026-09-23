package team08024301.module.comm;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.StandardMessage;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.information.MessageAmbulanceTeam;
import adf.core.agent.communication.standard.bundle.information.MessageBuilding;
import adf.core.agent.communication.standard.bundle.information.MessageCivilian;
import adf.core.agent.communication.standard.bundle.information.MessageFireBrigade;
import adf.core.agent.communication.standard.bundle.information.MessagePoliceForce;
import adf.core.agent.communication.standard.bundle.information.MessageRoad;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.component.communication.CommunicationMessage;
import adf.impl.module.comm.DefaultMessageCoordinator;
import java.util.ArrayList;
import java.util.List;
import rescuecore2.standard.entities.PoliceForce;
import rescuecore2.standard.entities.StandardEntity;

/**
 * 把警察之间的协调消息（MessagePoliceForce）从全局 radio 改到 voice（channel 0，短距离 SPEAK）。
 *
 * 原因：警察互相避让用的"认领"消息走全局 radio 时，30 个警察 + 红白的广播挤在同一个
 * 无线频道上，带宽一超就丢包，导致每个警察看到的"认领图"不一致、互相都以为某条路没人
 * 要，于是又扎堆。voice 频道不丢、且只在附近传播——只有附近的警察才可能撞上同一条路，
 * 正好只需要跟附近警察协调，远处的警察不需要。
 *
 * 其它消息（红白广播、红白的清障请求 CommandPolice 等）保持走 radio，行为不变。
 */
public class SampleMessageCoordinator extends DefaultMessageCoordinator {

  @Override
  public void coordinate(AgentInfo agentInfo, WorldInfo worldInfo,
      ScenarioInfo scenarioInfo, MessageManager messageManager,
      ArrayList<CommunicationMessage> sendMessageList,
      List<List<CommunicationMessage>> channelSendMessageList) {

    int numRadioChannels = scenarioInfo.getCommsChannelsCount() - 1;
    ArrayList<CommunicationMessage> radioMessages = new ArrayList<>();
    for (CommunicationMessage msg : sendMessageList) {
      if (msg instanceof MessagePoliceForce) {
        MessagePoliceForce p = (MessagePoliceForce) msg;
        StandardEntity entity = worldInfo.getEntity(p.getAgentID());
        if (entity instanceof PoliceForce) {
          if (numRadioChannels == 2) {
            // 2 radio 频道图（paris/vc/kobe）带宽充足（每频道 10000）：警察认领消息走
            // radio 频道 2，让所有警察都能听到彼此的认领，才能做到"一个请求/一个被困
            // 门口只去一个警察"。voice 只在附近传播，远处的警察听不到认领，会各自挤去
            // 同一个清障请求，把聚集点的工作丢掉。
            radioMessages.add(msg);
          } else {
            // 其它图（istanbul 3 radio / sf 1 radio 带宽紧）：克隆成 voice 版
            // （isRadio=false），直接发到 channel 0（SPEAK），只在附近传播。
            MessagePoliceForce voice = new MessagePoliceForce(false,
                (PoliceForce) entity, p.getAction(), p.getTargetID());
            channelSendMessageList.get(0).add(voice);
          }
          continue;
        }
      }
      radioMessages.add(msg);
    }

    // paris 这种"只有两个 radio 频道"的图：默认实现会把 MessageFireBrigade /
    // MessageAmbulanceTeam / MessageRoad 复制成 3 份塞进红白警各自的发送列表——3 倍冗余
    // 纯属浪费，而且红白又都先发 channel 1，于是 channel 1 带宽被打爆丢包。这里改成每条
    // 消息只发一次。
    //
    // 频道归属（见 DefaultChannelSubscriber.getChannelNumber，numChannels=2）：
    //   红白都订 channel 1，警订 channel 2。
    // 所以红白相关（伤员 MessageCivilian / 消防 / 救护 / 建筑）发 channel 1，警察相关
    // （清障请求 CommandPolice / 道路信息 MessageRoad）发 channel 2。伤员广播必须走
    // channel 1，否则挖人的红根本收不到。
    if (scenarioInfo.getCommsChannelsCount() - 1 == 2) {
      for (CommunicationMessage msg : radioMessages) {
        if (msg instanceof StandardMessage
            && !((StandardMessage) msg).isRadio()) {
          channelSendMessageList.get(0).add(msg); // 少量 voice 消息照旧发 channel 0
        } else if (isPoliceChannelMessage(msg)) {
          channelSendMessageList.get(2).add(msg);
        } else {
          channelSendMessageList.get(1).add(msg);
        }
      }
      return;
    }

    // SF 这种"只有一个 radio 频道"的图（comms.channels.count==2，即 1 voice + 1 radio）：
    // 所有排都只订阅唯一的 channel 1（见 DefaultChannelSubscriber，numChannels==1 时
    // 红白警的频道号全都算成 1）。父类 super.coordinate 仍会把 MessageFireBrigade /
    // MessageAmbulanceTeam / MessageRoad 各复制 3 份，全部塞进 channel 1 —— 3 倍冗余
    // 直接打爆 4000 字节带宽，服务器按到达顺序丢包（"Discarding message on channel 1:
    // already used 4000 of 4000 bytes"），红白发的清障请求 CommandPolice 也跟着被丢，
    // 警察根本收不到、无法去清障。这里改成每条消息只发一次到 channel 1，把带宽省下来
    // 给清障请求。
    if (scenarioInfo.getCommsChannelsCount() - 1 == 1) {
      for (CommunicationMessage msg : radioMessages) {
        if (msg instanceof StandardMessage
            && !((StandardMessage) msg).isRadio()) {
          channelSendMessageList.get(0).add(msg); // 少量 voice 消息照旧发 channel 0
        } else {
          channelSendMessageList.get(1).add(msg);
        }
      }
      return;
    }

    // istanbul 这种"三个 radio 频道"的图（comms.channels.count==4，即 1 voice + 3 radio）：
    // 修好订阅后，红订 [1]、警订 [2]、白订 [3]（DefaultChannelSubscriber 在 numChannels==3
    // 时 FB→1 / PF→2 / AT→3，三队各自独立频道）。所以跨队消息必须显式发到对方的频道，
    // 否则对方收不到。核心通信：
    //   红红互援（MessageFireBrigade 认领）→ 1；红叫白救人（ACTION_RESCUE）→ 1 + 3
    //   白内部（MessageAmbulanceTeam 认领）→ 3；红白共享伤员（MessageCivilian）→ 1 + 3
    //   红白叫警察（CommandPolice）、道路信息（MessageRoad）→ 2；建筑信息 → 1
    // 警察互相避让走 voice（上面第一条已转 channel 0），不占 radio 带宽。
    // istanbul 三个 radio 带宽很小（2248/1524/1524），默认三倍冗余会把 2/3 号频道打爆
    // （实测 ch2 丢 15250、ch3 丢 17447 次），所以这里每条消息只发一次到真正需要的频道。
    if (scenarioInfo.getCommsChannelsCount() - 1 == 3) {
      for (CommunicationMessage msg : radioMessages) {
        if (msg instanceof StandardMessage
            && !((StandardMessage) msg).isRadio()) {
          channelSendMessageList.get(0).add(msg); // 少量 voice 消息照旧发 channel 0
        } else if (msg instanceof CommandPolice || msg instanceof MessageRoad) {
          channelSendMessageList.get(2).add(msg);
        } else if (msg instanceof MessageCivilian) {
          channelSendMessageList.get(1).add(msg);
          channelSendMessageList.get(3).add(msg);
        } else if (msg instanceof MessageAmbulanceTeam) {
          channelSendMessageList.get(3).add(msg);
        } else if (msg instanceof MessageFireBrigade) {
          channelSendMessageList.get(1).add(msg);
          if (((MessageFireBrigade) msg)
              .getAction() == MessageFireBrigade.ACTION_RESCUE) {
            channelSendMessageList.get(3).add(msg);
          }
        } else if (msg instanceof MessageBuilding) {
          channelSendMessageList.get(1).add(msg);
        } else {
          channelSendMessageList.get(1).add(msg); // 其它（中心命令等）兜底发 1
        }
      }
      return;
    }

    // 其余频道数（>3）：每个排只订阅自己的频道，必须照父类的多频道路由。
    super.coordinate(agentInfo, worldInfo, scenarioInfo, messageManager,
        radioMessages, channelSendMessageList);
  }


  // 两信道图里只有警订 channel 2：清障请求 CommandPolice、道路信息 MessageRoad 走 2，
  // 其余（伤员/消防/救护/建筑）都走 1（红白共用）。
  private boolean isPoliceChannelMessage(CommunicationMessage msg) {
    return msg instanceof CommandPolice || msg instanceof MessageRoad
        || msg instanceof MessagePoliceForce;
  }
}
