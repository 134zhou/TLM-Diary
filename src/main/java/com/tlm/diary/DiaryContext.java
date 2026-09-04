package com.tlm.diary;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.AbstractMaidContext;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.item.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 日记上下文项：向 AI 暴露女仆当前持有的日记本列表（备注 / 锁状态 / 短 uuid / 已写条数 / 容量），
 * 供 AI 按备注区分并自主选择写入哪一本；给出"无备注命名提醒"、"主人读过/强开阅读"提示与改名提醒。
 * <p>
 * 注册于按需查询分类 {@code diary}，AI 经 query_game_context 工具按需拉取，节省 token。
 */
public class DiaryContext extends AbstractMaidContext {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public DiaryContext() {
        super("tlm_diary_status", "Diary status");
    }

    @Override
    public String getValue(EntityMaid maid) {
        List<ItemStack> diaries = DiaryApi.findDiaries(maid);
        if (diaries.isEmpty()) {
            return "no diary book";
        }
        String currentName = maid.getName().getString();
        List<String> parts = new ArrayList<>();
        List<String> unNamed = new ArrayList<>();
        List<String> readNotices = new ArrayList<>();
        String renameNotice = null;
        for (ItemStack d : diaries) {
            DiaryMeta m = DiaryApi.metaOf(d);
            String shortUuid = m.diaryUuid().toString().substring(0, 8);
            String label = m.hasNote() ? "'" + m.note() + "'" : "<unnamed>";
            StringBuilder entry = new StringBuilder(label).append(' ').append(shortUuid)
                    .append('(').append(m.writtenCount()).append('/').append(m.maxEntries()).append(')');
            if (m.locked()) {
                entry.append(" [locked]");
            }
            parts.add(entry.toString());

            DiaryStorage.DiaryFile file = DiaryStorage.load(m.diaryUuid());
            int total = file == null ? 0 : file.entries.size();
            if (file != null && file.ownerReadAt > 0) {
                String readNotice = buildReadNotice(m, file, total);
                if (readNotice != null) {
                    readNotices.add(readNotice);
                }
            }
            if (!m.hasNote()) {
                unNamed.add(shortUuid);
            }
            if (renameNotice == null) {
                renameNotice = checkRename(m, file, total, currentName);
            }
        }
        StringBuilder sb = new StringBuilder("diary books: ").append(String.join(", ", parts));
        if (!unNamed.isEmpty()) {
            sb.append(". Reminder: diaries ").append(String.join(", ", unNamed))
                    .append(" have no note yet; name them with set_diary_note when appropriate.");
        }
        if (!readNotices.isEmpty()) {
            sb.append(". ").append(String.join("; ", readNotices));
        }
        if (renameNotice != null) {
            sb.append(". ").append(renameNotice);
        }
        return sb.toString();
    }

    /** 主人读过该本日记的提示；无读记录返回 null。 */
    private static String buildReadNotice(DiaryMeta meta, DiaryStorage.DiaryFile file, int total) {
        String label = meta.hasNote() ? "'" + meta.note() + "'" : "<unnamed>";
        String time = TIME.format(Instant.ofEpochMilli(file.ownerReadAt).atZone(ZoneId.systemDefault()));
        int k = Math.min(file.ownerReadThrough, total);
        if (file.ownerReadForced) {
            return "Owner force-read your locked diary " + label + " up to entry #" + k + " (" + time + ")";
        }
        if (k < total) {
            return "Owner read " + label + " up to entry #" + k + " (" + time
                    + "); your entries after #" + k + " are still unseen by the owner";
        }
        return "Owner has read all #" + k + " entries of " + label + " (" + time + ")";
    }

    /**
     * 若最近一条条目的 author（写入时的名称）与当前名称不同，说明女仆被命名牌改过名，
     * 返回给 LLM 的英文提醒；否则返回 null。
     */
    private static String checkRename(DiaryMeta meta, DiaryStorage.DiaryFile file, int total, String currentName) {
        if (file == null || total == 0) {
            return null;
        }
        DiaryStorage.DiaryFile.Entry last = file.entries.get(total - 1);
        if (currentName.equals(last.author)) {
            return null;
        }
        return "Rename notice: the owner renamed you from \"" + last.author
                + "\" to \"" + currentName + "\"; earlier diary entries are signed with the old name.";
    }
}
