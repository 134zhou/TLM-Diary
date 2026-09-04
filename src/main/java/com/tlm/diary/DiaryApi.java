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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日记写读的统一入口（权限收敛到服务端）。
 * <p>
 * 权限模型：女仆日记（ownerType=maid）——绑定女仆可读可写，其他女仆只读，玩家只读；
 * 玩家日记（ownerType=player）——玩家可读可写，其女仆可读并写评语。
 */
public final class DiaryApi {

    public static final int DEFAULT_MAX_ENTRIES = 50;
    public static final int MAX_TEXT_LENGTH = 1024;
    /** 备注（AI 命名）长度上限。 */
    public static final int MAX_NOTE_LENGTH = 64;
    public static final int MAX_AI_LIMIT = 10;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 待恢复选择的新本登记：maidUuid → 刚绑定的新日记本 uuid（≥2 本孤儿时，供选择后定位目标栈）。 */
    private static final Map<UUID, UUID> PENDING_RECOVERY = new ConcurrentHashMap<>();

    /** 玩家日记"未评论"信号缓存：playerUuid → 其名下玩家日记信号（事件驱动刷新，供自动注入 Context 零文件 IO 读取）。 */
    private static final Map<UUID, List<PlayerDiarySignal>> PLAYER_DIARY_SIGNALS = new ConcurrentHashMap<>();

    private DiaryApi() {
    }

    public enum WriteResult {
        SUCCESS, NO_DIARY_BOOK, NOT_OWNER, DIARY_FULL, STORAGE_ERROR
    }

    public record PlayerDiarySignal(UUID diaryUuid, String note, int totalEntries, int uncommentedEntries) {
        public String shortId() {
            return diaryUuid.toString().substring(0, 8);
        }

        public boolean hasNote() {
            return note != null && !note.isBlank();
        }
    }

    private record PlayerDiary(UUID uuid, DiaryStorage.DiaryFile file) {
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
        if (meta == null || !meta.isOwnerMaid(maid.getUUID())) {
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
        if (meta == null || !meta.isMaidBound()) {
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
        return uuid.toString().startsWith(token.trim().toLowerCase(Locale.ROOT));
    }

    /** 服务端写一条女仆日记，返回结果枚举。 */
    public static WriteResult writeEntry(EntityMaid maid, ItemStack diary, String author, String text) {
        if (diary == null || diary.isEmpty() || !diary.is(DiaryMod.DIARY_BOOK.get())) {
            return WriteResult.NO_DIARY_BOOK;
        }
        DiaryMeta meta = metaOf(diary);
        UUID maidUuid = maid.getUUID();

        if (meta.isBound() && !meta.isOwnerMaid(maidUuid)) {
            return WriteResult.NOT_OWNER;
        }
        // 事件驱动刷新 ownerName 回退缓存（写日记时有 maid 实体在手，零查询零轮询）
        if (!meta.isBound()) {
            meta = meta.withOwner(maidUuid, DiaryMeta.OWNER_TYPE_MAID).withOwnerName(maid.getName().getString());
        } else {
            meta = meta.withOwnerName(maid.getName().getString());
        }
        if (meta.isFull()) {
            return WriteResult.DIARY_FULL;
        }

        String clean = sanitize(text);
        DiaryEntry entry = new DiaryEntry(System.currentTimeMillis(), author, clean, List.of());

        DiaryStorage.DiaryFile file = DiaryStorage.load(meta.diaryUuid());
        if (file == null) {
            file = new DiaryStorage.DiaryFile();
            file.diaryId = meta.diaryUuid().toString();
            file.createdAt = System.currentTimeMillis();
        }
        file.ownerMaidId = maidUuid.toString();
        file.ownerType = DiaryStorage.OWNER_TYPE_MAID;
        file.ownerId = maidUuid.toString();
        file.entries.add(toFileEntry(entry, DiaryStorage.WRITER_MAID));
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
            out.add(toEntry(e));
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
        DiaryMeta meta = metaOf(diary);
        if (meta.isPlayerBound()) {
            return "This is the owner's diary, not your own. You cannot write your own entries here; "
                    + "read it with read_player_diary and leave comments with comment_diary.";
        }
        String author = maid.getName().getString();
        String text = content;
        if (emotion != null && !emotion.isBlank()) {
            text = "[" + emotion + "] " + content;
        }
        WriteResult r = writeEntry(maid, diary, author, text);
        meta = metaOf(diary);
        return switch (r) {
            case SUCCESS -> {
                String base = "Diary entry written successfully (%d/%d)."
                        .formatted(meta.writtenCount(), meta.maxEntries());
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
        return DiaryStorage.listOwnedOrphaned(maid.getUUID(), DiaryStorage.OWNER_TYPE_MAID).stream()
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
     * 恢复后容量 = 默认容量 + 已恢复条数；旧文件归档为 .archived。
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
                DEFAULT_MAX_ENTRIES + restored, restored, DiaryMeta.OWNER_TYPE_MAID);

        DiaryStorage.DiaryFile nf = new DiaryStorage.DiaryFile();
        nf.diaryId = meta.diaryUuid().toString();
        nf.ownerMaidId = maid.getUUID().toString();
        nf.ownerType = DiaryStorage.OWNER_TYPE_MAID;
        nf.ownerId = maid.getUUID().toString();
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
        DiaryMeta currentMeta = newDiary.get(DiaryMod.DIARY_META.get());
        UUID oldUuid = currentMeta == null ? null : currentMeta.diaryUuid();
        newDiary.set(DiaryMod.DIARY_META.get(), meta);
        if (oldUuid != null && !oldUuid.equals(orphanUuid)) {
            DiaryStorage.deleteIfEmpty(oldUuid);
        }
        return true;
    }

    /**
     * 供 AI 工具调用：设置/修改某本日记的备注。
     * 女仆日记仅绑定女仆本人可命名；玩家日记允许该主人女仆命名（便于区分多本）。
     */
    public static String setNoteForAi(EntityMaid maid, String note, String diaryUuid) {
        ItemStack diary = findDiary(maid, diaryUuid);
        if (!diary.isEmpty()) {
            DiaryMeta meta = metaOf(diary);
            if (!meta.isOwnerMaid(maid.getUUID())) {
                return "This diary book is not bound to you; you can only name diaries that belong to you.";
            }
            return setNoteOnStack(maid, diary, meta, note);
        }
        // 玩家日记：文件级查找（玩家日记不一定在女仆身上）。
        PlayerDiary pd = findPlayerDiary(maid, diaryUuid);
        if (pd != null) {
            String clean = sanitizeNote(note);
            if (clean == null) {
                return "The note must not be empty.";
            }
            String old = pd.file().note == null ? "" : pd.file().note;
            pd.file().note = clean;
            pd.file().updatedAt = System.currentTimeMillis();
            if (!DiaryStorage.save(pd.uuid(), pd.file())) {
                return "Failed to save the diary note due to a storage error.";
            }
            refreshPlayerDiarySignals(maid.getOwnerUUID());
            String shortUuid = pd.uuid().toString().substring(0, 8);
            return old.isBlank() ? "Note '%s' set for diary %s.".formatted(clean, shortUuid)
                    : "Note updated from '%s' to '%s'.".formatted(old, clean);
        }
        if (diaryUuid != null && !diaryUuid.isBlank() && !findPlayerDiaries(maid).isEmpty()) {
            return "No diary book matches diary_uuid '" + diaryUuid
                    + "'. Query the 'diary' context and use one of the ids listed there.";
        }
        return "The maid does not have a diary book. Ask the owner for one and bind it first.";
    }

    private static String setNoteOnStack(EntityMaid maid, ItemStack diary, DiaryMeta meta, String note) {
        String clean = sanitizeNote(note);
        if (clean == null) {
            return "The note must not be empty.";
        }
        String old = meta.note() == null ? "" : meta.note();
        DiaryStorage.DiaryFile file = DiaryStorage.load(meta.diaryUuid());
        if (file != null) {
            file.note = clean;
            file.updatedAt = System.currentTimeMillis();
            DiaryStorage.save(meta.diaryUuid(), file);
        }
        diary.set(DiaryMod.DIARY_META.get(), meta.withNote(clean));
        String shortUuid = meta.diaryUuid().toString().substring(0, 8);
        return old.isBlank() ? "Note '%s' set for diary %s.".formatted(clean, shortUuid)
                : "Note updated from '%s' to '%s'.".formatted(old, clean);
    }

    /**
     * 主人成功阅读某本**女仆日记**后记录（事件驱动）：写入读时刻、读到条目数（=阅读时全书条目数）与是否强开。
     * 玩家日记不记录 ownerRead。
     */
    public static void recordOwnerRead(ItemStack diary) {
        if (diary == null || diary.isEmpty()) {
            return;
        }
        DiaryMeta meta = diary.get(DiaryMod.DIARY_META.get());
        if (meta == null || !meta.isMaidBound()) {
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
     * 供 AI 工具调用：给某本女仆日记上锁/解锁（仅绑定女仆本人，玩家日记不支持）。
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
        if (!meta.isMaidBound()) {
            return "Only a maid-bound diary can be locked; the owner's diary has no lock.";
        }
        if (!meta.isOwnerMaid(maid.getUUID())) {
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

    // ---------- 玩家日记 ----------

    /** 把未绑定日记本绑定给玩家（玩家日记）。 */
    public static Component bindToPlayer(ServerPlayer player, ItemStack diary) {
        if (diary == null || diary.isEmpty() || !diary.is(DiaryMod.DIARY_BOOK.get())) {
            return Component.translatable("tlm_diary.bind.invalid");
        }
        DiaryMeta meta = metaOf(diary);
        if (meta.isBound()) {
            return Component.translatable("tlm_diary.bind.already_bound");
        }
        String name = player.getName().getString();
        meta = meta.withOwner(player.getUUID(), DiaryMeta.OWNER_TYPE_PLAYER).withOwnerName(name);
        diary.set(DiaryMod.DIARY_META.get(), meta);

        DiaryStorage.DiaryFile file = DiaryStorage.load(meta.diaryUuid());
        if (file == null) {
            file = new DiaryStorage.DiaryFile();
            file.diaryId = meta.diaryUuid().toString();
            file.createdAt = System.currentTimeMillis();
        }
        file.ownerType = DiaryStorage.OWNER_TYPE_PLAYER;
        file.ownerId = player.getUUID().toString();
        file.ownerMaidId = null;
        file.updatedAt = System.currentTimeMillis();
        if (!DiaryStorage.save(meta.diaryUuid(), file)) {
            return Component.translatable("tlm_diary.bind.error");
        }
        refreshPlayerDiarySignals(player.getUUID());
        return Component.translatable("tlm_diary.bind.success", name);
    }

    /** 玩家在玩家日记里写一条条目，返回给玩家的提示 Component。 */
    public static Component writeForPlayer(ServerPlayer player, ItemStack diary, String text) {
        if (diary == null || diary.isEmpty() || !diary.is(DiaryMod.DIARY_BOOK.get())) {
            return Component.translatable("tlm_diary.write.invalid");
        }
        DiaryMeta meta = metaOf(diary);
        if (!meta.isBound()) {
            return Component.translatable("tlm_diary.write.not_bound");
        }
        if (!meta.isPlayerBound()) {
            return Component.translatable("tlm_diary.write.maid_diary_readonly");
        }
        if (meta.isFull()) {
            return Component.translatable("tlm_diary.write.full", meta.writtenCount(), meta.maxEntries());
        }
        String clean = sanitize(text);
        if (clean.isBlank()) {
            return Component.translatable("tlm_diary.write.empty");
        }

        DiaryStorage.DiaryFile file = DiaryStorage.load(meta.diaryUuid());
        if (file == null) {
            file = new DiaryStorage.DiaryFile();
            file.diaryId = meta.diaryUuid().toString();
            file.createdAt = System.currentTimeMillis();
        }
        file.ownerType = DiaryStorage.OWNER_TYPE_PLAYER;
        file.ownerId = meta.ownerUuid().get().toString();
        file.ownerMaidId = null;

        DiaryStorage.DiaryFile.Entry entry = new DiaryStorage.DiaryFile.Entry();
        entry.writtenAt = System.currentTimeMillis();
        entry.author = player.getName().getString();
        entry.text = clean;
        entry.writerKind = DiaryStorage.WRITER_PLAYER;
        entry.comments = new ArrayList<>();
        file.entries.add(entry);
        file.updatedAt = System.currentTimeMillis();

        if (!DiaryStorage.save(meta.diaryUuid(), file)) {
            return Component.translatable("tlm_diary.write.error");
        }
        diary.set(DiaryMod.DIARY_META.get(), meta.withWrittenCount(file.entries.size()));
        refreshPlayerDiarySignals(meta.ownerUuid().get());
        return Component.translatable("tlm_diary.write.success", file.entries.size());
    }

    /** 列出主人名下的玩家日记 uuid，按 updatedAt 倒序。 */
    public static List<UUID> findPlayerDiaries(EntityMaid maid) {
        UUID owner = maid.getOwnerUUID();
        if (owner == null) {
            return List.of();
        }
        return DiaryStorage.listOwnedFiles(owner, DiaryStorage.OWNER_TYPE_PLAYER).stream()
                .map(DiaryApi::uuidFromFileName)
                .filter(Objects::nonNull)
                .toList();
    }

    /** 定位主人的玩家日记：diaryUuid 为空取最近更新的一本；否则取前缀匹配的那本。 */
    public static PlayerDiary findPlayerDiary(EntityMaid maid, String diaryUuid) {
        List<UUID> uuids = findPlayerDiaries(maid);
        if (uuids.isEmpty()) {
            return null;
        }
        UUID target = null;
        if (diaryUuid == null || diaryUuid.isBlank()) {
            target = uuids.get(0);
        } else {
            for (UUID u : uuids) {
                if (isUuidMatch(u, diaryUuid)) {
                    target = u;
                    break;
                }
            }
        }
        if (target == null) {
            return null;
        }
        DiaryStorage.DiaryFile file = DiaryStorage.load(target);
        return file == null ? null : new PlayerDiary(target, file);
    }

    /** 供 AI 读取主人玩家日记（含每条已有评语）。 */
    public static String readPlayerDiaryForAi(EntityMaid maid, String diaryUuid, String mode,
                                              Integer from, Integer limit, String query) {
        PlayerDiary pd = findPlayerDiary(maid, diaryUuid);
        if (pd == null) {
            if (diaryUuid != null && !diaryUuid.isBlank() && !findPlayerDiaries(maid).isEmpty()) {
                return "No owner's diary matches diary_uuid '" + diaryUuid
                        + "'. Query the 'diary' context and use one of the ids listed there.";
            }
            return "The owner does not have a diary book bound to them yet.";
        }
        DiaryStorage.DiaryFile file = pd.file();
        int total = file.entries.size();
        String label = noteLabel(file.note);
        String shortId = pd.uuid().toString().substring(0, 8);
        if (total == 0) {
            return "Owner's diary " + label + " " + shortId + " is empty.";
        }

        String m = mode == null || mode.isBlank() ? "latest" : mode.trim().toLowerCase(Locale.ROOT);
        int lim = clampLimit(limit);
        int start;
        int end;
        StringBuilder body = new StringBuilder();

        switch (m) {
            case "latest" -> {
                start = Math.max(0, total - lim);
                end = total;
                body.append("Owner's diary ").append(label).append(' ').append(shortId)
                        .append(", ").append(total).append(" entries (showing latest ").append(end - start).append("):\n");
                appendEntries(body, file.entries, start, end);
                body.append("(Showing #").append(start + 1).append("-#").append(end)
                        .append(" of ").append(total).append(", oldest to newest.");
                if (start > 0) {
                    body.append(" Use read_player_diary with mode=range, from=1, limit=").append(lim)
                            .append(" to read older entries.)");
                } else {
                    body.append(")");
                }
            }
            case "range" -> {
                int fromVal = from == null ? 1 : Math.max(1, from);
                if (fromVal > total) {
                    return "No entries in that range. Owner's diary " + label + " " + shortId
                            + " has " + total + " entries.";
                }
                start = fromVal - 1;
                end = Math.min(total, start + lim);
                body.append("Owner's diary ").append(label).append(' ').append(shortId)
                        .append(", ").append(total).append(" entries (showing #").append(start + 1)
                        .append("-#").append(end).append("):\n");
                appendEntries(body, file.entries, start, end);
                if (end < total) {
                    body.append("(Use read_player_diary with mode=range, from=").append(end + 1)
                            .append(", limit=").append(lim).append(" to continue.)");
                }
            }
            case "search" -> {
                if (query == null || query.isBlank()) {
                    return "mode=search requires a non-empty query.";
                }
                String q = query.toLowerCase(Locale.ROOT);
                List<Integer> hits = new ArrayList<>();
                for (int i = 0; i < total; i++) {
                    DiaryStorage.DiaryFile.Entry e = file.entries.get(i);
                    if ((e.text != null && e.text.toLowerCase(Locale.ROOT).contains(q))
                            || (e.author != null && e.author.toLowerCase(Locale.ROOT).contains(q))) {
                        hits.add(i);
                    }
                }
                if (hits.isEmpty()) {
                    return "No entries matched '" + query + "' in owner's diary " + label + " " + shortId + ".";
                }
                body.append("Owner's diary ").append(label).append(' ').append(shortId)
                        .append(", ").append(hits.size()).append(" match(es) for \"").append(query).append("\":\n");
                int fromHit = Math.max(0, hits.size() - lim);
                int count = 0;
                for (int i = fromHit; i < hits.size(); i++) {
                    int idx = hits.get(i);
                    appendEntry(body, file.entries.get(idx), idx);
                    count++;
                }
                if (fromHit > 0) {
                    body.append("(Showing the ").append(count).append(" most recent match(es); ")
                            .append(fromHit).append(" older match(es) not shown. Narrow the query to see specific ones.)");
                }
            }
            default -> {
                return "Unknown mode '" + mode + "'. Use one of: latest, range, search.";
            }
        }
        return body.toString();
    }

    private static void appendEntries(StringBuilder body, List<DiaryStorage.DiaryFile.Entry> entries, int start, int end) {
        for (int i = start; i < end; i++) {
            appendEntry(body, entries.get(i), i);
        }
    }

    private static void appendEntry(StringBuilder body, DiaryStorage.DiaryFile.Entry e, int index) {
        body.append('#').append(index + 1).append(" [").append(time(e.writtenAt)).append("] ")
                .append(e.author == null ? "?" : e.author).append(": ").append(e.text == null ? "" : e.text).append('\n');
        if (e.comments != null) {
            for (DiaryStorage.DiaryFile.Comment c : e.comments) {
                body.append("    - comment [").append(time(c.writtenAt)).append("] ")
                        .append(c.author == null ? "?" : c.author).append(": ").append(c.text == null ? "" : c.text).append('\n');
            }
        }
    }

    /** 供 AI 在主人玩家日记的某条条目下写评语。 */
    public static String commentForAi(EntityMaid maid, String diaryUuid, Integer entryNumber, String content) {
        PlayerDiary pd = findPlayerDiary(maid, diaryUuid);
        if (pd == null) {
            if (diaryUuid != null && !diaryUuid.isBlank() && !findPlayerDiaries(maid).isEmpty()) {
                return "No owner's diary matches diary_uuid '" + diaryUuid
                        + "'. Query the 'diary' context and use one of the ids listed there.";
            }
            return "The owner does not have a diary book bound to them yet.";
        }
        int total = pd.file().entries.size();
        if (entryNumber == null || entryNumber < 1 || entryNumber > total) {
            return "Entry #" + entryNumber + " does not exist. The owner's diary has " + total + " entries.";
        }
        String clean = sanitize(content);
        if (clean.isBlank()) {
            return "The comment must not be empty.";
        }
        DiaryStorage.DiaryFile.Entry e = pd.file().entries.get(entryNumber - 1);
        if (e.comments == null) {
            e.comments = new ArrayList<>();
        }
        DiaryStorage.DiaryFile.Comment c = new DiaryStorage.DiaryFile.Comment();
        c.writtenAt = System.currentTimeMillis();
        c.author = maid.getName().getString();
        c.text = clean;
        e.comments.add(c);
        pd.file().updatedAt = System.currentTimeMillis();
        if (!DiaryStorage.save(pd.uuid(), pd.file())) {
            return "Failed to save the comment due to a storage error.";
        }
        refreshPlayerDiarySignals(maid.getOwnerUUID());
        return "Comment added under entry #" + entryNumber + " of owner's diary "
                + noteLabel(pd.file().note) + " " + pd.uuid().toString().substring(0, 8) + ".";
    }

    // ---------- 玩家日记信号 ----------

    public static List<PlayerDiarySignal> getPlayerDiarySignals(EntityMaid maid) {
        UUID owner = maid.getOwnerUUID();
        if (owner == null) {
            return List.of();
        }
        List<PlayerDiarySignal> cached = PLAYER_DIARY_SIGNALS.get(owner);
        if (cached == null) {
            cached = computePlayerDiarySignals(owner);
            PLAYER_DIARY_SIGNALS.put(owner, cached);
        }
        return cached;
    }

    public static void refreshPlayerDiarySignals(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        PLAYER_DIARY_SIGNALS.put(playerUuid, computePlayerDiarySignals(playerUuid));
    }

    private static List<PlayerDiarySignal> computePlayerDiarySignals(UUID owner) {
        List<PlayerDiarySignal> out = new ArrayList<>();
        for (Path p : DiaryStorage.listOwnedFiles(owner, DiaryStorage.OWNER_TYPE_PLAYER)) {
            UUID uuid = uuidFromFileName(p);
            if (uuid == null) {
                continue;
            }
            DiaryStorage.DiaryFile file = DiaryStorage.load(uuid);
            if (file == null) {
                continue;
            }
            out.add(new PlayerDiarySignal(uuid, file.note, file.entries.size(),
                    DiaryStorage.countUncommentedPlayerEntries(file)));
        }
        return out;
    }

    // ---------- 工具方法 ----------

    private static String noteLabel(String note) {
        return note == null || note.isBlank() ? "<unnamed>" : "'" + note + "'";
    }

    private static int clampLimit(Integer limit) {
        if (limit == null) {
            return 5;
        }
        return Math.max(1, Math.min(MAX_AI_LIMIT, limit));
    }

    private static String time(long epochMillis) {
        return TIME.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
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

    private static String sanitizeNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String clean = note.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (clean.length() > MAX_NOTE_LENGTH) {
            clean = clean.substring(0, MAX_NOTE_LENGTH);
        }
        return clean.isBlank() ? null : clean;
    }

    private static DiaryStorage.DiaryFile.Entry toFileEntry(DiaryEntry e, String writerKind) {
        DiaryStorage.DiaryFile.Entry f = new DiaryStorage.DiaryFile.Entry();
        f.writtenAt = e.writtenAt();
        f.author = e.author();
        f.text = e.text();
        f.writerKind = writerKind;
        f.comments = new ArrayList<>();
        return f;
    }

    private static DiaryEntry toEntry(DiaryStorage.DiaryFile.Entry e) {
        List<DiaryComment> comments = new ArrayList<>();
        if (e.comments != null) {
            for (DiaryStorage.DiaryFile.Comment c : e.comments) {
                comments.add(new DiaryComment(c.writtenAt, c.author, c.text));
            }
        }
        return new DiaryEntry(e.writtenAt, e.author, e.text, comments);
    }
}
