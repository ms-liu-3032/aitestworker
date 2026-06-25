# 智能测试平台交接文档索引

这套文档只保留对开源仓库继续维护真正需要的内容：

1. 让后来者快速理解平台结构和当前能力边界。
2. 提供继续开发、打包、发布所需的最小背景。

建议阅读顺序：

1. [01_项目与设计总览.md](./01_项目与设计总览.md)
2. [02_架构方案.md](./02_架构方案.md)
3. [04_交付_自测_开发规范.md](./04_交付_自测_开发规范.md)
4. [08_LLM治理实施日志.md](./08_LLM治理实施日志.md)
5. [11_轨迹定位与语义修正建议设计方案.md](./11_轨迹定位与语义修正建议设计方案.md)
6. [13_Loop+LLMWiki+Mini-TOM整合与Playwright升级方案.md](./13_Loop+LLMWiki+Mini-TOM整合与Playwright升级方案.md)

打包与安装相关：

- 打包总说明：[../../packaging/README.md](../../packaging/README.md)
- 服务端打包脚本：[../../packaging/build-server-package.sh](../../packaging/build-server-package.sh)
- 客户端打包脚本：[../../packaging/build-client-package.sh](../../packaging/build-client-package.sh)
- Windows 客户端打包脚本：[../../packaging/build-client-package-windows.sh](../../packaging/build-client-package-windows.sh)

## 新人接手最短路径

1. 先看本索引。
2. 再看 [01_项目与设计总览.md](./01_项目与设计总览.md) 了解平台边界。
3. 看 [02_架构方案.md](./02_架构方案.md) 了解后端、前端和数据链路。
4. 如果要继续做轨迹增强或用例生成策略，优先看 [11_轨迹定位与语义修正建议设计方案.md](./11_轨迹定位与语义修正建议设计方案.md) 和 [13_Loop+LLMWiki+Mini-TOM整合与Playwright升级方案.md](./13_Loop+LLMWiki+Mini-TOM整合与Playwright升级方案.md)。
5. 如果要发布或交付，直接看 [../../packaging/README.md](../../packaging/README.md)。

## 文档维护约束

开源仓中的 handover 文档只保留平台级事实，不再记录内部项目上下文、真实业务调试样例或个人复盘内容。
