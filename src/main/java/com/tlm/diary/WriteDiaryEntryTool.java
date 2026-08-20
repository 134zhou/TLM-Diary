package com.tlm.diary;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.Component;

import java.util.Optional;

/**
 * 日记写入 Tool：向女仆 AI 暴露"写一条日记"能力，由 AI（LLM）自主决定何时调用。
 */
public class WriteDiaryEntryTool implements ITool<WriteDiaryEntryTool.Result> {

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("content").forGetter(Result::content),
            Codec.STRING.optionalFieldOf("emotion").forGetter(Result::emotion),
            Codec.STRING.optionalFieldOf("diary_uuid").forGetter(Result::diaryUuid)
    ).apply(i, Result::new));

    @Override
    public String id() {
        return "write_diary_entry";
    }

    @Override
    public String summary(EntityMaid maid) {
        return "Write an entry into the maid's diary book when something worth remembering happens "
                + "(a notable event, a mood, a task from the owner, a memorable moment). "
                + "The AI decides when and what to write.";
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        StringParameter content = StringParameter.create()
                .setDescription("The diary entry text, first person, concise")
                .setMaxLength(DiaryApi.MAX_TEXT_LENGTH);
        StringParameter emotion = StringParameter.create()
                .setDescription("Optional mood of the entry")
                .addEnumValues("happy", "sad", "calm", "excited", "nostalgic", "tired");
        StringParameter diaryUuid = StringParameter.create()
                .setDescription("Optional diary book UUID to target when the maid owns multiple diaries; "
                        + "omit to write to the one in the bauble slot");
        root.addProperties("content", content);
        root.addProperties("emotion", emotion, false);
        root.addProperties("diary_uuid", diaryUuid, false);
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolCallId, Result result, LLMCallback callback) {
        EntityMaid maid = callback.getMaid();
        String msg = DiaryApi.writeForAi(maid, result.content(), result.emotion().orElse(""), result.diaryUuid().orElse(null));
        return callback.addToolResult(msg, toolCallId);
    }

    @Override
    public Component invocationSummaryComponent(Result result) {
        return Component.translatable("tool.tlm_diary.write_diary_entry.summary");
    }

    public record Result(String content, Optional<String> emotion, Optional<String> diaryUuid) {
    }
}
