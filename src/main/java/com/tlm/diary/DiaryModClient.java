package com.tlm.diary;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

/**
 * 客户端入口。此类不会在专用服务器上加载。
 */
@Mod(value = DiaryMod.MODID, dist = Dist.CLIENT)
public class DiaryModClient {

    public DiaryModClient() {
        // TODO(Phase 1)：注册只读阅读 Screen、网络 payload 处理等客户端逻辑。
        // 玩家侧只读，内容经服务端下发。
    }
}
