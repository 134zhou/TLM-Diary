package com.tlm.diary;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

/**
 * 日记本物品。
 * <p>
 * 不可堆叠、不可复制（防止同一 uuid 出现在多本日记上）；玩家右键为只读阅读，
 * 写入仅由女仆 AI 经 {@link WriteDiaryEntryTool}（服务端）触发。
 */
public class DiaryBookItem extends Item {

    public DiaryBookItem() {
        super(new Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        boolean writeMode = isQuillInOtherHand(player, hand);

        if (level.isClientSide()) {
            // 客户端：日记 + 羽毛笔 → 打开写屏；否则等待服务端下发的只读书页。
            if (writeMode) {
                DiaryWriteScreen.tryOpen(player, stack);
            }
            return InteractionResultHolder.sidedSuccess(stack, true);
        }

        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.sidedSuccess(stack, false);
        }

        DiaryMeta meta = DiaryApi.metaOf(stack);

        // 未绑定 + 潜行 → 绑定给当前玩家（玩家日记）
        if (!meta.isBound() && player.isShiftKeyDown()) {
            Component result = DiaryApi.bindToPlayer(serverPlayer, stack);
            serverPlayer.sendSystemMessage(result);
            return InteractionResultHolder.sidedSuccess(stack, false);
        }

        // 写模式：真正写入在 ServerboundDiaryWritePayload 的服务端 handler 完成
        if (writeMode) {
            return InteractionResultHolder.sidedSuccess(stack, false);
        }

        // 只读阅读：女仆日记保留锁/强开/主人读过语义；玩家日记直接读。
        if (meta.isMaidBound()) {
            DiaryApi.refreshOwnerName(stack, level);
            if (meta.locked() && !player.isShiftKeyDown()) {
                String maidName = meta.ownerName() == null || meta.ownerName().isBlank()
                        ? "?"
                        : meta.ownerName();
                serverPlayer.sendSystemMessage(
                        Component.translatable("tlm_diary.lock.blocked", maidName));
                return InteractionResultHolder.sidedSuccess(stack, false);
            }
            DiaryApi.recordOwnerRead(stack);
        }

        List<DiaryEntry> entries = DiaryApi.readEntries(stack);
        PacketDistributor.sendToPlayer(serverPlayer, new DiaryViewPayload(meta.diaryUuid(), entries));
        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    private static boolean isQuillInOtherHand(Player player, InteractionHand hand) {
        ItemStack other = player.getItemInHand(hand == InteractionHand.MAIN_HAND
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        return other.is(DiaryMod.QUILL.get());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        // 客户端：绑定后显示女仆当前名称。优先按 ownerUuid 实时查询实体（命名牌改名立即生效，零轮询）；
        // 女仆不在客户端视野时回退显示缓存的 ownerName。
        DiaryMeta meta = stack.get(DiaryMod.DIARY_META.get());
        if (meta == null || !meta.isBound()) {
            tooltipComponents.add(Component.translatable("tlm_diary.tooltip.unbound").withStyle(ChatFormatting.GRAY));
            tooltipComponents.add(Component.translatable("tlm_diary.tooltip.bind_hint").withStyle(ChatFormatting.DARK_GRAY));
        } else {
            String name = resolveOwnerName(meta.ownerUuid().get(), meta.ownerName());
            tooltipComponents.add(Component.translatable("tlm_diary.tooltip.bound_to", name).withStyle(ChatFormatting.GRAY));
            if (meta.hasNote()) {
                tooltipComponents.add(Component.translatable("tlm_diary.tooltip.note", meta.note())
                        .withStyle(ChatFormatting.GRAY));
            }
            if (meta.isPlayerBound()) {
                tooltipComponents.add(Component.translatable("tlm_diary.tooltip.write_hint")
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
            if (meta.locked()) {
                tooltipComponents.add(Component.translatable("tlm_diary.tooltip.locked")
                        .withStyle(ChatFormatting.GRAY));
            }
        }
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }

    /** 实时查询女仆当前名称；查询失败回退缓存；缓存也为空则用占位文案。 */
    private static String resolveOwnerName(UUID ownerUuid, String cached) {
        Level level = Minecraft.getInstance().level;
        if (level != null) {
            List<EntityMaid> maids = level.getEntitiesOfClass(EntityMaid.class, AABB.INFINITE,
                    m -> m.getUUID().equals(ownerUuid));
            if (!maids.isEmpty()) {
                return maids.get(0).getName().getString();
            }
        }
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        return Component.translatable("tlm_diary.tooltip.unknown").getString();
    }

    /**
     * 掉落物被摧毁（岩浆/火焰/击杀/仙人掌等）→ 标记文件孤儿并尽力通知。
     * 1 参（原版 {@code Item#onDestroyed}）与 2 参（NeoForge {@code IItemExtension} 默认方法，
     * 经 {@code IItemStackExtension} → {@code Item.onDestroyed} 委托链，已用 javap 核实）均覆写以覆盖全部路径。
     */
    @Override
    public void onDestroyed(ItemEntity itemEntity) {
        onDiaryDestroyed(itemEntity);
    }

    @Override
    public void onDestroyed(ItemEntity itemEntity, DamageSource damageSource) {
        onDiaryDestroyed(itemEntity);
    }

    private void onDiaryDestroyed(ItemEntity itemEntity) {
        if (itemEntity.level().isClientSide()) {
            return;
        }
        DiaryApi.markDestroyed(itemEntity.getItem());
        DiaryDestructionHandler.notifyDestroyed(itemEntity);
    }
}
