# Investory 文档

项目架构、评估和计划统一存放于本目录。

| 文档 | 用途 |
| --- | --- |
| [当前架构](architecture.md) | 模块职责、源码入口、进程关系、数据流、构建方式及现有边界 |
| [2026-10-22 技术方案 v0.2](a-investory-technical-proposal-2026-10-22.md) | 当前评审基线：复用 Investory、同花顺金融API、常驻规则与按需观澜、邮件、数据分层、券商条件路径及待决事项；含运行时路线图 |
| [邮件发送网关](integrations/email-gateway.md) | 独立 SMTP 模块、管理员测试接口、配置和验证边界；默认关闭，未接入观澜 |
| [同花顺金融API只读数据通道](integrations/hithink-data.md) | 标的检索、批量报价、历史日线；管理员独立查询接口，未替换旧库或现有页面 |
| [DeepSeek Search 辅助搜索](integrations/deepseek-search.md) | 官方 Search API、观澜入口、私密配置、摘要与错误处理及价格边界 |
| [2026-10-20 代码梳理与验证记录](code-hygiene-2026-10-20.md) | 本次实际清理、缺陷修复、测试结果和未覆盖事项 |
| [v6.3.3 发布说明](releases/v6.3.3.md) | 版本范围、云端发布、备份回滚和验证方式 |
| [A/HK 抓取零写入调查](operations/crawl-zero-rows-2026-10-20.md) | 云端证据、不同根因、状态口径及修复 |
| [2026-10-19 架构评估与演进计划](investory-architecture-assessment-2026-10-19.md) | 历史评估及量化工具演进方案；其中清理前的问题状态以最新验证记录为准 |

新增计划和架构决策应继续放入 `docs/`，并在此添加入口。当前实现与未来规划分开描述，避免把规划中的券商执行、私有化部署或桌面后端打包写成已实现功能。
