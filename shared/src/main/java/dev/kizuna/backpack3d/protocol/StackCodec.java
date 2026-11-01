package dev.kizuna.backpack3d.protocol;

import com.google.protobuf.ByteString;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 真实 ItemStack 的不可变存储边界，业务不手动挑选或丢弃 NBT 字段。
 *
 * <p>26.2 的物品数据组件会引用数据驱动注册表（附魔、盔甲纹饰等），编解码都要带所在端的注册表；
 * 单机里客户端与内置服务端各有一份注册表，不能混用，所以由调用方传入。
 */
public final class StackCodec {
    public static final int SHA256_BYTES = 32;

    private StackCodec() {}

    public static ByteString encode(ItemStack stack, HolderLookup.Provider registries) {
        // 只接受真实非空栈；每件物品的大小有上限，避免无法同步的物品进入仓库。
        if (stack.isEmpty() || stack.getCount() > stack.getMaxStackSize()) {
            throw new IllegalArgumentException("无效物品栈");
        }
        try {
            var tag = ItemStack.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack)
                    .getOrThrow(IllegalArgumentException::new);
            var bytes = new ByteArrayOutputStream();
            var output = new DataOutputStream(bytes);
            output.writeByte(Tag.TAG_COMPOUND);
            output.writeUTF("");
            writeCanonical(tag, output, 0);
            // 深度先由有界写入器验证，再检查读取端的分配预算，避免存入后无法解码。
            if (tag.sizeInBytes() > Messages.MAX_NBT_MEMORY_BYTES) {
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

    private static void writeCanonical(Tag element, DataOutput output, int depth)
            throws IOException {
        // compound 的键顺序没有语义；稳定排序使两端指纹不依赖 HashMap 插入顺序。
        if (depth > Messages.MAX_NBT_DEPTH) {
            throw new IllegalArgumentException("物品 NBT 嵌套过深");
        }
        if (element instanceof CompoundTag compound) {
            for (String key : compound.keySet().stream().sorted().toList()) {
                var child = compound.get(key);
                output.writeByte(child.getId());
                output.writeUTF(key);
                writeCanonical(child, output, depth + 1);
            }
            output.writeByte(Tag.TAG_END);
        } else if (element instanceof ListTag list) {
            byte type = elementType(list);
            output.writeByte(type);
            output.writeInt(list.size());
            for (var child : list) {
                writeCanonical(wrapElement(type, child), output, depth + 1);
            }
        } else {
            element.write(output);
        }
    }

    private static byte elementType(ListTag list) {
        // 26.2 的列表允许混合类型；与原版 ListTag.write 相同，同类列表写元素类型，混合列表一律写成 compound 列表。
        byte type = Tag.TAG_END;
        for (var child : list) {
            if (type == Tag.TAG_END) {
                type = child.getId();
            } else if (type != child.getId()) {
                return Tag.TAG_COMPOUND;
            }
        }
        return type;
    }

    private static Tag wrapElement(byte listType, Tag child) {
        // 混合列表里的非 compound 元素（以及形似包装的 compound）按原版规则包成 {"": 值}，读取时原版会自动拆开。
        if (listType != Tag.TAG_COMPOUND) {
            return child;
        }
        if (child instanceof CompoundTag compound && !(compound.size() == 1 && compound.contains(""))) {
            return child;
        }
        var wrapper = new CompoundTag();
        wrapper.put("", child);
        return wrapper;
    }

    public static ItemStack decode(ByteString bytes, HolderLookup.Provider registries) {
        // 有界未压缩 NBT 不会产生压缩炸弹；NBT 自身的读取器同时限制分配量和嵌套层数。
        if (bytes.isEmpty() || bytes.size() > Messages.MAX_NBT_BYTES) {
            throw new IllegalArgumentException("无效 NBT 大小");
        }
        try {
            var input = new DataInputStream(bytes.newInput());
            var nbt = NbtIo.read(input, NbtAccounter.create(Messages.MAX_NBT_MEMORY_BYTES));
            // 与 1.20.1 的 ItemStack.of 相同：解析失败得到空栈，下面统一按无效物品拒绝。
            var stack = ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), nbt)
                    .result()
                    .orElse(ItemStack.EMPTY);
            if (input.available() != 0
                    || stack.isEmpty()
                    || stack.getCount() > stack.getMaxStackSize()) {
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

    public static ByteString fingerprint(ItemStack stack, HolderLookup.Provider registries) {
        // 空槽使用空字节的哈希作为确定性状态；仍不允许把空栈编码为可存储物品。
        return fingerprint(stack.isEmpty() ? ByteString.EMPTY : encode(stack, registries));
    }
}
