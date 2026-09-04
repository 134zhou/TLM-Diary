package com.tlm.diary;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.GameContextRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ToolRegister;
import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.item.bauble.BaubleManager;

/**
 * TLM 扩展入口：女仆模组通过 {@link LittleMaidExtension} 注解 + 无参构造反射实例化本类。
 * <p>
 * 仅在女仆模组被安装时才会被加载（软依赖），未安装时本类不会被触发。
 */
@LittleMaidExtension
public class LittleMaidCompat implements ILittleMaid {

    public LittleMaidCompat() {
    }

    @Override
    public void bindMaidBauble(BaubleManager manager) {
        // 将日记本绑定为女仆饰品，可放入饰品栏。
        manager.bind(DiaryMod.DIARY_BOOK.get(), new DiaryBauble());
    }

    @Override
    public void registerAITool(ToolRegister register) {
        // 仅向 AI 暴露工具，由 AI 自主决定：写入日记、命名/修改备注、上锁/解锁。
        register.register(new WriteDiaryEntryTool());
        register.register(new SetDiaryNoteTool());
        register.register(new SetDiaryLockTool());
    }

    @Override
    public void registerAIMaidContext(GameContextRegister register) {
        // 先注册分类，再注册上下文项（顺序不可颠倒，否则抛异常）。
        register.registerCategory("diary",
                "Diary books the maid owns: notes, identifiers, entry counts and remaining capacity", false);
        register.registerContext("diary", new DiaryContext());
    }
}
