package dev.kizuna.backpack3d.server;

import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;

/** 由服务器玩家 mixin 提供，随原版玩家存档读取与写入。 */
public interface PlayerStorage {
    String NBT_KEY = "backpack_storage_3d";

    SavedInventory bs3d$getInventory();

    void bs3d$setInventory(SavedInventory inventory);
}
