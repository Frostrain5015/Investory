# 产品版本维护

产品版本的唯一来源是 `backend/pom.xml` 的 Investory 项目版本（不是 Spring Boot parent 或第三方依赖版本）。升级时只修改这一处。

前端 Vite 构建直接读取该版本，作为 `__APP_VERSION__`；Java/Maven 原生使用项目版本；部署脚本直接解析同一 POM，不再读取提交标题或桌面包版本。

前端、桌面端的 `package.json`、`package-lock.json` 是派生值。npm 的开发、构建、桌面启动和安装入口，以及部署脚本，自动运行 `tools/version.mjs` 同步四个文件。需要在构建前立即更新仓库清单时运行：

```sh
node tools/version.mjs
```

提交前使用只读检查（前端 `npm run check` 也已包含）：

```sh
node tools/version.mjs --check
```

修改版本后重新构建，再提交派生文件并推送。已经生成的 JAR、网页和桌面安装包仍须重新构建、发布，运行中的程序不会被源文件修改直接更新。`deploy.py --no-build` 会核对 JAR 内的 Maven 版本，不接受其他版本的旧构建物。

第三方依赖版本独立维护，历史发布记录保留原有版本；README 通过链接指向版本来源，不再复制当前版本号。

回归测试覆盖：只改 POM 后同步所有派生版本、依赖版本不变、重复执行不修改文件、只读检查发现漂移、非法版本拒绝、旧 JAR 版本拒绝。
