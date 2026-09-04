package com.tlm.diary;

import com.mojang.logging.LogUtils;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

/**
 * TLM: Diary 主类。
 * <p>
 * mod_id 为 {@code tlm_diary}，与 {@code META-INF/neoforge.mods.toml} 中 {@code ${mod_id}} 展开结果、
 * 以及本类上的 {@link Mod} 注解保持一致（模板注释要求）。
 */
@Mod(DiaryMod.MODID)
public class DiaryMod {
    public static final String MODID = "tlm_diary";

    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, MODID);

    /** 日记本物品 {@code tlm_diary:diary_book}，不可堆叠。 */
    public static final DeferredItem<DiaryBookItem> DIARY_BOOK =
            ITEMS.register("diary_book", DiaryBookItem::new);

    /**
     * 日记本元数据组件（仅存索引与绑定信息：uuid / owner / ownerName 缓存 / 上限 / 已写条数）。
     * 内容不进入组件，主存储为游戏目录外部文件（见 {@link DiaryStorage}）。
     * <p>
     * `networkSynchronized` 让客户端拿到 ownerUuid / ownerName（tooltip 展示绑定女仆名称用）；
     * 正文始终不进客户端栈。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<DiaryMeta>> DIARY_META =
            DATA_COMPONENTS.register("diary_meta",
                    () -> DataComponentType.<DiaryMeta>builder().persistent(DiaryMeta.CODEC)
                            .networkSynchronized(DiaryMeta.STREAM_CODEC).build());

    public DiaryMod(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        DATA_COMPONENTS.register(modEventBus);
        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(this::registerPayloads);
        // 游戏总线：掉落物到期消失等销毁场景（恢复外部文件用）
        NeoForge.EVENT_BUS.register(DiaryDestructionHandler.class);
    }

    /** 将日记本加入原版"工具与实用品"标签页（与书与笔同页）。 */
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(DIARY_BOOK.get());
        }
    }

    /** 注册自定义网络 payload：只读阅读（服务端下发日记条目）、恢复选择（≥2 孤儿时）。 */
    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(MODID).playToClient(
                DiaryViewPayload.TYPE,
                DiaryViewPayload.STREAM_CODEC,
                DiaryViewPayload::handle);
        event.registrar(MODID).playToClient(
                ClientboundDiaryRecoveryPayload.TYPE,
                ClientboundDiaryRecoveryPayload.STREAM_CODEC,
                ClientboundDiaryRecoveryPayload::handle);
        event.registrar(MODID).playToServer(
                ServerboundDiaryRecoverChoicePayload.TYPE,
                ServerboundDiaryRecoverChoicePayload.STREAM_CODEC,
                ServerboundDiaryRecoverChoicePayload::handle);
    }
}
