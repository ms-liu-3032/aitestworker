# 打包与安装

在仓库根目录运行以下脚本，产物统一输出到忽略版本控制的 `release/` 目录。

| 脚本 | 产物 | 用途 |
| --- | --- | --- |
| `./packaging/build-server-package.sh` | `release/server/*.tar.gz` | 原有后端、Web 和依赖的一体化服务端包 |
| `./packaging/build-dsh-plugin-package.sh` | `release/dsh-plugin/*.tgz`、`INSTALL.md` | 本机 DeepSeek Harness 插件，内含 Skill 和浏览器采集模块 |
| `./packaging/build-client-package.sh` | `release/client/` | 旧版 macOS 独立浏览器 worker 客户端 |
| `./packaging/build-client-package-windows.sh` | `release/client/` | 旧版 Windows 独立浏览器 worker 客户端 |

DSH 插件与服务端分别分发。使用者先安装 DSH Desktop，在插件页选择 `.tgz`，再从 AITestHub Web 获取一次性绑定码。插件包不包含服务地址、账号、绑定码、数据库数据或本机证据。原 Web 功能和独立 worker 打包脚本继续保留。

服务端构建需要 Java 17、Maven、Node.js、npm、Docker 和 tar。插件构建需要 Node.js、npm 和 tar。打包脚本会检查依赖和包内必需文件。
