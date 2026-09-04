package com.tlm.diary;

import com.github.tartaricacid.touhoulittlemaid.api.bauble.IMaidBauble;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.item.ItemStack;

/**
 * 日记本的饰品实现：放入饰品栏时执行**永久绑定**并刷新名称缓存，其余时刻无定时逻辑。
 * <p>
 * 写入不在此处触发——是否写日记完全由女仆 AI 经 {@link WriteDiaryEntryTool} 决定。
 */
public class DiaryBauble implements IMaidBauble {

    @Override
    public void onPutOn(EntityMaid maid, ItemStack baubleItem) {
        // 绑定语义：首次放入饰品栏时绑定为 owner（永久绑定，不可解绑、不可转主），并记录当前名称。
        // 已绑定且是本人佩戴时，事件驱动刷新 ownerName 回退缓存（女仆即参数，零查询零轮询）。
        DiaryMeta meta = DiaryApi.metaOf(baubleItem);
        if (!meta.isBound()) {
            DiaryMeta bound = meta.withOwner(maid.getUUID(), DiaryMeta.OWNER_TYPE_MAID)
                    .withOwnerName(maid.getName().getString());
            baubleItem.set(DiaryMod.DIARY_META.get(), bound);

            // owner 同步持久化到外部文件，保证跨存档可追溯。
            DiaryStorage.DiaryFile file = DiaryStorage.load(meta.diaryUuid());
            if (file == null) {
                file = new DiaryStorage.DiaryFile();
                file.diaryId = meta.diaryUuid().toString();
                file.createdAt = System.currentTimeMillis();
            }
            file.ownerMaidId = maid.getUUID().toString();
            file.ownerType = DiaryStorage.OWNER_TYPE_MAID;
            file.ownerId = maid.getUUID().toString();
            file.updatedAt = System.currentTimeMillis();
            DiaryStorage.save(meta.diaryUuid(), file);

            // 首次绑定一本全新日记本：若该女仆有已销毁的孤儿日记，触发找回（0/1/多）。
            DiaryApi.recoverIfOrphaned(maid, baubleItem);
        } else if (meta.isOwnerMaid(maid.getUUID())) {
            DiaryApi.refreshOwnerName(baubleItem, maid);
        }
    }
}
