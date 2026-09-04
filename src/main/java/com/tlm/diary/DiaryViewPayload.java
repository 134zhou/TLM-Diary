package com.tlm.diary;

import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务端 → 客户端：下发日记内容供只读阅读界面展示。
 * <p>
 * 正文只随此 payload 在内存中传递，客户端 ItemStack 上始终不含正文。
 */
public record DiaryViewPayload(UUID diaryUuid, List<DiaryEntry> entries) implements CustomPacketPayload {

    public static final Type<DiaryViewPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DiaryMod.MODID, "diary_view"));

    public static final StreamCodec<ByteBuf, DiaryViewPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, DiaryViewPayload::diaryUuid,
            ByteBufCodecs.<ByteBuf, DiaryEntry>list().apply(DiaryEntry.STREAM_CODEC), DiaryViewPayload::entries,
            DiaryViewPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 客户端处理：把条目格式化为书页，打开只读 BookViewScreen（无任何编辑入口）。 */
    public static void handle(DiaryViewPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            List<Component> pages = buildPages(payload.entries());
            Minecraft.getInstance().setScreen(new BookViewScreen(new BookViewScreen.BookAccess(pages)));
        });
    }

    private static List<Component> buildPages(List<DiaryEntry> entries) {
        List<Component> pages = new ArrayList<>();
        if (entries == null || entries.isEmpty()) {
            pages.add(Component.translatable("tlm_diary.diary.empty").withStyle(ChatFormatting.GRAY));
        } else {
            for (DiaryEntry e : entries) {
                pages.add(page(e));
            }
        }
        return pages;
    }

    private static Component page(DiaryEntry e) {
        MutableComponent header = Component.literal("[" + time(e.writtenAt()) + "] " + e.author())
                .withStyle(ChatFormatting.GRAY);
        MutableComponent page = header.append("\n").append(e.text());
        if (e.comments() != null && !e.comments().isEmpty()) {
            for (DiaryComment c : e.comments()) {
                MutableComponent line = Component.literal("\n  ↳ " + c.author() + ": " + c.text())
                        .withStyle(ChatFormatting.DARK_PURPLE);
                page = page.append(line);
            }
        }
        return page;
    }

    private static String time(long epochMillis) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }
}
