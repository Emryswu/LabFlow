# AGENTS.md

给接手本仓库的 AI 编码助手（Claude Code、Codex 等）看的约定。命令以 `.github/workflows/ci.yml` 和 `frontend/package.json` 为准；两边不一致时，以它们为准并顺手修正本文件。

## 仓库结构

| 目录 | 内容 |
| --- | --- |
| 根目录 | 后端：Spring Boot 3.5、Java 21、Maven（自带 `mvnw`），包名 `com.arthur.labops` |
| `frontend/` | 前端：Next.js 16，由 vinext 构建运行；React 19、TypeScript；Node `>=22.13.0` |
| `src/main/resources/db/migration/` | Flyway 迁移脚本 |
| `benchmark/` | 并发压测脚本（Python）与结果，`RESULTS.md` 是对外引用的数据来源 |
| `scripts/`、根目录 `*.ps1` | Windows 本地起栈、生产 profile 起停与验证脚本 |
| `docs/` | `concurrency-notes.md` 并发设计说明，`screenshots/` 是 README 用图 |
| `.github/screenshots/` | README 截图的页面清单（`shots.mjs`）与截图脚本，由 `readme-screenshots.yml` 运行 |

## 命令

CI（`ci.yml`）对每个指向 main 的 PR 跑以下全部检查。

**后端**（在仓库根目录下）

- 构建与测试：`./mvnw -B --no-transfer-progress verify`（Windows 用 `mvnw.cmd`）
- 没有单独的 lint 步骤。
- 默认 profile 走 H2、进程内锁和本地定时器，不需要 MySQL、Redis、RabbitMQ。
- MySQL 并发回归（`RefreshTokenMysqlConcurrencyTest`、`PaymentRequestMysqlConcurrencyTest`、`MysqlCriticalConcurrencyRegressionTest`）用 Testcontainers，本机没有 Docker 时会被跳过；CI 会检查它们真的跑过、没有 skipped。
- MySQL 趋势查询计划回归（`DashboardTrendIndexMysqlTest`）同样用 Testcontainers；CI 必须确认已执行，防止索引缺失被跳过的测试掩盖。
- `RabbitReservationExpiryOrderingIntegrationTest` 要连 `127.0.0.1:5672`，连不上整类跳过；CI 用 RabbitMQ service 让它真正执行。
- CI 另有「后端 · 无 Docker」job，关掉 Docker 再跑一遍 `verify`：新加的 Testcontainers 用例在没有 Docker 时必须跳过，不能报错。

**前端**（在 `frontend/` 下）

- 安装：`npm ci`
- lint：`npm run lint`
- 类型检查：`npm run typecheck`（`tsc --noEmit`；lint 和测试的 TS 转译都不做类型检查，类型错误只有这一步拦得住）
- 测试：`npm test`，先 `vinext build` 生产构建，再跑 `tests/**/*.test.mjs`。CI 跑在 UTC，断言渲染出的日期或时分时，测试数据用 `new Date(年, 月, 日, 时, 分)` 按本地时间构造，不要写死带时区的字符串；本地可用 `TZ=UTC npm test` 复查。
- 本地开发：`npm run dev`，后端地址见 `frontend/.env.example`（默认 `http://127.0.0.1:18080`）。

**README 截图**

- 截图只来自真实运行的服务，不用假后端或手工拼图。改了界面后先推送自己的分支，再对该分支手动运行「README 截图」workflow（`gh workflow run readme-screenshots.yml --ref <分支>`），它会起栈、按角色截图并提交回该分支，不能在 main 上运行。截图提交由 workflow 令牌推送，不会触发 CI，之后再推一次自己的提交即可让 CI 覆盖最新状态。
- 要拍的页面和等待的选择器写在 `.github/screenshots/shots.mjs`；`frontend/tests/readme-screenshots.test.mjs` 检查类名和 `data-*` 属性仍存在、清单与 README 引用的图一致；`rendered-html.test.mjs` 用完整登录选择器查询真实渲染的表单，覆盖 `autocomplete` 的属性值。改界面时如果它们失败，同步改 `shots.mjs`。

**提交信息检查**（在仓库根目录下）

- 测试：`node --test .github/scripts/check-commit-messages.test.mjs`
- 自查本分支的提交：`node .github/scripts/check-commit-messages.mjs origin/main HEAD`

## 提交与 PR

- commit 和 PR 只署作者本人。不加 `Co-Authored-By`、会话链接（`claude.ai/code/session…`）、`Generated with ...` 之类的署名行。CI 的「提交信息署名检查」会拦下带这些字样的提交。
- PR 描述用中文。
- 改完必须先在本地跑通所改部分的 lint 和测试（见上方命令），全部通过再提交。

## 修 bug 与 review

- 修 bug 必须补回归测试：修复前测试失败，修复后通过。
- review 发现问题时，不只修这一处，还要防止同类问题再出现。优先改代码结构，让错误写不出来；其次补测试或 CI 检查，让错误被自动发现。两者都拦不住时，才写进文档。

## 后端约定

- 数据库结构变更只加新的 Flyway 迁移，版本号接着最大的往下排，不改已经合入的迁移。
- 状态变更涉及设备和预约两张表时，加锁顺序固定为 Equipment → Reservation，批量锁按主键排序，避免 ABBA 死锁。细节见 `docs/concurrency-notes.md`。
- 并发相关的修复，H2 上测不出来的要补 MySQL Testcontainers 回归，并在 `ci.yml` 里加「确认已执行」的检查，防止被静默跳过。

## 前端约定

- 颜色、圆角、阴影用 `app/globals.css` 里 `:root` 的变量，不另起一套。
- 图标用 `app/components/icons.tsx` 的 `Icon`，缺的图标加进那个文件，不用 Unicode 字符充当图标。设备没有照片字段，按类别用 `categoryIcon` 显示图标。
- 角色导航以 `app/lib/roles.ts` 的 `navItemsForRole` 为准；`tests/rendered-html.test.mjs` 里有一份对照，改导航时两处一起改。
- 筛选项（下拉选项、标签列表）不要从当前页、已筛选的列表数据里推导：换页或加筛选后选项会消失。由后端单独接口提供，例如设备类别用 `GET /api/equipment/categories`。测试要覆盖「筛选后」和「数据在第一页之外」两种情况。
- 设备名录这类只用来装饰行的数据，不要和页面自己的数据放进同一个 `Promise.all`：名录失败会连带已经成功的行一起丢掉。用 `page.tsx` 的 `settle` 单独等待，失败时行回退到编号并单独提示；测试要覆盖「主数据成功、名录失败」。
- 给 `.solid-button`、`.ghost-button` 这类通用类加页面专属尺寸时，选择器要带上父级（如 `.login-card .login-submit`）：单个类名写在通用样式前面会按源码顺序被覆盖。jsdom 的 `getComputedStyle` 不算优先级，测不出这种覆盖，要断言样式时用 `tests/css-cascade.mjs` 的 `cascadedValue`。
- 菜单、下拉层等浮层不要渲染在 `overflow` 滚动容器里（表格外层的 `.table-scroll` 会裁掉纵向溢出），用 portal 挂到 `body` 再按触发按钮定位，参照 `EquipmentView` 的 `MoreMenu`。测试至少覆盖一行、两行和末行三种情况。

## 编辑方式

- 大文件用局部编辑，不要整文件重写。

## 压测数据

- `benchmark/RESULTS.md` 里的数字只来自 `benchmark/` 下脚本的实际运行结果，不手改、不估算。
