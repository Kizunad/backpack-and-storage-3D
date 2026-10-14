package dev.kizuna.backpack3d.protocol;

import com.google.protobuf.ByteString;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtTagSizeTracker;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 真实 ItemStack 的不可变存储边界，业务不手动挑选或丢弃 NBT 字段。 */
public final class StackCodec {
    public static final int SHA256_BYTES = 32;

    private StackCodec() {}

    public static ByteString encode(ItemStack stack) {
        // 只接受真实非空栈；每件物品的大小有上限，避免无法同步的物品进入仓库。
        if (stack.isEmpty() || stack.getCount() > stack.getMaxCount()) {
            throw new IllegalArgumentException("无效物品栈");
        }
        try {
            var tag = stack.writeNbt(new NbtCompound());
            var bytes = new ByteArrayOutputStream();
            var output = new DataOutputStream(bytes);
            output.writeByte(NbtElement.COMPOUND_TYPE);
            output.writeUTF("");
            writeCanonical(tag, output, 0);
            // 深度先由有界写入器验证，再检查读取端的分配预算，避免存入后无法解码。
            if (tag.getSizeInBytes() > Messages.MAX_NBT_MEMORY_BYTES) {
                throw new IllegalArgumentException("物品 NBT 内存大小超出限制");
            }
            if (bytes.size() > Messages.MAX_NBT_BYTES) {
                throw new IllegalArgumentException("物品 NBT 超出大小限制");
            }
            return ByteString.copyFrom(bytes.toByteArray());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void writeCanonical(NbtElement element, DataOutput output, int depth)
            throws IOException {
        // compound 的键顺序没有语义；稳定排序使两端指纹不依赖 HashMap 插入顺序。
        if (depth > Messages.MAX_NBT_DEPTH) {
            throw new IllegalArgumentException("物品 NBT 嵌套过深");
        }
        if (element instanceof NbtCompound compound) {
            for (String key : compound.getKeys().stream().sorted().toList()) {
                var child = compound.get(key);
                output.writeByte(child.getType());
                output.writeUTF(key);
                writeCanonical(child, output, depth + 1);
            }
            output.writeByte(NbtElement.END_TYPE);
        } else if (element instanceof NbtList list) {
            output.writeByte(list.getHeldType());
            output.writeInt(list.size());
            for (var child : list) {
                writeCanonical(child, output, depth + 1);
            }
        } else {
            element.write(output);
        }
    }

    public static ItemStack decode(ByteString bytes) {
        // 有界未压缩 NBT 不会产生压缩炸弹；NBT 自身的读取器同时限制分配量和嵌套层数。
        if (bytes.isEmpty() || bytes.size() > Messages.MAX_NBT_BYTES) {
            throw new IllegalArgumentException("无效 NBT 大小");
        }
        try {
            var input = new DataInputStream(bytes.newInput());
            var nbt = NbtIo.read(input, new NbtTagSizeTracker(Messages.MAX_NBT_MEMORY_BYTES));
            var stack = ItemStack.fromNbt(nbt);
            if (input.available() != 0
                    || stack.isEmpty()
                    || stack.getCount() > stack.getMaxCount()) {
                throw new IllegalArgumentException("无效物品 NBT");
            }
            return stack;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public static ByteString fingerprint(ByteString bytes) {
        // 指纹只用于内容对拍，不把它视为身份认证或物品创建权限。
        try {
            return ByteString.copyFrom(
                    MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
