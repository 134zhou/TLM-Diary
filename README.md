# TLM: Diary（女仆日记本）

> 车万女仆（Touhou Little Maid）的 NeoForge 附属模组 —— 让女仆拥有一本自己的日记。

[![License: MIT](https://img.shields.io/github/license/134zhou/TLM-Diary)](LICENSE)
[![GitHub](https://img.shields.io/badge/GitHub-134zhou%2FTLM--Diary-blue)](https://github.com/134zhou/TLM-Diary)

🔗 项目主页：https://github.com/134zhou/TLM-Diary

## 简介

**TLM: Diary** 为 [车万女仆](https://github.com/TartaricAcid/TouhouLittleMaid)（Touhou Little Maid, TLM）添加了"日记本"：女仆可以把它戴在饰品栏里，由女仆的 AI（LLM）自主决定何时写下值得纪念的内容，玩家则可以随时翻看。

## 功能特性

- 📖 **日记本物品**：通过**祭坛**合成（书 + 墨囊 + 羽毛，与原版"书与笔"相同材料），不可堆叠；
- 🤝 **女仆绑定**：首次放入女仆饰品栏即**永久绑定**该女仆；绑定女仆可读可写，其他女仆只能阅读；悬停物品可查看绑定女仆的**当前名称**（命名牌改名实时生效）；
- ✍️ **AI 自主书写**：通过 `write_diary_entry` 工具与 `diary-writing` 技能，由游戏内配置的 AI（LLM）自主决定是否写、写什么（**无定时写入**）；
- 📄 **玩家只读**：右键打开只读书页（复用原版书籍阅读界面），无任何编辑入口；
- 💾 **跨存档存储**：日记内容保存在 `<游戏目录>/tlm_diary/diaries/*.json`，与存档无关，可跨存档共享、备份迁移；
- 🔒 **写入上限与恢复**：每本日记有写入上限，写满后 AI 会提醒玩家制作新本；日记本被销毁时数据不丢失，可制作新本恢复；
- 🔔 **改名提醒**：女仆被命名牌改名后，tooltip 显示最新名称，并提示 AI 历史条目以旧名署名。

## 依赖

| 依赖 | 版本 |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.x |
| Touhou Little Maid（车万女仆） | 1.5.2+（**必需**） |

## 安装

1. 安装 NeoForge 21.1.x 与车万女仆（Touhou Little Maid）1.5.2+；
2. 将构建出的 `tlm_diary-<version>.jar` 放入 `mods/` 目录；
3. 启动游戏即可。

## 使用

1. 用**祭坛**合成日记本（书 + 墨囊 + 羽毛）；
2. 把日记本放入女仆的**饰品栏**（首次佩戴即绑定，绑定永久）；
3. 玩家**右键**日记本即可只读翻阅；
4. 在女仆的 AI 聊天中，AI 会自主决定何时调用 `write_diary_entry` 写日记；日记写满或没有日记本时，AI 会向你要一本新的日记本。

> 日记内容存储于 `<游戏目录>/tlm_diary/diaries/`，由模组独占管理（普通用户只读），请勿手动编辑；备份/迁移请直接复制该目录。

## 构建

需要 JDK 21：

```
gradlew build
```

构建产物位于 `build/libs/`。

## 许可

本项目基于 [MIT License](LICENSE) 发布。

> 注意：本项目使用 Mojang 官方映射名，受 [Mojang 映射许可](https://github.com/NeoForged/NeoForm/blob/main/Mojang.md) 约束。
