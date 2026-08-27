# CompletableFuture 练习

用「商品详情页聚合」场景练异步编排。5 个模拟远程调用，全部用 `Thread.sleep` 模拟耗时，
`OrderDetailApi.CHAOS` 开关控制是否注入随机故障。

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

## 文件结构

脚手架和编排分开：`OrderDetailApi` 只提供 5 个假 RPC，不含 `main`；
每一关的编排各自一个类、各自一个 `main`，**任何一关都能随时单独重跑**，用来跟别的关卡对照耗时。

```
OrderDetailApi.java   5 个模拟远程调用 + CHAOS 开关（无 main）
Product.java          商品主数据，带 categoryId
L0Serial.java         L0 · 串行基线
L1Parallel.java       L1 · 并行化
L2Compose.java        L2 · 串行依赖 thenCompose
L3Fallback.java       L3 · 异常降级 exceptionally
L4Timeout.java        L4 · 超时兜底
L5AllOf.java          L5 · 批量聚合 allOf
L6ThreadPool.java     L6 · 线程池对比
L7JoinTrap.java       L7 · join 位置反面教材
```

L3~L7 目前只有类注释（目标、步骤、验收、坑），实现待写。

每个 `main` 里的「建池 / 计时 / 打印 / shutdown」样板是**故意重复**的 ——
这些练习要观察的就是任务提交顺序和 `join` 的位置，抽成工具方法反而把重点藏起来。

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

### 各关要点

L0/L1 的要点已经写在 `L0Serial` / `L1Parallel` 的类注释里，下面是还没开的关：

- **L2** 故意先用 `thenApply` 写一遍，看到 `CompletableFuture<CompletableFuture<List<String>>>`
  这个套娃类型再换成 `thenCompose`。这个「踩一脚」比直接看对的写法记得牢。
  注意：套娃版**不是编译不过**，只要肯把左边类型写成两层它照样跑通 —— 真正的代价是这个类型会往上游传染。
- **L3** 评论挂了不该让整个页面 500，降级成空列表 → `exceptionally`。
  然后对比：把它换成 `handle` 和 `whenComplete`，观察三者行为差异（尤其 `whenComplete` 改不了结果这点）。
- **L4** 库存偶尔卡 1.5s → `completeOnTimeout(有货, 300, MILLISECONDS)`。
- **L5** 把 5 个结果用 `allOf` 统一等待，再逐个 `join` 取值。
  注意体会 `allOf` 返回的是 `CompletableFuture<Void>` —— 不带结果，这是新手最常卡住的地方。
- **L6**（最重要的一关）
  1. 全程用默认池跑，看线程名是 `ForkJoinPool.commonPool-worker-N`
  2. 换成 `Executors.newFixedThreadPool(4)` 显式传入，看线程名变成 `pool-1-thread-N`
  3. 故意实验：把线程池设成 `newFixedThreadPool(1)`，再发起 5 个并行任务，观察它怎么退化回串行 ——
     这就是生产上「用 commonPool 跑阻塞 IO 拖垮全站」的微缩版
  4. 对比 `thenApply` 和 `thenApplyAsync` 打出来的线程名分别是什么
- **L7** 在链条中间插一个 `.join()`，测耗时，看它怎么把并行打回串行。
  亲手写一次这个 bug，以后 review 别人代码时一眼就能看出来。

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
