package com.tlm.diary;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 日记内容的持久化层。
 * <p>
 * 主存储为游戏目录下的外部文件：{@code <游戏目录>/tlm_diary/diaries/<uuid>.json}，
 * 与 saves/ 平级，因此跨存档共享。由模组独占写入（原子写），普通用户只读（用于备份/分享）。
 */
public final class DiaryStorage {

    public static final String OWNER_TYPE_MAID = "maid";
    public static final String OWNER_TYPE_PLAYER = "player";
    public static final String WRITER_MAID = "maid";
    public static final String WRITER_PLAYER = "player";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private DiaryStorage() {
    }

    public static Path diariesDir() {
        return FMLPaths.GAMEDIR.get().resolve("tlm_diary").resolve("diaries");
    }

    public static Path file(UUID diaryUuid) {
        return diariesDir().resolve(diaryUuid + ".json");
    }

    /** 读取日记文件；不存在返回 null；损坏则备份为 .bak 并返回 null（视为空日记）。 */
    public static DiaryFile load(UUID diaryUuid) {
        Path f = file(diaryUuid);
        if (!Files.exists(f)) {
            return null;
        }
        try {
            String json = Files.readString(f, StandardCharsets.UTF_8);
            return normalize(GSON.fromJson(json, DiaryFile.class));
        } catch (Exception e) {
            DiaryMod.LOGGER.error("Failed to read diary file {}, treating it as corrupted", f, e);
            backupCorrupted(f);
            return null;
        }
    }

    /** 兼容旧档：旧文件只有 ownerMaidId，缺省推断为女仆日记；条目缺省 writerKind/comment 也在此补齐。 */
    private static DiaryFile normalize(DiaryFile df) {
        if (df == null) {
            return null;
        }
        if (df.ownerType == null || df.ownerType.isBlank()) {
            if (df.ownerId == null || df.ownerId.isBlank()) {
                df.ownerType = df.ownerMaidId == null || df.ownerMaidId.isBlank() ? "" : OWNER_TYPE_MAID;
                df.ownerId = df.ownerMaidId;
            } else {
                df.ownerType = "";
            }
        } else if (df.ownerId == null || df.ownerId.isBlank()) {
            if (OWNER_TYPE_MAID.equals(df.ownerType)) {
                df.ownerId = df.ownerMaidId;
            }
        }
        if (df.entries == null) {
            df.entries = new ArrayList<>();
        }
        for (DiaryFile.Entry e : df.entries) {
            if (e.writerKind == null || e.writerKind.isBlank()) {
                e.writerKind = WRITER_MAID;
            }
            if (e.comments == null) {
                e.comments = new ArrayList<>();
            }
        }
        return df;
    }

    private static void backupCorrupted(Path f) {
        try {
            Path bak = f.resolveSibling(f.getFileName() + ".bak");
            Files.copy(f, bak, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(f);
        } catch (IOException ignored) {
            DiaryMod.LOGGER.error("Failed to back up corrupted diary file {}", f);
        }
    }

    /** 原子写：先写临时文件，再 rename 覆盖目标，避免写一半损坏。 */
    public static boolean save(UUID diaryUuid, DiaryFile diary) {
        Path dir = diariesDir();
        Path target = file(diaryUuid);
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(diaryUuid + ".json.tmp");
            Files.writeString(tmp, GSON.toJson(diary), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            DiaryMod.LOGGER.error("Failed to save diary file {}", target, e);
            return false;
        }
    }

    /** 归档：恢复迁移完成后把旧孤儿文件改名 .archived 保留，供回溯。 */
    public static void archive(UUID diaryUuid) {
        Path f = file(diaryUuid);
        if (!Files.exists(f)) {
            return;
        }
        try {
            Path archived = f.resolveSibling(f.getFileName() + ".archived");
            Files.move(f, archived, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            DiaryMod.LOGGER.error("Failed to archive diary file {}", f, e);
        }
    }

    /** 列出某女仆名下（ownerMaidId 匹配）的全部日记文件，按 updatedAt 倒序。 */
    public static List<Path> listOwnedFiles(UUID ownerUuid) {
        return listOwnedFiles(ownerUuid, OWNER_TYPE_MAID);
    }

    /** 列出某绑定对象（ownerId + ownerType 匹配）名下的全部日记文件，按 updatedAt 倒序。 */
    public static List<Path> listOwnedFiles(UUID ownerUuid, String ownerType) {
        return listFiles(ownerUuid, ownerType, false);
    }

    /** 列出某女仆名下已标记孤儿（正向销毁证据）的日记文件，按 updatedAt 倒序。 */
    public static List<Path> listOwnedOrphaned(UUID ownerUuid) {
        return listOwnedOrphaned(ownerUuid, OWNER_TYPE_MAID);
    }

    /** 列出某绑定对象名下已标记孤儿的日记文件，按 updatedAt 倒序。 */
    public static List<Path> listOwnedOrphaned(UUID ownerUuid, String ownerType) {
        return listFiles(ownerUuid, ownerType, true);
    }

    private static List<Path> listFiles(UUID ownerUuid, String ownerType, boolean orphanedOnly) {
        Path dir = diariesDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        String owner = ownerUuid == null ? null : ownerUuid.toString();
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> {
                        DiaryFile df = readQuietly(p);
                        if (df == null) {
                            return false;
                        }
                        if (orphanedOnly && !df.orphaned) {
                            return false;
                        }
                        return ownerType.equals(df.ownerType) && owner != null && owner.equals(df.ownerId);
                    })
                    .sorted(Comparator.comparingLong((Path p) -> {
                        DiaryFile df = readQuietly(p);
                        return df == null ? 0L : df.updatedAt;
                    }).reversed())
                    .toList();
        } catch (IOException e) {
            DiaryMod.LOGGER.error("Failed to list diary files", e);
            return List.of();
        }
    }

    /** 正向销毁证据：把文件标记为孤儿并保存；文件不存在或已标记则忽略。 */
    public static void markOrphaned(UUID diaryUuid) {
        DiaryFile file = load(diaryUuid);
        if (file == null || file.orphaned) {
            return;
        }
        file.orphaned = true;
        file.updatedAt = System.currentTimeMillis();
        save(diaryUuid, file);
    }

    /** 删除某本日记的文件（仅用于清理"恢复后遗弃的空文件"）。 */
    public static void deleteIfEmpty(UUID diaryUuid) {
        DiaryFile file = load(diaryUuid);
        if (file != null && !file.orphaned && file.entries.isEmpty()) {
            try {
                Files.deleteIfExists(file(diaryUuid));
            } catch (IOException e) {
                DiaryMod.LOGGER.error("Failed to delete empty diary file {}", file(diaryUuid), e);
            }
        }
    }

    public static boolean isPlayerEntry(DiaryFile.Entry e) {
        return e != null && WRITER_PLAYER.equals(e.writerKind);
    }

    public static int countPlayerEntries(DiaryFile file) {
        if (file == null || file.entries == null) {
            return 0;
        }
        int count = 0;
        for (DiaryFile.Entry e : file.entries) {
            if (isPlayerEntry(e)) {
                count++;
            }
        }
        return count;
    }

    public static int countUncommentedPlayerEntries(DiaryFile file) {
        if (file == null || file.entries == null) {
            return 0;
        }
        int count = 0;
        for (DiaryFile.Entry e : file.entries) {
            if (isPlayerEntry(e) && (e.comments == null || e.comments.isEmpty())) {
                count++;
            }
        }
        return count;
    }

    private static DiaryFile readQuietly(Path p) {
        try {
            return normalize(GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), DiaryFile.class));
        } catch (Exception e) {
            return null;
        }
    }

    /** 外部文件 JSON 结构（DTO）。 */
    public static class DiaryFile {
        public String diaryId;
        /** 旧档兼容字段：女仆日记的绑定女仆 UUID；新档也继续写，便于旧版本读取。 */
        public String ownerMaidId;
        /** 绑定类型：maid / player；空 = 未绑定。 */
        public String ownerType;
        /** 绑定对象 UUID（女仆或玩家）。 */
        public String ownerId;
        public long createdAt;
        public long updatedAt;
        /** true = 已有正向销毁证据（物品被摧毁/消失），内容保留、可被找回；false/缺省 = 正常。 */
        public boolean orphaned;
        /** 备注（AI 命名，供玩家与 AI 区分；恢复迁移时携带）。 */
        public String note;
        /** 上锁（仅女仆日记有意义）：普通右键被拦截，潜行右键可强开；恢复迁移时携带。 */
        public boolean locked;
        /** 主人最近一次成功阅读的时刻（epoch ms；0 = 从未读过）。 */
        public long ownerReadAt;
        /** 主人最近一次阅读时读到的条目数（读到第几条）。 */
        public int ownerReadThrough;
        /** 最近一次阅读是否为强开（读上锁的日记）。 */
        public boolean ownerReadForced;
        public List<Entry> entries = new ArrayList<>();

        public static class Entry {
            public long writtenAt;
            public String author;
            public String text;
            /** 写入者类型：maid（缺省，女仆写）/ player（玩家写）。 */
            public String writerKind;
            /** 女仆在这条条目下写的评语。 */
            public List<Comment> comments = new ArrayList<>();
        }

        public static class Comment {
            public long writtenAt;
            public String author;
            public String text;
        }
    }
}
