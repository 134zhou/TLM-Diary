package com.tlm.diary;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.AbstractMaidContext;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 日记上下文项：向 AI 暴露女仆当前持有的日记本列表（标识 / 已写条数 / 容量），
 * 并在女仆被主人改过名时附加改名提醒（比较最近一条条目的 author 与当前名称）。
 * <p>
 * 注册于按需查询分类 {@code diary}，AI 经 query_game_context 工具按需拉取，节省 token。
 */
public class DiaryContext extends AbstractMaidContext {

    public DiaryContext() {
        super("tlm_diary_status", "Diary status");
    }

    @Override
    public String getValue(EntityMaid maid) {
        List<ItemStack> diaries = DiaryApi.findDiaries(maid);
        if (diaries.isEmpty()) {
            return "no diary book";
        }
        StringBuilder sb = new StringBuilder();
        String renameNotice = null;
        String currentName = maid.getName().getString();
        for (ItemStack d : diaries) {
            DiaryMeta m = DiaryApi.metaOf(d);
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            String shortUuid = m.diaryUuid().toString().substring(0, 8);
            sb.append(shortUuid).append('(').append(m.writtenCount()).append('/').append(m.maxEntries()).append(')');
            if (renameNotice == null) {
                renameNotice = checkRename(d, currentName);
            }
        }
        if (renameNotice != null) {
            sb.append(". ").append(renameNotice);
        }
        return sb.toString();
    }

    /**
     * 若最近一条条目的 author（写入时的名称）与当前名称不同，说明女仆被命名牌改过名，
     * 返回给 LLM 的英文提醒；否则返回 null。
     */
    private static String checkRename(ItemStack diary, String currentName) {
        List<DiaryEntry> entries = DiaryApi.readEntries(diary);
        if (entries.isEmpty()) {
            return null;
        }
        DiaryEntry last = entries.get(entries.size() - 1);
        if (currentName.equals(last.author())) {
            return null;
        }
        return "Rename notice: the owner renamed you from \"" + last.author()
                + "\" to \"" + currentName + "\"; earlier diary entries are signed with the old name.";
    }
}
