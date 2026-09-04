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
 * @param ownerUuid    绑定对象 UUID（女仆或玩家；永久绑定；empty = 未绑定）
 * @param ownerName    绑定对象名称缓存（事件驱动刷新；仅作 tooltip 回退显示）
 * @param note         备注（AI 命名/修改，用于区分多本日记；"" = 无备注）
 * @param locked       上锁（仅女仆日记有意义）：普通右键被拦截，潜行右键可强开
 * @param maxEntries   本日记写入上限
 * @param writtenCount 已写入条数
 * @param ownerType    绑定类型："" = 未绑定；"maid" = 女仆日记；"player" = 玩家日记
 */
public record DiaryMeta(UUID diaryUuid, Optional<UUID> ownerUuid, String ownerName, String note, boolean locked,
                        int maxEntries, int writtenCount, String ownerType) {

    public static final String OWNER_TYPE_MAID = "maid";
    public static final String OWNER_TYPE_PLAYER = "player";

    public static final Codec<DiaryMeta> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("diary_uuid").forGetter(DiaryMeta::diaryUuid),
            UUIDUtil.CODEC.optionalFieldOf("owner_uuid").forGetter(DiaryMeta::ownerUuid),
            Codec.STRING.optionalFieldOf("owner_name", "").forGetter(DiaryMeta::ownerName),
            Codec.STRING.optionalFieldOf("note", "").forGetter(DiaryMeta::note),
            Codec.BOOL.optionalFieldOf("locked", false).forGetter(DiaryMeta::locked),
            Codec.INT.fieldOf("max_entries").forGetter(DiaryMeta::maxEntries),
            Codec.INT.fieldOf("written_count").forGetter(DiaryMeta::writtenCount),
            Codec.STRING.optionalFieldOf("owner_type", "").forGetter(DiaryMeta::ownerType)
    ).apply(i, DiaryMeta::new));

    /**
     * 网络同步。record 已达 8 个字段，超出 {@code StreamCodec.composite} 的 6 字段上限，
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
                ByteBufCodecs.STRING_UTF8.encode(buf, m.ownerType());
            },
            buf -> {
                UUID uuid = UUIDUtil.STREAM_CODEC.decode(buf);
                UUID owner = buf.readBoolean() ? UUIDUtil.STREAM_CODEC.decode(buf) : null;
                String ownerName = ByteBufCodecs.STRING_UTF8.decode(buf);
                String note = ByteBufCodecs.STRING_UTF8.decode(buf);
                boolean locked = buf.readBoolean();
                int maxEntries = ByteBufCodecs.VAR_INT.decode(buf);
                int writtenCount = ByteBufCodecs.VAR_INT.decode(buf);
                String ownerType = ByteBufCodecs.STRING_UTF8.decode(buf);
                return new DiaryMeta(uuid, Optional.ofNullable(owner), ownerName, note, locked, maxEntries, writtenCount, ownerType);
            });

    /** 创建一本全新的、未绑定的日记本。 */
    public static DiaryMeta fresh(int maxEntries) {
        return new DiaryMeta(UUID.randomUUID(), Optional.empty(), "", "", false, maxEntries, 0, "");
    }

    public boolean isBound() {
        return ownerUuid.isPresent();
    }

    public boolean isMaidBound() {
        return isBound() && OWNER_TYPE_MAID.equals(ownerType);
    }

    public boolean isPlayerBound() {
        return isBound() && OWNER_TYPE_PLAYER.equals(ownerType);
    }

    public boolean isOwner(UUID uuid) {
        return ownerUuid.isPresent() && ownerUuid.get().equals(uuid);
    }

    public boolean isOwnerMaid(UUID maidUuid) {
        return isMaidBound() && isOwner(maidUuid);
    }

    public boolean isOwnerPlayer(UUID playerUuid) {
        return isPlayerBound() && isOwner(playerUuid);
    }

    public boolean isFull() {
        return writtenCount >= maxEntries;
    }

    public boolean hasNote() {
        return note != null && !note.isBlank();
    }

    public DiaryMeta withOwner(UUID uuid, String newOwnerType) {
        return new DiaryMeta(diaryUuid, Optional.of(uuid), ownerName, note, locked, maxEntries, writtenCount, newOwnerType);
    }

    public DiaryMeta withOwnerName(String name) {
        return new DiaryMeta(diaryUuid, ownerUuid, name, note, locked, maxEntries, writtenCount, ownerType);
    }

    public DiaryMeta withNote(String newNote) {
        return new DiaryMeta(diaryUuid, ownerUuid, ownerName, newNote, locked, maxEntries, writtenCount, ownerType);
    }

    public DiaryMeta withLocked(boolean newLocked) {
        return new DiaryMeta(diaryUuid, ownerUuid, ownerName, note, newLocked, maxEntries, writtenCount, ownerType);
    }

    public DiaryMeta withWrittenCount(int count) {
        return new DiaryMeta(diaryUuid, ownerUuid, ownerName, note, locked, maxEntries, count, ownerType);
    }
}
