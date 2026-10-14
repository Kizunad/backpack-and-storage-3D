package dev.kizuna.backpack3d.client;

import dev.kizuna.backpack3d.item.ItemMetadata;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.text.Text;

/** 原版物品栏和 BS3D 内景共用原版 tooltip 流程，保留其他模组追加的信息。 */
final class ItemDescriptions {
    private ItemDescriptions() {}

    static void register() {
        ItemTooltipCallback.EVENT.register((stack, context, lines) -> {
            // 描述按客户端当前语言解析；空键不产生空白行，原有 Lore 和附魔保持原样。
            String key = ItemMetadata.of(stack).descriptionTranslationKey();
            if (!key.isEmpty()) {
                lines.add(Text.translatable(key));
            }
        });
    }
}
