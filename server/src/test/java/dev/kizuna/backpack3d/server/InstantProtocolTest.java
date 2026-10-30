package dev.kizuna.backpack3d.server;

import com.google.protobuf.UnknownFieldSet;
import dev.kizuna.backpack3d.protocol.Messages;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Phase;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.State;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Transfer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InstantProtocolTest {
    private static final int ELAPSED_FIELD = 3;
    private static final int DURATION_FIELD = 4;

    @Test
    void reservedTimingIsRejectedAfterProtobufDecode() throws Exception {
        // 用真实旧字段线型编码，再由新 schema 解码，非零值不能被静默忽略。
        for (int field : new int[] {ELAPSED_FIELD, DURATION_FIELD}) {
            var invalid = decoded(field, 1);
            assertThrows(IllegalArgumentException.class, () -> Messages.validatePayload(invalid));
            var zero = decoded(field, 0);
            assertEquals(zero, Messages.validatePayload(zero));
        }
    }

    private static State decoded(int number, long value) throws Exception {
        // 分别构造每层消息，保留字段号以模拟旧版本真实载荷。
        var field = UnknownFieldSet.Field.newBuilder()
                .addVarint(value)
                .build();
        var unknown = UnknownFieldSet.newBuilder()
                .addField(number, field)
                .build();
        var transfer = Transfer.newBuilder()
                .setPhase(Phase.COMPLETED)
                .setUnknownFields(unknown)
                .build();
        var state = State.newBuilder()
                .setTransfer(transfer)
                .build();
        return State.parseFrom(state.toByteArray());
    }
}
