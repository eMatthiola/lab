# CompletableFuture 练习

用「商品详情页聚合」场景练异步编排。5 个模拟远程调用，全部用 `Thread.sleep` 模拟耗时，
`OrderDetail.CHAOS` 开关控制是否注入随机故障。

## 场景

| 方法 | 耗时 | 特点 |
|---|---|---|
| `queryProduct(id)` | 80ms | 返回含 `categoryId` 的 `Product` |
| `queryPrice(id)` | 120ms | 独立 |
| `queryStock(id)` | 200ms | CHAOS 开启时 20% 概率卡 1500ms |
| `queryComments(id)` | 150ms | CHAOS 开启时 10% 概率抛异常 |
| `queryRecommend(categoryId)` | 300ms | **依赖 `queryProduct` 的结果** |

依赖关系（关键路径 = 380ms）：

```
┌─ queryProduct ─80ms─→ queryRecommend ─300ms─┐  ← 关键路径 380ms
├─ queryPrice ────120ms───────────────────────┤
├─ queryStock ────200ms───────────────────────┤
└─ queryComments ─150ms───────────────────────┘
```

## 关卡

| 关 | 目标 | 验收 | 状态 |
|---|---|---|---|
| L0 | 串行基线 | ~880ms，全部 `on main` | ✅ |
| L1 | `supplyAsync` 并行化 | ~420ms，全部 `on pool-1-thread-N` | ✅ |
| L2 | `thenCompose` vs `thenApply` | 把 `queryRecommend` 改成返回 `CompletableFuture` 后仍跑通 | ⬜ |
| L3 | `exceptionally` 异常降级 | `CHAOS=true`，评论挂了页面照常渲染 | ⬜ |
| L4 | `completeOnTimeout` 超时兜底 | 跑 10 次耗时稳定，不飙到 1.5s | ⬜ |
| L5 | `allOf` 批量聚合 | 用 `allOf` 替代逐个 `join` | ⬜ |
| L6 | 线程池对比 | 解释不同池大小下的耗时差异 | ⬜ |
| L7 | `join` 位置反面教材 | 亲手写出退化成串行的版本 | ⬜ |

## 笔记

### 方法命名规则

后缀决定回调签名，前缀决定触发时机，每个再 ×3 个执行变体（无后缀 / `Async` / `Async` + Executor）。

| 后缀 | 接口 | 入参 | 返回 | 结果盒子 |
|---|---|---|---|---|
| `...Apply` | `Function` | ✅ | ✅ | `CompletableFuture<R>` |
| `...Accept` | `Consumer` | ✅ | ❌ | `CompletableFuture<Void>` |
| `...Run` | `Runnable` | ❌ | ❌ | `CompletableFuture<Void>` |

`thenApply` ≈ `map`，`thenCompose` ≈ `flatMap`（回调本身返回 Future 时必须用后者）。

### L1 秘诀

**所有 `supplyAsync` 写在前面，所有 `join` 写在后面。**
`supplyAsync` 不阻塞（只是丢任务进池 + 返回空盒子），`join` 才阻塞。
5 个 `join` 顺序写但总时间不相加 —— 等第一个时其他也在同时倒计时。

### 线程池大小的影响（L1 实验）

| 池大小 | 耗时 |
|---|---|
| 5 | 421ms |
| 2 | 611ms |

多出的 190ms **不是均摊的**：`queryRecommend`（最长，300ms）排在队尾，
到 t=270 才拿到线程。
→ 并行度不足的代价是**推迟关键路径上最长任务的开工时间**，不是每个任务都慢一点。

`newFixedThreadPool` 用的是**无界** `LinkedBlockingQueue`，生产环境应改用
`new ThreadPoolExecutor(...)` 显式指定有界队列 + 拒绝策略。

### 踩过的坑

- **不带 `Async` 的回调跑在上游的完成线程上**，不受控制。回调里有阻塞操作时一律用 `Async` + 显式 executor。
- **`pool.shutdown()` 要放 `finally`**：`join` 抛异常时会跳过它，线程池的非守护线程吊着 JVM 不退出。L3 打开 CHAOS 后会亲眼看到。
- **`Supplier.get()` 不允许抛受检异常**，所以 `sleep()` 里要把 `InterruptedException` 包成非受检的。包之前记得 `Thread.currentThread().interrupt()` 恢复中断标志，否则 `shutdownNow()` 关不掉线程池。
- ⚠️ **L4 的 `orTimeout` / `completeOnTimeout` 是 Java 9+**，当前 `sourceCompatibility = '1.8'`，练到那关需要先升到 17。
