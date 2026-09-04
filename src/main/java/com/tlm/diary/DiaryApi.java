package com.tlm.diary;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.handler.BaubleItemHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    /** 备注（AI 命名）长度上限。 */
    public static final int MAX_NOTE_LENGTH = 64;

    /** 待恢复选择的新本登记：maidUuid → 刚绑定的新日记本 uuid（≥2 本孤儿时，供选择后定位目标栈）。 */
    private static final Map<UUID, UUID> PENDING_RECOVERY = new ConcurrentHashMap<>();

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
     * <p>
     * uuid 匹配接受**前缀短 id**（如上下文展示的 8 位短 id），因为 LLM 通常会直接引用上下文里的短 id。
     */
    public static ItemStack findDiary(EntityMaid maid, String diaryUuid) {
        ItemStack fallback = ItemStack.EMPTY;
        for (ItemStack s : findDiaries(maid)) {
            DiaryMeta m = metaOf(s);
            if (diaryUuid == null || diaryUuid.isBlank()) {
                if (fallback.isEmpty()) {
                    fallback = s;
                }
            } else if (isUuidMatch(m.diaryUuid(), diaryUuid)) {
                return s;
            }
        }
        return fallback;
    }

    /** uuid 匹配：完整 uuid 或其前缀（短 id），忽略大小写。 */
    private static boolean isUuidMatch(UUID uuid, String token) {
        if (uuid == null || token == null) {
            return false;
        }
        return uuid.toString().startsWith(token.trim().toLowerCase());
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
            if (diaryUuid != null && !diaryUuid.isBlank() && !findDiaries(maid).isEmpty()) {
                return "No diary book matches diary_uuid '" + diaryUuid
                        + "'. Query the 'diary' context and use one of the ids listed there.";
            }
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
            case SUCCESS -> {
                String base = "Diary entry written successfully (%d/%d)."
                        .formatted(meta.writtenCount(), meta.maxEntries());
                // 无备注：主动提示 AI 命名（事件驱动信号，非轮询）
                yield meta.hasNote() ? base
                        : base + " This diary has no note yet; consider naming it with set_diary_note.";
            }
            case NO_DIARY_BOOK -> "The maid does not have a diary book.";
            case NOT_OWNER -> "This diary book belongs to another maid; you can only read it.";
            case DIARY_FULL -> "The diary book is full (%d/%d). You may ask the owner to give the maid a new diary book."
                    .formatted(meta.writtenCount(), meta.maxEntries());
            case STORAGE_ERROR -> "Failed to save the diary entry due to a storage error.";
        };
    }

    /** 列出该女仆名下可恢复的孤儿（已检测到销毁）文件的 uuid，按最后更新倒序。 */
    public static List<UUID> listRestorable(EntityMaid maid) {
        return DiaryStorage.listOwnedOrphaned(maid.getUUID()).stream()
                .map(DiaryApi::uuidFromFileName)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 正向销毁证据：把该日记文件标记为孤儿（掉落物被岩浆/火焰摧毁、5 分钟消失等）。仅服务端调用；
     * 文件不存在（从未写入内容）则忽略。
     */
    public static void markDestroyed(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        DiaryMeta meta = stack.get(DiaryMod.DIARY_META.get());
        if (meta != null) {
            DiaryStorage.markOrphaned(meta.diaryUuid());
        }
    }

    /**
     * 首次绑定一本全新日记本后调用：若该女仆名下存在已标记销毁的孤儿文件，
     * 按 0/1/≥2 分支执行恢复（0 无操作；1 自动恢复并通知；≥2 打开恢复选择界面）。
     */
    public static void recoverIfOrphaned(EntityMaid maid, ItemStack newDiary) {
        if (newDiary == null || newDiary.isEmpty()) {
            return;
        }
        List<UUID> orphans = listRestorable(maid);
        if (orphans.isEmpty()) {
            return;
        }
        if (orphans.size() == 1) {
            if (recover(maid, newDiary, orphans.get(0))) {
                notifyOwner(maid, "tlm_diary.notify.recovered", fileEntryCount(orphans.get(0)));
            }
            return;
        }
        // ≥2：登记"正在等待玩家选择"的新本目标，并把候选列表发给女仆主人打开选择界面
        DiaryMeta meta = metaOf(newDiary);
        PENDING_RECOVERY.put(maid.getUUID(), meta.diaryUuid());
        openRecoveryScreen(maid, orphans);
    }

    /** 把候选孤儿列表打包为 payload 发给女仆主人（客户端打开恢复选择界面）。 */
    private static void openRecoveryScreen(EntityMaid maid, List<UUID> orphans) {
        if (!(maid.getOwner() instanceof ServerPlayer owner)) {
            DiaryMod.LOGGER.debug("Skip recovery screen: owner of maid {} is not an online player", maid.getUUID());
            return;
        }
        List<ClientboundDiaryRecoveryPayload.Candidate> candidates = new ArrayList<>();
        for (UUID uuid : orphans) {
            DiaryStorage.DiaryFile file = DiaryStorage.load(uuid);
            if (file == null) {
                continue;
            }
            String preview = "";
            if (!file.entries.isEmpty()) {
                preview = file.entries.get(file.entries.size() - 1).text;
                if (preview.length() > 40) {
                    preview = preview.substring(0, 40) + "...";
                }
            }
            candidates.add(new ClientboundDiaryRecoveryPayload.Candidate(
                    uuid, file.entries.size(), file.updatedAt, preview));
        }
        if (candidates.isEmpty()) {
            return;
        }
        PacketDistributor.sendToPlayer(owner, new ClientboundDiaryRecoveryPayload(maid.getUUID(), candidates));
    }

    /** 服务端处理玩家在恢复选择界面点选的孤儿文件。 */
    public static void handleRecoverChoice(ServerPlayer player, UUID maidUuid, UUID orphanUuid) {
        UUID newDiaryUuid = PENDING_RECOVERY.remove(maidUuid);
        if (newDiaryUuid == null || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        Entity entity = serverLevel.getEntity(maidUuid);
        if (!(entity instanceof EntityMaid maid)) {
            return;
        }
        ItemStack diary = findDiary(maid, newDiaryUuid.toString());
        if (diary.isEmpty()) {
            return;
        }
        if (recover(maid, diary, orphanUuid)) {
            notifyOwner(maid, "tlm_diary.notify.recovered", fileEntryCount(orphanUuid));
        }
    }

    private static void notifyOwner(EntityMaid maid, String langKey, Object... args) {
        if (maid.getOwner() instanceof ServerPlayer owner) {
            owner.sendSystemMessage(Component.translatable(langKey, args));
        }
    }

    private static int fileEntryCount(UUID uuid) {
        DiaryStorage.DiaryFile file = DiaryStorage.load(uuid);
        return file == null ? 0 : file.entries.size();
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
        String oldNote = old.note == null ? "" : old.note;
        DiaryMeta meta = new DiaryMeta(UUID.randomUUID(), java.util.Optional.of(maid.getUUID()),
                maid.getName().getString(), oldNote, old.locked,
                DEFAULT_MAX_ENTRIES + restored, restored);

        DiaryStorage.DiaryFile nf = new DiaryStorage.DiaryFile();
        nf.diaryId = meta.diaryUuid().toString();
        nf.ownerMaidId = maid.getUUID().toString();
        nf.createdAt = System.currentTimeMillis();
        nf.updatedAt = nf.createdAt;
        nf.note = oldNote;
        nf.locked = old.locked;
        nf.ownerReadAt = old.ownerReadAt;
        nf.ownerReadThrough = old.ownerReadThrough;
        nf.ownerReadForced = old.ownerReadForced;
        nf.entries = new ArrayList<>(old.entries);

        if (!DiaryStorage.save(meta.diaryUuid(), nf)) {
            return false;
        }
        DiaryStorage.archive(orphanUuid);
        // 清理新本原 uuid 在绑定时可能创建的空文件，避免残留
        DiaryMeta currentMeta = newDiary.get(DiaryMod.DIARY_META.get());
        UUID oldUuid = currentMeta == null ? null : currentMeta.diaryUuid();
        newDiary.set(DiaryMod.DIARY_META.get(), meta);
        if (oldUuid != null && !oldUuid.equals(orphanUuid)) {
            DiaryStorage.deleteIfEmpty(oldUuid);
        }
        return true;
    }

    /**
     * 供 AI 工具调用：设置/修改某本日记的备注（仅绑定女仆本人，AI 专属，玩家无入口）。
     * 无备注时为"命名"；有备注时为"修改"（旧值随结果返回，便于 AI 自查是否必要）。
     */
    public static String setNoteForAi(EntityMaid maid, String note, String diaryUuid) {
        ItemStack diary = findDiary(maid, diaryUuid);
        if (diary.isEmpty()) {
            if (diaryUuid != null && !diaryUuid.isBlank() && !findDiaries(maid).isEmpty()) {
                return "No diary book matches diary_uuid '" + diaryUuid
                        + "'. Query the 'diary' context and use one of the ids listed there.";
            }
            return "The maid does not have a diary book. Ask the owner for one and bind it first.";
        }
        DiaryMeta meta = metaOf(diary);
        if (!meta.isOwner(maid.getUUID())) {
            return "This diary book is not bound to you; you can only name diaries that belong to you.";
        }
        if (note == null || note.isBlank()) {
            return "The note must not be empty.";
        }
        String clean = note.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (clean.length() > MAX_NOTE_LENGTH) {
            clean = clean.substring(0, MAX_NOTE_LENGTH);
        }
        String old = meta.note() == null ? "" : meta.note();
        // 组件 + 外部文件双写（文件供恢复迁移时携带备注）
        DiaryStorage.DiaryFile file = DiaryStorage.load(meta.diaryUuid());
        if (file != null) {
            file.note = clean;
            file.updatedAt = System.currentTimeMillis();
            DiaryStorage.save(meta.diaryUuid(), file);
        }
        diary.set(DiaryMod.DIARY_META.get(), meta.withNote(clean));
        String shortUuid = meta.diaryUuid().toString().substring(0, 8);
        if (old.isBlank()) {
            return "Note '%s' set for diary %s.".formatted(clean, shortUuid);
        }
        return "Note updated from '%s' to '%s'.".formatted(old, clean);
    }

    /**
     * 主人成功阅读某本日记后记录（事件驱动）：写入读时刻、读到条目数（=阅读时全书条目数）与是否强开。
     * 供 DiaryContext 生成"主人读过"提示。
     */
    public static void recordOwnerRead(ItemStack diary) {
        if (diary == null || diary.isEmpty()) {
            return;
        }
        DiaryMeta meta = diary.get(DiaryMod.DIARY_META.get());
        if (meta == null || !meta.isBound()) {
            return;
        }
        DiaryStorage.DiaryFile file = DiaryStorage.load(meta.diaryUuid());
        if (file == null) {
            return;
        }
        file.ownerReadAt = System.currentTimeMillis();
        file.ownerReadThrough = file.entries.size();
        file.ownerReadForced = meta.locked();
        file.updatedAt = System.currentTimeMillis();
        DiaryStorage.save(meta.diaryUuid(), file);
    }

    /**
     * 供 AI 工具调用：给某本日记上锁/解锁（仅绑定女仆本人，AI 专属，玩家无工具）。
     * 上锁 = 普通右键被拦截、潜行右键可强开（强开会被记录并在上下文提示 AI）。
     */
    public static String setLockForAi(EntityMaid maid, boolean locked, String diaryUuid) {
        ItemStack diary = findDiary(maid, diaryUuid);
        if (diary.isEmpty()) {
            if (diaryUuid != null && !diaryUuid.isBlank() && !findDiaries(maid).isEmpty()) {
                return "No diary book matches diary_uuid '" + diaryUuid
                        + "'. Query the 'diary' context and use one of the ids listed there.";
            }
            return "The maid does not have a diary book. Ask the owner for one and bind it first.";
        }
        DiaryMeta meta = metaOf(diary);
        if (!meta.isOwner(maid.getUUID())) {
            return "This diary book is not bound to you; you can only lock diaries that belong to you.";
        }
        DiaryStorage.DiaryFile file = DiaryStorage.load(meta.diaryUuid());
        if (file != null) {
            file.locked = locked;
            file.updatedAt = System.currentTimeMillis();
            DiaryStorage.save(meta.diaryUuid(), file);
        }
        diary.set(DiaryMod.DIARY_META.get(), meta.withLocked(locked));
        String shortUuid = meta.diaryUuid().toString().substring(0, 8);
        if (locked) {
            return "Diary %s is now locked: the owner cannot open it with a normal right-click "
                    + "(a sneaking right-click would force it open, which you would be told about).".formatted(shortUuid);
        }
        return "Diary %s is now unlocked.".formatted(shortUuid);
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
