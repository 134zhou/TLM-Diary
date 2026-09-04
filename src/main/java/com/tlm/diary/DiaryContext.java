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
 * 日记上下文项：向 AI 暴露女仆当前持有的女仆日记本列表，以及主人名下的玩家日记本列表。
 * <p>
 * 女仆日记：备注 / 锁状态 / 短 uuid / 已写条数 / 容量 / 归属标记；
 * 玩家日记：备注 / 短 uuid / 条目数 / 未评论数（供 AI 决定是否 read_player_diary + comment_diary）。
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
            if (m.isBound() && !m.isOwnerMaid(maid.getUUID())) {
                entry.append(" [not yours]");
            }
            parts.add(entry.toString());

            DiaryStorage.DiaryFile file = DiaryStorage.load(m.diaryUuid());
            int total = file == null ? 0 : file.entries.size();
            if (file != null && file.ownerReadAt > 0 && m.isMaidBound()) {
                String readNotice = buildReadNotice(m, file, total);
                if (readNotice != null) {
                    readNotices.add(readNotice);
                }
            }
            if (!m.hasNote()) {
                unNamed.add(shortUuid);
            }
            if (renameNotice == null && m.isMaidBound()) {
                renameNotice = checkRename(m, file, total, currentName);
            }
        }

        StringBuilder sb = new StringBuilder();
        if (!parts.isEmpty()) {
            sb.append("maid diaries: ").append(String.join(", ", parts));
        } else {
            sb.append("maid diaries: none");
        }

        // 主人名下的玩家日记（女仆可读并可留言）
        List<DiaryApi.PlayerDiarySignal> playerDiaries = DiaryApi.getPlayerDiarySignals(maid);
        if (!playerDiaries.isEmpty()) {
            List<String> pdParts = new ArrayList<>();
            for (DiaryApi.PlayerDiarySignal s : playerDiaries) {
                String label = s.hasNote() ? "'" + s.note() + "'" : "<unnamed>";
                pdParts.add(label + " " + s.shortId() + " (owner's diary, " + s.totalEntries()
                        + " entries, " + s.uncommentedEntries() + " un-commented)");
            }
            sb.append(". Owner's diaries: ").append(String.join(", ", pdParts));
        }

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

    /** 主人读过该本女仆日记的提示；无读记录返回 null。 */
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
