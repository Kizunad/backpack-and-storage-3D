package dev.kizuna.backpack3d.service;

import com.google.protobuf.ByteString;
import dev.kizuna.backpack3d.protocol.StackCodec;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 2026-11-01 移除的草包、背篓、收纳箱。物品不再注册，旧存档里的实例无法解码为 ItemStack：
 * 读取时记 WARN，按损坏容器处理——原样保留字节和其中的内容物，不能打开、取出或穿戴。
 * 只认这三个 ID；其他未注册物品仍按损坏数据拒绝。
 */
public final class RetiredContainers {
    private static final Set<String> IDS = Set.of("bs3d:grass_pouch", "bs3d:back_basket", "bs3d:storage_box");
    private static final String NAME_KEY = "item.bs3d.retired_container";
    private static final Logger LOG = LoggerFactory.getLogger("bs3d/retired");
    // 每次事务都会重建容器树，同一位置、同一类型只警告一次。
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private RetiredContainers() {}

    /** stackNbt 为 StackCodec 格式的物品字节；where 是日志里的位置说明。 */
    public static boolean is(ByteString stackNbt, String where) {
        return is(StackCodec.itemId(stackNbt), where);
    }

    public static boolean is(String itemId, String where) {
        if (!IDS.contains(itemId)) {
            return false;
        }
        if (WARNED.add(where + " " + itemId)) {
            LOG.warn("{}存有已移除的旧容器 {}，按损坏容器原样保留，不能打开、取出或穿戴", where, itemId);
        }
        return true;
    }

    /** 客户端同样解码不了已移除的物品；内景改用屏障方块占住它原来的格子。 */
    public static ByteString placeholder(ByteString stackNbt, HolderLookup.Provider registries) {
        var stack = new ItemStack(Items.BARRIER);
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable(NAME_KEY, StackCodec.itemId(stackNbt)));
        return StackCodec.encode(stack, registries);
    }
}
