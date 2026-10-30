package dev.kizuna.backpack3d.protocol;

import com.google.protobuf.Message;

/** 保留字段号仍可能以未知字段被解码；非零旧计时不能进入即时事务客户端。 */
public final class RetiredTiming {
    private RetiredTiming() {
        // 仅提供协议边界校验，不保存运行状态。
    }

    public static void validate(Message message, int elapsedField, int durationField) {
        // 允许旧编码器显式写零；错误线型和非零计时均拒绝。
        requireZero(message, elapsedField);
        requireZero(message, durationField);
    }

    private static void requireZero(Message message, int number) {
        // 计时原字段为 int32，合法的残留只能是值为零的 varint。
        var field = message.getUnknownFields().getField(number);
        if (field.getVarintList().stream().anyMatch(value -> value != 0)
                || !field.getFixed32List().isEmpty()
                || !field.getFixed64List().isEmpty()
                || !field.getLengthDelimitedList().isEmpty()
                || !field.getGroupList().isEmpty()) {
            throw new IllegalArgumentException("elapsed_ticks / duration_ticks 已废弃");
        }
    }
}
