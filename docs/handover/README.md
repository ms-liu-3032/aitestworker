# 智能测试平台交接文档索引

这套文档的目标只有两个：

1. 任何人切换到新的 token、新的模型、新的同事后，都能直接继续开发。
2. 所有关键变更、架构决策、交付边界、安装方式都能追踪、回溯、复盘。

建议阅读顺序：

1. [01_项目与设计总览.md](<repo-root>/docs/handover/01_项目与设计总览.md)
2. [02_架构方案.md](<repo-root>/docs/handover/02_架构方案.md)
3. [03_开发计划与进度.md](<repo-root>/docs/handover/03_开发计划与进度.md)
4. [04_交付_自测_开发规范.md](<repo-root>/docs/handover/04_交付_自测_开发规范.md)
5. [05_上下文日志.md](<repo-root>/docs/handover/05_上下文日志.md)
6. [06_Playwright轨迹增强实施清单.md](<repo-root>/docs/handover/06_Playwright轨迹增强实施清单.md)
7. [07_LLM数据污染治理方案.md](<repo-root>/docs/handover/07_LLM数据污染治理方案.md)
8. [08_LLM治理实施日志.md](<repo-root>/docs/handover/08_LLM治理实施日志.md)
9. [09_平台技术分享稿.md](<repo-root>/docs/handover/09_平台技术分享稿.md)
10. [10_轨迹摘要文本化P0设计与实现方案.md](<repo-root>/docs/handover/10_轨迹摘要文本化P0设计与实现方案.md)
11. [11_轨迹定位与语义修正建议设计方案.md](<repo-root>/docs/handover/11_轨迹定位与语义修正建议设计方案.md)
12. [12_轨迹清洗目标规范补充稿.md](<repo-root>/docs/handover/12_轨迹清洗目标规范补充稿.md)
13. [CHANGELOG.md](<repo-root>/docs/handover/CHANGELOG.md)

打包与安装相关：

- 打包总说明：[../packaging/README.md](<repo-root>/packaging/README.md)
- 服务端打包脚本：[../packaging/build-server-package.sh](<repo-root>/packaging/build-server-package.sh)
- 客户端打包脚本：[../packaging/build-client-package.sh](<repo-root>/packaging/build-client-package.sh)
- Windows 客户端打包脚本：[../packaging/build-client-package-windows.sh](<repo-root>/packaging/build-client-package-windows.sh)

## 新人接手最短路径

1. 先看本索引。
2. 再看 [05_上下文日志.md](<repo-root>/docs/handover/05_上下文日志.md) 了解当前做到哪里。
3. 看 [CHANGELOG.md](<repo-root>/docs/handover/CHANGELOG.md) 了解最近改了什么。
4. 如果要继续做轨迹增强，先看 [06_Playwright轨迹增强实施清单.md](<repo-root>/docs/handover/06_Playwright轨迹增强实施清单.md)。
5. 如果要继续做“轨迹摘要文本化 / Mini-TOM 前置层”，先看 [10_轨迹摘要文本化P0设计与实现方案.md](<repo-root>/docs/handover/10_轨迹摘要文本化P0设计与实现方案.md)。
6. 如果要继续做“轨迹定位与语义修正建议”，先看 [11_轨迹定位与语义修正建议设计方案.md](<repo-root>/docs/handover/11_轨迹定位与语义修正建议设计方案.md)。
7. 如果要继续调轨迹清洗规则，先看 [12_轨迹清洗目标规范补充稿.md](<repo-root>/docs/handover/12_轨迹清洗目标规范补充稿.md)。
8. 如果要发布或交付，直接看 [../packaging/README.md](<repo-root>/packaging/README.md)。

## 文档维护约束

从现在开始，每次变更都必须同时更新：

1. [CHANGELOG.md](<repo-root>/docs/handover/CHANGELOG.md)
2. [05_上下文日志.md](<repo-root>/docs/handover/05_上下文日志.md) 中的“最新状态”

要求：

- 只写事实，不写空话。
- 一次变更一条记录。
- 记录内容必须能回答：改了什么、为什么改、影响哪里、怎么验证。
