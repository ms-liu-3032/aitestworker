# AITestHub for DeepSeek Harness

本机 DSH 插件可独立设计用例并用 Playwright 执行 UI 自动化；需要跨设备资产时，也可连接远程 AITestHub 原有后端。DSH 负责推理、测试设计和执行；Hub 提供项目、TOM、Wiki、正式用例、草稿、自动化资产、权限和证据服务。原 Web 页面与功能继续保留；插件不启动第二套后端。

## 构建与分享

在仓库根目录运行：

```bash
./packaging/build-dsh-plugin-package.sh
```

脚本构建共享浏览器采集模块和插件，在 `release/dsh-plugin/` 生成一个 `.tgz` 文件与 `INSTALL.md`。把这两个文件交给使用者即可；不要分享本机的 `.env`、数据库、证据和 `node_modules`。插件包中已包含 Skill 和采集代码，使用者无需再打开独立 worker 窗口。

开发者也可在本目录运行 `npm ci --install-links && npm test`。插件要求 DeepSeek Harness Desktop 版本满足 `package.json` 中的 `engines.dsh`，本机浏览器执行需要 Chrome 或 Playwright Chromium。

## 使用

1. 在 DSH 插件页添加 `.tgz`，重启并确认插件已启用。
2. 不连接 Hub 时，直接说“按这份需求先列测试点，再设计独立用例并保存在本机”。本地设计有唯一 ID 和版本，保存在 `~/.dsh/aitesthub-local/designs/`。继续说“给这条用例建立 Playwright 映射并执行”，报告、截图、trace 和录屏只保存在本机，结果标记 `LOCAL_ONLY`。
3. 需要项目 TOM、Wiki、正式用例或证据回写时，在 Hub Web 登录生成一次性绑定码，复制完整绑定命令到 DSH。模型 API Key 与 Hub 连接凭据分别由 DSH 和 Hub 管理。
4. 绑定后直接用自然语言创建项目、分析需求、生成测试点和用例、执行 UI/API 自动化或查看证据。插件会读取 Hub 的项目资产与规则，写入个人草稿前调用校验；正式用例仍由人在 Hub 审核。
5. 每次 UI/API 自动化执行后，工具结果显示执行状态和本地 HTML 报告路径。仅在联机模式下，执行证据才按采集会话上传 Hub。

Skill 只规定跨业务的测试方法与来源使用顺序。具体菜单、接口路径、定位器、测试数据和断言保存在本地设计或当前项目的自动化资产中，不写进 Skill。本地模式仅依据本轮需求与实测页面，不能声称读取 Hub 规则或通过 Hub 审核。定位失败时只修复定位，不擅自更改断言。

## 安全与数据

插件不内置服务器地址、授权码或账号。连接凭据保存在本机 DSH 凭据服务中；后端按绑定用户检查项目权限。HTTP 连接只适用于可信内网，外部访问应使用 HTTPS。自动化与采集可能产生包含业务数据的截图、网络记录或录屏，分享报告前应自行核对内容。

## 维护

后端接口、Web 管理页、DSH 插件和打包脚本在同一仓库维护。修改插件后重新运行打包脚本并把新 `.tgz` 发给使用者；修改后端或 Web 则使用原服务端打包脚本升级。两种包分别安装。
