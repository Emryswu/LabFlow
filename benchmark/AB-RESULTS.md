# 同一 MySQL 上的本地锁与 Redis 锁 A/B

测量提交：`a6684f3aa7eb7905c2a735201509f6e6b34e7cd5`（PR #49 合并进 main 之后）。
运行目录：`benchmark/ab-results/20261128T161209Z/`。
本页每个数字都从该目录里的 `measured/client.json`、`evidence.txt`、`environment.txt` 或 `mysql-reservations.txt` 读出，没有估算。

## 环境

| 项 | 值 |
| --- | --- |
| 机器 | Linux cursor 6.12.113+，Ubuntu 24.04.5，KVM 虚拟机 |
| CPU | Intel Xeon Processor，4 vCPU，每核 1 线程 |
| 内存 | `free -b` 的 Mem total = 16780623872 字节，Swap 0 |
| Java | OpenJDK 21.0.13.0.3 |
| Python | 3.12.3 |
| Docker | 29.1.3 |
| MySQL | 镜像 `mysql:8.4`，服务端 8.4.13，`utf8mb4` / `utf8mb4_unicode_ci` |
| InnoDB | buffer pool 134217728 字节，`innodb_lock_wait_timeout` 50，隔离级别 REPEATABLE-READ，`max_connections` 151 |
| Redis | 镜像 `redis:7.4-alpine`，服务端 7.4.12 |
| RabbitMQ | 镜像 `rabbitmq:3.13-management-alpine`，3.13.7 |
| 应用 | 同一个 JAR，production profile。登录限流保持 Redis，过期保持 RabbitMQ。演示用户开启，演示业务数据关闭 |
| 唯一变量 | 启动参数 `--labops.reservation-lock.mode=local` 或 `redis`。锁等待 2s、租约 10s、Hikari、Tomcat 都用默认值，没有为某一组单独改 |

每一格测量前都用同一份 `baseline.sql` 恢复这个 MySQL 实例，再冷启动 JVM。启动后先做一次 independent、并发 2 的预热，再跑正式请求。预热结果写在同目录的 `warmup/`，不进入下表。

顺序：第 1、3 轮先 local 后 redis，第 2 轮先 redis 后 local。计划原文是 `plan.json`。

本地锁那一组的应用日志里，`Redis reservation lock acquired` 都是 0 行。Redis 那一组 acquired 与 released 行数相等：争用和独立创建是 68（32 条正式创建 + 2 条预热，每条用户锁和日期锁各一次），审批格是 20（预热 4 次加上 8 条串行预建创建的 16 次）。审批决定本身不走这层应用锁，日志里的获取发生在预热和预建。

## 参数

| 场景 | 并发 | 轮次 | 超时 | 正式请求在做什么 |
| --- | ---: | ---: | ---: | --- |
| contention | 32 | 3 | 60s | 不同学生，同一新设备，同一小时 |
| independent | 32 | 3 | 60s | 不同学生，各自新设备，同一小时 |
| approval | 8 | 3 | 60s | 先串行建成 8 条重叠 PENDING，再由管理员并发批准 |

客户端是 `benchmark/bench_lock_ab.py`。墙钟从屏障放行算到最后一条正式请求返回。成功吞吐只数 HTTP 201（创建）或 HTTP 200（审批）。

## 正式结果

`valid=false` 的一轮照样列出，不删、不重跑。

### contention

| 轮次 | 锁 | 有效 | HTTP | 错误码 | 成功 P50 ms | 成功 P95 ms | 成功 P99 ms | 全部 P99 ms | 全部响应 req/s | 成功 req/s | 墙钟 s | 原始 JSON |
| ---: | --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | local | true | 201:32 | HTTP_201:32 | 241.345 | 401.513 | 424.206 | 424.206 | 74.52411710935793 | 74.52411710935793 | 0.4293911989999515 | `benchmark/ab-results/20261128T161209Z/runs/r1-local-contention/measured/client.json` |
| 1 | redis | true | 201:32 | HTTP_201:32 | 305.866 | 622.752 | 736.311 | 736.311 | 41.40161048357921 | 41.40161048357921 | 0.7729167929999221 | `benchmark/ab-results/20261128T161209Z/runs/r1-redis-contention/measured/client.json` |
| 2 | local | true | 201:32 | HTTP_201:32 | 238.243 | 392.688 | 407.917 | 407.917 | 77.23316380563803 | 77.23316380563803 | 0.4143297830000847 | `benchmark/ab-results/20261128T161209Z/runs/r2-local-contention/measured/client.json` |
| 2 | redis | true | 201:32 | HTTP_201:32 | 373.533 | 783.127 | 863.674 | 863.674 | 35.714308235026095 | 35.714308235026095 | 0.8959994350000215 | `benchmark/ab-results/20261128T161209Z/runs/r2-redis-contention/measured/client.json` |
| 3 | local | true | 201:32 | HTTP_201:32 | 243.291 | 394.278 | 415.052 | 415.052 | 75.54461488012656 | 75.54461488012656 | 0.42359074900014093 | `benchmark/ab-results/20261128T161209Z/runs/r3-local-contention/measured/client.json` |
| 3 | redis | true | 201:32 | HTTP_201:32 | 475.736 | 899.106 | 958.004 | 958.004 | 32.93909989769286 | 32.93909989769286 | 0.971489813000062 | `benchmark/ab-results/20261128T161209Z/runs/r3-redis-contention/measured/client.json` |

### independent

| 轮次 | 锁 | 有效 | HTTP | 错误码 | 成功 P50 ms | 成功 P95 ms | 成功 P99 ms | 全部 P99 ms | 全部响应 req/s | 成功 req/s | 墙钟 s | 原始 JSON |
| ---: | --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | local | true | 201:32 | HTTP_201:32 | 189.024 | 255.759 | 260.593 | 260.593 | 121.43900791865042 | 121.43900791865042 | 0.26350676399988515 | `benchmark/ab-results/20261128T161209Z/runs/r1-local-independent/measured/client.json` |
| 1 | redis | false | 201:28, 409:4 | HTTP_201:28, RESOURCE_BUSY:4 | 200.571 | 250.188 | 254.898 | 254.663 | 123.15901519213186 | 107.76413829311538 | 0.2598266960001183 | `benchmark/ab-results/20261128T161209Z/runs/r1-redis-independent/measured/client.json` |
| 2 | local | true | 201:32 | HTTP_201:32 | 178.314 | 246.867 | 255.519 | 255.519 | 123.62230379081787 | 123.62230379081787 | 0.25885296599994945 | `benchmark/ab-results/20261128T161209Z/runs/r2-local-independent/measured/client.json` |
| 2 | redis | true | 201:32 | HTTP_201:32 | 227.972 | 290.587 | 291.945 | 291.945 | 104.89189441459328 | 104.89189441459328 | 0.30507600400005686 | `benchmark/ab-results/20261128T161209Z/runs/r2-redis-independent/measured/client.json` |
| 3 | local | true | 201:32 | HTTP_201:32 | 167.166 | 243.108 | 251.308 | 251.308 | 123.3550279362398 | 123.3550279362398 | 0.25941382800010615 | `benchmark/ab-results/20261128T161209Z/runs/r3-local-independent/measured/client.json` |
| 3 | redis | true | 201:32 | HTTP_201:32 | 200.252 | 269.221 | 272.762 | 272.762 | 114.36642430510842 | 114.36642430510842 | 0.2798024000001078 | `benchmark/ab-results/20261128T161209Z/runs/r3-redis-independent/measured/client.json` |

### approval

| 轮次 | 锁 | 有效 | HTTP | 错误码 | 成功 P50 ms | 成功 P95 ms | 成功 P99 ms | 全部 P99 ms | 全部响应 req/s | 成功 req/s | 墙钟 s | 原始 JSON |
| ---: | --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | local | true | 200:1, 409:7 | HTTP_200:1, RESERVATION_CONFLICT:7 | 50.156 | 50.156 | 50.156 | 84.742 | 93.46633187343211 | 11.683291484179014 | 0.0855923179999536 | `benchmark/ab-results/20261128T161209Z/runs/r1-local-approval/measured/client.json` |
| 1 | redis | true | 200:1, 409:7 | HTTP_200:1, RESERVATION_CONFLICT:7 | 38.73 | 38.73 | 38.73 | 64.954 | 121.24712672751808 | 15.15589084093976 | 0.06598094499986473 | `benchmark/ab-results/20261128T161209Z/runs/r1-redis-approval/measured/client.json` |
| 2 | local | true | 200:1, 409:7 | HTTP_200:1, RESERVATION_CONFLICT:7 | 45.314 | 45.314 | 45.314 | 74.106 | 107.44258261675603 | 13.430322827094503 | 0.0744583739999598 | `benchmark/ab-results/20261128T161209Z/runs/r2-local-approval/measured/client.json` |
| 2 | redis | true | 200:1, 409:7 | HTTP_200:1, RESERVATION_CONFLICT:7 | 46.994 | 46.994 | 46.994 | 77.677 | 101.9851511659182 | 12.748143895739775 | 0.07844279200003257 | `benchmark/ab-results/20261128T161209Z/runs/r2-redis-approval/measured/client.json` |
| 3 | local | true | 200:1, 409:7 | HTTP_200:1, RESERVATION_CONFLICT:7 | 54.415 | 54.415 | 54.415 | 92.491 | 85.16482960813505 | 10.645603701016881 | 0.09393548999992163 | `benchmark/ab-results/20261128T161209Z/runs/r3-local-approval/measured/client.json` |
| 3 | redis | true | 200:1, 409:7 | HTTP_200:1, RESERVATION_CONFLICT:7 | 46.639 | 46.639 | 46.639 | 77.51 | 102.003878952508 | 12.7504848690635 | 0.07842838999999913 | `benchmark/ab-results/20261128T161209Z/runs/r3-redis-approval/measured/client.json` |

## 怎么读

审批的成功延迟只有 1 个样本，所以 P50、P95、P99 是同一个数。创建场景 32 个请求都成功时，成功延迟和全部延迟相同。

18 次预热里有 17 次是 1 个 HTTP 201 加 1 个 HTTP 409 `RESOURCE_BUSY`，客户端因此把预热记成 `valid=false`。唯一一次预热两个都成功，是 `r1-redis-independent`。正式结果里，18 格有 17 格 `valid=true`。唯一无效的正式轮是 `r1-redis-independent`：28 个 201、4 个 409 `RESOURCE_BUSY`。这一轮留在表里，没有重跑替换。

这些 `RESOURCE_BUSY` 对应应用日志里的 MySQL 死锁：`Deadlock found when trying to get lock; try restarting transaction`。`r1-redis-independent/app.log` 里能看到插入 `equipment_reservations` 和冲突检查 `FOR UPDATE` 两条语句都卷入死锁。延迟是几十到几百毫秒，不是 `innodb_lock_wait_timeout` 的 50 秒，也不是 Redis 锁等待的 2 秒。全部正式轮的错误码里都没有 `RESERVATION_LOCK_TIMEOUT`。

争用场景两边都是 32/32 创建成功，持久化查询对应该设备 32 条 `PENDING`。Redis 的成功 P50 是 305.866、373.533、475.736 ms，本地锁是 241.345、238.243、243.291 ms。同一台 MySQL 上，32 个学生抢同一设备的日期锁时，Redis 这一组更慢，但 2 秒等待够用，没有人被锁等待丢掉。

独立设备上，三轮本地锁都是 32/32。Redis 的第 2、3 轮也是 32/32，成功 P50 分别是 227.972 ms 和 200.252 ms；本地锁对应轮是 178.314 ms 和 167.166 ms。第 1 轮 Redis 因为 4 个死锁不能当成干净样本。

审批 6 轮都是恰好 1 个 HTTP 200 `APPROVED`、7 个 HTTP 409 `RESERVATION_CONFLICT`。这测的是数据库行锁下的批准，不是应用层锁的开销。Redis 日志里的获取次数来自预热和串行预建，不来自批准请求。

这组数据来自 4 vCPU 的云虚拟机，只说明这一次、这套参数下两种锁的差别。不要和 `benchmark/RESULTS.md` 里 H2 对 MySQL 的整栈数字比快慢。

## 原始数据

- 计划：`benchmark/ab-results/20261128T161209Z/plan.json`
- 环境命令输出：`benchmark/ab-results/20261128T161209Z/environment.txt`
- 每轮恢复用的库快照：`benchmark/ab-results/20261128T161209Z/baseline.sql`（含演示账号的口令哈希，口令就是仓库里的演示口令）
- 编排日志：`benchmark/ab-results/20261128T161209Z/orchestrator.log`
- 每格目录：`app.log`、`java.argv`、`mysql-reservations.txt`、`redis-lock-keys.txt`、`evidence.txt`、`warmup/`、`measured/`
- 复现脚本：`benchmark/run_lock_ab.py`。账号口令从环境变量读，不写进仓库。脚本只针对本机一次性库。

