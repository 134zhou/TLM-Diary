package com.tlm.diary;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

/**
 * 单条日记（内存中的条目表示）。
 * <p>
 * 外部文件的 JSON 结构见 {@link DiaryStorage.DiaryFile.Entry}，字段基本一一对应，
 * 单独拆 DTO 是为了精确控制 JSON 格式、避免依赖 Gson 对 record 的序列化行为。
 *
 * @param writtenAt 写入时刻（epoch 毫秒）
 * @param author    写入者（女仆名或玩家名）
 * @param text      正文
 * @param comments  女仆写下的评语（玩家日记使用）
 */
public record DiaryEntry(long writtenAt, String author, String text, List<DiaryComment> comments) {

    public static final Codec<DiaryEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("written_at").forGetter(DiaryEntry::writtenAt),
            Codec.STRING.fieldOf("author").forGetter(DiaryEntry::author),
            Codec.STRING.fieldOf("text").forGetter(DiaryEntry::text),
            Codec.list(DiaryComment.CODEC).optionalFieldOf("comments", List.of()).forGetter(DiaryEntry::comments)
    ).apply(i, DiaryEntry::new));

    /** 网络同步用（只读阅读界面下发条目）。 */
    public static final StreamCodec<ByteBuf, DiaryEntry> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, DiaryEntry::writtenAt,
            ByteBufCodecs.STRING_UTF8, DiaryEntry::author,
            ByteBufCodecs.STRING_UTF8, DiaryEntry::text,
            ByteBufCodecs.<ByteBuf, DiaryComment>list().apply(DiaryComment.STREAM_CODEC), DiaryEntry::comments,
            DiaryEntry::new);
}
