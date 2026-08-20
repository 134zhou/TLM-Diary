package com.tlm.diary;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.handler.BaubleItemHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日记写读的统一入口（权限收敛到服务端）。
 * <p>
 * 权限模型：绑定女仆可读可写；其他女仆只读（写请求返回 NOT_OWNER）；玩家只读（GUI 走服务端下发，无写路径）。
 */
public final class DiaryApi {

    public static final int DEFAULT_MAX_ENTRIES = 50;
    public static final int MAX_TEXT_LENGTH = 1024;

    /** 活跃日记注册表：当前存档里"活着"的日记 uuid，供销毁检测（孤儿标记）使用。 */
    private static final Set<UUID> LIVE_DIARIES = ConcurrentHashMap.newKeySet();

    private DiaryApi() {
    }

    public enum WriteResult {
        SUCCESS, NO_DIARY_BOOK, NOT_OWNER, DIARY_FULL, STORAGE_ERROR
    }

    /** 读取/惰性初始化某日记本的元数据。 */
    public static DiaryMeta metaOf(ItemStack stack) {
        DiaryMeta meta = stack.get(DiaryMod.DIARY_META.get());
        if (meta == null) {
            meta = DiaryMeta.fresh(DEFAULT_MAX_ENTRIES);
            stack.set(DiaryMod.DIARY_META.get(), meta);
        }
        return meta;
    }

    /** 登记为活跃日记（物品反序列化/绑定/写入时调用）。 */
    public static void markAlive(ItemStack stack) {
        DiaryMeta meta = stack.get(DiaryMod.DIARY_META.get());
        if (meta != null) {
            LIVE_DIARIES.add(meta.diaryUuid());
        }
    }

    /** 事件驱动刷新 ownerName 回退缓存：已有 maid 实体在手（绑定/写入/佩戴时调用，零查询零轮询）。 */
    public static void refreshOwnerName(ItemStack diary, EntityMaid maid) {
        if (diary == null || diary.isEmpty()) {
            return;
        }
        DiaryMeta meta = diary.get(DiaryMod.DIARY_META.get());
        if (meta == null || !meta.isOwner(maid.getUUID())) {
            return;
        }
        String current = maid.getName().getString();
        if (!current.equals(meta.ownerName())) {
            diary.set(DiaryMod.DIARY_META.get(), meta.withOwnerName(current));
        }
    }

    /** 事件驱动刷新 ownerName 回退缓存：按 ownerUuid 查服务端女仆（同维度且已加载才可）。 */
    public static void refreshOwnerName(ItemStack diary, Level level) {
        if (diary == null || diary.isEmpty() || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        DiaryMeta meta = diary.get(DiaryMod.DIARY_META.get());
        if (meta == null || !meta.isBound()) {
            return;
        }
        Entity entity = serverLevel.getEntity(meta.ownerUuid().get());
        if (entity instanceof EntityMaid maid) {
            refreshOwnerName(diary, maid);
        }
    }

    /** 列出女仆当前持有的全部日记本（饰品栏 + 背包）。 */
    public static List<ItemStack> findDiaries(EntityMaid maid) {
        List<ItemStack> result = new ArrayList<>();
        BaubleItemHandler bauble = maid.getMaidBauble();
        for (int i = 0; i < bauble.getSlots(); i++) {
            ItemStack s = bauble.getStackInSlot(i);
            if (s.is(DiaryMod.DIARY_BOOK.get())) {
                result.add(s);
            }
        }
        IItemHandler inv = maid.getAvailableInv(false);
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.is(DiaryMod.DIARY_BOOK.get())) {
                result.add(s);
            }
        }
        return result;
    }

    /**
     * 定位目标日记本：{@code diaryUuid} 为空时优先取饰品栏中的日记本；
     * 否则取 uuid 匹配的那一本（支持女仆同时持有多本日记）。
     */
    public static ItemStack findDiary(EntityMaid maid, String diaryUuid) {
        ItemStack fallback = ItemStack.EMPTY;
        for (ItemStack s : findDiaries(maid)) {
            DiaryMeta m = metaOf(s);
            if (diaryUuid == null || diaryUuid.isBlank()) {
                if (fallback.isEmpty()) {
                    fallback = s;
                }
            } else if (m.diaryUuid().toString().equals(diaryUuid)) {
                return s;
            }
        }
        return fallback;
    }

    /** 服务端写一条日记，返回结果枚举。 */
    public static WriteResult writeEntry(EntityMaid maid, ItemStack diary, String author, String text) {
        if (diary == null || diary.isEmpty() || !diary.is(DiaryMod.DIARY_BOOK.get())) {
            return WriteResult.NO_DIARY_BOOK;
        }
        DiaryMeta meta = metaOf(diary);
        UUID maidUuid = maid.getUUID();

        if (meta.isBound() && !meta.isOwner(maidUuid)) {
            return WriteResult.NOT_OWNER;
        }
        // 事件驱动刷新 ownerName 回退缓存（写日记时有 maid 实体在手，零查询零轮询）
        if (!meta.isBound()) {
            meta = meta.withOwner(maidUuid).withOwnerName(maid.getName().getString());
        } else {
            meta = meta.withOwnerName(maid.getName().getString());
        }
        if (meta.isFull()) {
            return WriteResult.DIARY_FULL;
        }

        String clean = sanitize(text);
        DiaryEntry entry = new DiaryEntry(System.currentTimeMillis(), author, clean);

        DiaryStorage.DiaryFile file = DiaryStorage.load(meta.diaryUuid());
        if (file == null) {
            file = new DiaryStorage.DiaryFile();
            file.diaryId = meta.diaryUuid().toString();
            file.createdAt = System.currentTimeMillis();
        }
        file.ownerMaidId = maidUuid.toString();
        file.entries.add(toFileEntry(entry));
        file.updatedAt = System.currentTimeMillis();

        if (!DiaryStorage.save(meta.diaryUuid(), file)) {
            return WriteResult.STORAGE_ERROR;
        }

        diary.set(DiaryMod.DIARY_META.get(), meta.withWrittenCount(file.entries.size()));
        LIVE_DIARIES.add(meta.diaryUuid());
        return WriteResult.SUCCESS;
    }

    /** 读取某日记本的全部条目（供只读 GUI / AI 上下文使用）。 */
    public static List<DiaryEntry> readEntries(ItemStack diary) {
        if (diary == null || diary.isEmpty()) {
            return List.of();
        }
        DiaryMeta meta = diary.get(DiaryMod.DIARY_META.get());
        if (meta == null) {
            return List.of();
        }
        DiaryStorage.DiaryFile file = DiaryStorage.load(meta.diaryUuid());
        if (file == null) {
            return List.of();
        }
        List<DiaryEntry> out = new ArrayList<>();
        for (DiaryStorage.DiaryFile.Entry e : file.entries) {
            out.add(new DiaryEntry(e.writtenAt, e.author, e.text));
        }
        return out;
    }

    /** 供 AI Tool 调用：执行写入并返回给 LLM 的英文反馈。 */
    public static String writeForAi(EntityMaid maid, String content, String emotion, String diaryUuid) {
        ItemStack diary = findDiary(maid, diaryUuid);
        if (diary.isEmpty()) {
            return "The maid does not have a diary book. You can ask the owner to craft one and give it to you.";
        }
        String author = maid.getName().getString();
        String text = content;
        if (emotion != null && !emotion.isBlank()) {
            text = "[" + emotion + "] " + content;
        }
        WriteResult r = writeEntry(maid, diary, author, text);
        DiaryMeta meta = metaOf(diary);
        return switch (r) {
            case SUCCESS -> "Diary entry written successfully (%d/%d)."
                    .formatted(meta.writtenCount(), meta.maxEntries());
            case NO_DIARY_BOOK -> "The maid does not have a diary book.";
            case NOT_OWNER -> "This diary book belongs to another maid; you can only read it.";
            case DIARY_FULL -> "The diary book is full (%d/%d). You may ask the owner to give the maid a new diary book."
                    .formatted(meta.writtenCount(), meta.maxEntries());
            case STORAGE_ERROR -> "Failed to save the diary entry due to a storage error.";
        };
    }

    /** 列出该女仆名下可恢复的孤儿文件（未被任何活跃日记引用的历史文件）。 */
    public static List<Path> listRestorable(EntityMaid maid) {
        return DiaryStorage.listOwnedFiles(maid.getUUID()).stream()
                .filter(p -> {
                    UUID uuid = uuidFromFileName(p);
                    return uuid == null || !LIVE_DIARIES.contains(uuid);
                })
                .toList();
    }

    /**
     * 把某孤儿文件的内容恢复到一本新日记本上（新 uuid、绑定当前女仆）。
     * 恢复后容量 = 默认容量 + 已恢复条数，保证新本仍有 DEFAULT_MAX_ENTRIES 的全新写入空间，
     * 避免"写满 → 销毁 → 重制"死循环；旧文件归档为 .archived。
     */
    public static boolean recover(EntityMaid maid, ItemStack newDiary, UUID orphanUuid) {
        DiaryStorage.DiaryFile old = DiaryStorage.load(orphanUuid);
        if (old == null) {
            return false;
        }
        int restored = old.entries.size();
        DiaryMeta meta = new DiaryMeta(UUID.randomUUID(), java.util.Optional.of(maid.getUUID()),
                maid.getName().getString(), DEFAULT_MAX_ENTRIES + restored, restored);

        DiaryStorage.DiaryFile nf = new DiaryStorage.DiaryFile();
        nf.diaryId = meta.diaryUuid().toString();
        nf.ownerMaidId = maid.getUUID().toString();
        nf.createdAt = System.currentTimeMillis();
        nf.updatedAt = nf.createdAt;
        nf.entries = new ArrayList<>(old.entries);

        if (!DiaryStorage.save(meta.diaryUuid(), nf)) {
            return false;
        }
        DiaryStorage.archive(orphanUuid);
        newDiary.set(DiaryMod.DIARY_META.get(), meta);
        LIVE_DIARIES.add(meta.diaryUuid());
        return true;
    }

    private static UUID uuidFromFileName(Path p) {
        String name = p.getFileName().toString();
        if (!name.endsWith(".json")) {
            return null;
        }
        try {
            return UUID.fromString(name.substring(0, name.length() - ".json".length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String t = text.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (t.length() > MAX_TEXT_LENGTH) {
            t = t.substring(0, MAX_TEXT_LENGTH);
        }
        return t;
    }

    private static DiaryStorage.DiaryFile.Entry toFileEntry(DiaryEntry e) {
        DiaryStorage.DiaryFile.Entry f = new DiaryStorage.DiaryFile.Entry();
        f.writtenAt = e.writtenAt();
        f.author = e.author();
        f.text = e.text();
        return f;
    }
}
