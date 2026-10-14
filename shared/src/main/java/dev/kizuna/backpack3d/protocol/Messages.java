package dev.kizuna.backpack3d.protocol;

import static dev.kizuna.inventoryui.protocol.pb.WireProtocol.Direction.*;

import com.google.protobuf.Message;

import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.*;
import dev.kizuna.inventoryui.protocol.pb.WireProtocol;

import java.util.Map;

/** 两端共享消息标识，公共信封继续使用 Framework wire v2。 */
public final class Messages {
    public static final String MODULE = "bs3d:inventory";
    public static final String OPEN = "bs3d:open";
    public static final String MOVE = "bs3d:move";
    public static final String CANCEL = "bs3d:cancel";
    public static final String STATE = "bs3d:state";
    public static final String EQUIP = "bs3d:equip";
    public static final String APPEARANCE = "bs3d:appearance";
    public static final String GROUND_OPENED = "bs3d:ground_opened";
    public static final String CLOSE_GROUND = "bs3d:close_ground";
    public static final int VERSION = 6;
    public static final int MAX_NBT_MEMORY_BYTES = 262_144;
    public static final int MAX_NBT_BYTES = 16_384;
    public static final int MAX_NBT_DEPTH = 32;
    public static final int MAX_SAVE_BYTES = 196_608;
    public static final int RECEIPT_LIMIT = 128;
    public static final int TICKS_PER_SECOND = 20;

    private Messages() {}

    public static Map<String, WireProtocol.Capability> capabilities() {
        return Map.of(
                OPEN, capability(OpenRequest.getDefaultInstance(), CLIENT_TO_SERVER),
                MOVE, capability(MoveRequest.getDefaultInstance(), CLIENT_TO_SERVER),
                EQUIP, capability(EquipRequest.getDefaultInstance(), CLIENT_TO_SERVER),
                CANCEL, capability(CancelRequest.getDefaultInstance(), CLIENT_TO_SERVER),
                STATE, capability(State.getDefaultInstance(), SERVER_TO_CLIENT),
                APPEARANCE, capability(EquipmentAppearance.getDefaultInstance(), SERVER_TO_CLIENT),
                GROUND_OPENED, capability(GroundOpened.getDefaultInstance(), SERVER_TO_CLIENT),
                CLOSE_GROUND,
                        capability(CloseGroundRequest.getDefaultInstance(), CLIENT_TO_SERVER));
    }

    private static WireProtocol.Capability capability(
            Message sample, WireProtocol.Direction direction) {
        // 描述符来自 protoc；调用点显式写出方向，避免用布尔值猜测收发关系。
        String payloadType = sample.getDescriptorForType().getFullName();
        return WireProtocol.Capability.newBuilder()
                .setModule(MODULE)
                .setVersion(VERSION)
                .setRequired(true)
                .setPayloadType(payloadType)
                .setDirection(direction)
                .build();
    }
}
