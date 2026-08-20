package com.tlm.diary;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.Optional;
import java.util.UUID;

/**
 * 日记本元数据（挂在 ItemStack 的 DataComponent 上，仅存索引与绑定信息，不含正文）。
 *
 * @param diaryUuid    日记唯一 ID，对应外部文件 {@code <游戏目录>/tlm_diary/diaries/<uuid>.json}
 * @param ownerUuid    绑定女仆 UUID（永久绑定；empty = 未绑定）
 * @param ownerName    绑定女仆名称缓存（事件驱动刷新：绑定/写入/读取时；仅作 tooltip 回退显示，
 *                     非权威——当前名称以客户端按 ownerUuid 实时查询女仆实体为准，命名牌改名即时生效）
 * @param maxEntries   本日记写入上限
 * @param writtenCount 已写入条数
 */
public record DiaryMeta(UUID diaryUuid, Optional<UUID> ownerUuid, String ownerName, int maxEntries, int writtenCount) {

    public static final Codec<DiaryMeta> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("diary_uuid").forGetter(DiaryMeta::diaryUuid),
            UUIDUtil.CODEC.optionalFieldOf("owner_uuid").forGetter(DiaryMeta::ownerUuid),
            Codec.STRING.optionalFieldOf("owner_name", "").forGetter(DiaryMeta::ownerName),
            Codec.INT.fieldOf("max_entries").forGetter(DiaryMeta::maxEntries),
            Codec.INT.fieldOf("written_count").forGetter(DiaryMeta::writtenCount)
    ).apply(i, DiaryMeta::new));

    /** 网络同步（客户端 tooltip 需要 ownerUuid / ownerName）。 */
    public static final StreamCodec<ByteBuf, DiaryMeta> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, DiaryMeta::diaryUuid,
            ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), DiaryMeta::ownerUuid,
            ByteBufCodecs.STRING_UTF8, DiaryMeta::ownerName,
            ByteBufCodecs.VAR_INT, DiaryMeta::maxEntries,
            ByteBufCodecs.VAR_INT, DiaryMeta::writtenCount,
            DiaryMeta::new);

    /** 创建一本全新的、未绑定的日记本。 */
    public static DiaryMeta fresh(int maxEntries) {
        return new DiaryMeta(UUID.randomUUID(), Optional.empty(), "", maxEntries, 0);
    }

    public boolean isBound() {
        return ownerUuid.isPresent();
    }

    public boolean isOwner(UUID maidUuid) {
        return ownerUuid.isPresent() && ownerUuid.get().equals(maidUuid);
    }

    public boolean isFull() {
        return writtenCount >= maxEntries;
    }

    public DiaryMeta withOwner(UUID uuid) {
        return new DiaryMeta(diaryUuid, Optional.of(uuid), ownerName, maxEntries, writtenCount);
    }

    public DiaryMeta withOwnerName(String name) {
        return new DiaryMeta(diaryUuid, ownerUuid, name, maxEntries, writtenCount);
    }

    public DiaryMeta withWrittenCount(int count) {
        return new DiaryMeta(diaryUuid, ownerUuid, ownerName, maxEntries, count);
    }
}
