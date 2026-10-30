package dev.kizuna.backpack3d.server;

import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.MoveRequest;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Phase;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Transfer;
import dev.kizuna.backpack3d.service.Account;
import dev.kizuna.backpack3d.service.TransferService;
import java.io.IOException;

/** 无调度器的转移入口；返回完成回执时库存和幂等收据已提交。 */
public final class ImmediateTransfer {
    private final TransferService service;
    private final HandlingRules rules;

    public ImmediateTransfer(TransferService service, HandlingRules rules) {
        // 数值和提交规则由同一服务器目录提供。
        this.service = service;
        this.rules = rules;
    }

    public Transfer execute(Account account, MoveRequest move) throws IOException {
        // 重试不再次取物和播放声音，新请求的准备与提交发生在同一调用内。
        var response = Transfer.newBuilder()
                .setOperation(move.getOperation())
                .setPhase(Phase.COMPLETED)
                .setDetail("转移完成");
        if (service.committed(account, move)) {
            return response.build();
        }
        var candidate = service.prepare(account, move);
        var material = rules.material(candidate.moved());
        service.commit(account, move);
        return response.setContactSound(material.sound())
                .setSoundVolume(material.volume())
                .setSoundPitch(material.pitch())
                .setSource(candidate.source())
                .setTarget(move.getTarget())
                .build();
    }
}
