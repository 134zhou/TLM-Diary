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
 * 日记本元数据（挂在 ItemStack 的 DataComponent 上，仅存索引、绑定信息、备注与锁状态，不含正文）。
 *
 * @param diaryUuid    日记唯一 ID，对应外部文件 {@code <游戏目录>/tlm_diary/diaries/<uuid>.json}
 * @param ownerUuid    绑定女仆 UUID（永久绑定；empty = 未绑定）
 * @param ownerName    绑定女仆名称缓存（事件驱动刷新；仅作 tooltip 回退显示）
 * @param note         备注（AI 命名/修改，用于玩家与 AI 区分多本日记；"" = 无备注）
 * @param locked       上锁（AI 可控）：普通右键被拦截，潜行右键可强开
 * @param maxEntries   本日记写入上限
 * @param writtenCount 已写入条数
 */
public record DiaryMeta(UUID diaryUuid, Optional<UUID> ownerUuid, String ownerName, String note, boolean locked,
                        int maxEntries, int writtenCount) {

    public static final Codec<DiaryMeta> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("diary_uuid").forGetter(DiaryMeta::diaryUuid),
            UUIDUtil.CODEC.optionalFieldOf("owner_uuid").forGetter(DiaryMeta::ownerUuid),
            Codec.STRING.optionalFieldOf("owner_name", "").forGetter(DiaryMeta::ownerName),
            Codec.STRING.optionalFieldOf("note", "").forGetter(DiaryMeta::note),
            Codec.BOOL.optionalFieldOf("locked", false).forGetter(DiaryMeta::locked),
            Codec.INT.fieldOf("max_entries").forGetter(DiaryMeta::maxEntries),
            Codec.INT.fieldOf("written_count").forGetter(DiaryMeta::writtenCount)
    ).apply(i, DiaryMeta::new));

    /**
     * 网络同步。record 已达 7 个字段，超出 {@code StreamCodec.composite} 的 6 字段上限，
     * 因此用手动编解码（顺序须与 encode/decode 一致）。
     */
    public static final StreamCodec<ByteBuf, DiaryMeta> STREAM_CODEC = StreamCodec.of(
            (buf, m) -> {
                UUIDUtil.STREAM_CODEC.encode(buf, m.diaryUuid());
                buf.writeBoolean(m.ownerUuid().isPresent());
                m.ownerUuid().ifPresent(u -> UUIDUtil.STREAM_CODEC.encode(buf, u));
                ByteBufCodecs.STRING_UTF8.encode(buf, m.ownerName());
                ByteBufCodecs.STRING_UTF8.encode(buf, m.note());
                buf.writeBoolean(m.locked());
                ByteBufCodecs.VAR_INT.encode(buf, m.maxEntries());
                ByteBufCodecs.VAR_INT.encode(buf, m.writtenCount());
            },
            buf -> {
                UUID uuid = UUIDUtil.STREAM_CODEC.decode(buf);
                UUID owner = buf.readBoolean() ? UUIDUtil.STREAM_CODEC.decode(buf) : null;
                String ownerName = ByteBufCodecs.STRING_UTF8.decode(buf);
                String note = ByteBufCodecs.STRING_UTF8.decode(buf);
                boolean locked = buf.readBoolean();
                int maxEntries = ByteBufCodecs.VAR_INT.decode(buf);
                int writtenCount = ByteBufCodecs.VAR_INT.decode(buf);
                return new DiaryMeta(uuid, Optional.ofNullable(owner), ownerName, note, locked, maxEntries, writtenCount);
            });

    /** 创建一本全新的、未绑定的日记本。 */
    public static DiaryMeta fresh(int maxEntries) {
        return new DiaryMeta(UUID.randomUUID(), Optional.empty(), "", "", false, maxEntries, 0);
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

    public boolean hasNote() {
        return note != null && !note.isBlank();
    }

    public DiaryMeta withOwner(UUID uuid) {
        return new DiaryMeta(diaryUuid, Optional.of(uuid), ownerName, note, locked, maxEntries, writtenCount);
    }

    public DiaryMeta withOwnerName(String name) {
        return new DiaryMeta(diaryUuid, ownerUuid, name, note, locked, maxEntries, writtenCount);
    }

    public DiaryMeta withNote(String newNote) {
        return new DiaryMeta(diaryUuid, ownerUuid, ownerName, newNote, locked, maxEntries, writtenCount);
    }

    public DiaryMeta withLocked(boolean newLocked) {
        return new DiaryMeta(diaryUuid, ownerUuid, ownerName, note, newLocked, maxEntries, writtenCount);
    }

    public DiaryMeta withWrittenCount(int count) {
        return new DiaryMeta(diaryUuid, ownerUuid, ownerName, note, locked, maxEntries, count);
    }
}
