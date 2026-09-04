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
            return GSON.fromJson(json, DiaryFile.class);
        } catch (Exception e) {
            DiaryMod.LOGGER.error("Failed to read diary file {}, treating it as corrupted", f, e);
            backupCorrupted(f);
            return null;
        }
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
        Path dir = diariesDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> {
                        DiaryFile df = readQuietly(p);
                        return df != null && ownerUuid.toString().equals(df.ownerMaidId);
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

    /** 列出某女仆名下已标记孤儿（正向销毁证据）的日记文件，按 updatedAt 倒序。 */
    public static List<Path> listOwnedOrphaned(UUID ownerUuid) {
        Path dir = diariesDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> {
                        DiaryFile df = readQuietly(p);
                        return df != null && df.orphaned && ownerUuid.toString().equals(df.ownerMaidId);
                    })
                    .sorted(Comparator.comparingLong((Path p) -> {
                        DiaryFile df = readQuietly(p);
                        return df == null ? 0L : df.updatedAt;
                    }).reversed())
                    .toList();
        } catch (IOException e) {
            DiaryMod.LOGGER.error("Failed to list orphaned diary files", e);
            return List.of();
        }
    }

    private static DiaryFile readQuietly(Path p) {
        try {
            return GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), DiaryFile.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 外部文件 JSON 结构（DTO）。 */
    public static class DiaryFile {
        public String diaryId;
        public String ownerMaidId;
        public long createdAt;
        public long updatedAt;
        /** true = 已有正向销毁证据（物品被摧毁/消失），内容保留、可被找回；false/缺省 = 正常。 */
        public boolean orphaned;
        /** 备注（AI 命名，供玩家与 AI 区分；恢复迁移时携带）。 */
        public String note;
        /** 上锁（AI 可控）：普通右键被拦截，潜行右键可强开；恢复迁移时携带。 */
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
        }
    }
}
