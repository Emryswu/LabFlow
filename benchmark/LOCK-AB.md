# 本地锁与 Redis 锁 A/B 复测

**状态：已提供客户端，尚未运行真实中间件 A/B。** 历史 [RESULTS.md](RESULTS.md) 保留，不用它推断单独 Redis 锁的性能。

## 实验设计

同一提交、同一 JAR、同一 MySQL / Redis / RabbitMQ 版本、同一机器、同一 production profile；唯一处理变量是启动参数 `--labops.reservation-lock.mode=local|redis`。保留相同日志级别、连接池、锁等待 / 租约参数、硬件、请求分布；每次从等价的独立测试库快照恢复。交替运行 local → redis、redis → local，统一预热后至少重复 3 轮。不得连接正式数据库。客户端的 lock-mode 只是用户声明，保存启动日志核实实际模式。

| 场景 | 测量对象 | 验收 |
| --- | --- | --- |
| contention | 不同学生，同设备同时间创建 | PENDING 可以并存；记录全部响应、成功吞吐及锁等待超时 |
| independent | 不同学生，各自设备，同时间创建 | 避免用户锁和设备锁共享；记录全部响应及成功吞吐 |
| approval | 预建重叠 PENDING，管理员并发审批 | 恰好一条 APPROVED，其余 RESERVATION_CONFLICT，读取持久化状态核对 |

审批直接走 ReservationService，主要验证数据库行锁，不是应用层 local/redis 锁成本的测量。创建使用 ReservationApplicationService 才经过两层应用锁。列表接口读取用于核对持久化可见状态，正式报告仍应保存独立数据库查询结果。

## 执行

先创建独立、无在途预约的测试学生账号（每个并发请求使用不同账号，避免用户锁与默认 20 条未关闭预约配额污染结果）。管理员和学生账号只通过环境变量传入，勿提交真实凭据。每轮创建新设备和预约，脚本不会删除数据；用专用数据库快照恢复，勿在正常账号上使用。

在仓库根目录的 PowerShell 中运行，示例为 2 个学生，较高并发时增加独立学生数：

```powershell
$env:LABFLOW_BENCH_ACCOUNTS = '{"admin":{"username":"admin","password":"<测试口令>"},"students":[{"username":"bench1","password":"<测试口令>"},{"username":"bench2","password":"<测试口令>"}]}'
$commit = git rev-parse HEAD
python benchmark/bench_lock_ab.py --disposable-db --lock-mode local --scenario contention --concurrency 2 --commit $commit --round 1 --out benchmark/ab-results/local-contention-r1.json
```

切换 JAR 启动参数、恢复相同初始库并按同样流程预热后，使用 `--lock-mode redis` 和独立输出文件复测。对 independent 和 approval 分别执行同一过程。每轮需保存实际启动参数、环境与数据库快照说明、预热方法、日志；客户端只记录本轮结果，不自动启动服务、不执行数据库恢复，也不代替数据库不变量查询。重复结果不可覆盖。

输出 JSON 含 HTTP 状态、业务错误码、逐请求延迟、P50/P95/P99、全部响应吞吐与成功操作吞吐、持久化状态、commit 及声明的锁模式；`valid=false` 或非零退出表示不能作为成功实验使用。Redis 锁等待超时单独统计，不冒充时段冲突；比较吞吐时同时查看成功率。仅在真实运行后填写新结果，不能把本地客户端测试当作性能数据。
