# CompletableFuture 练习

用「商品详情页聚合」场景练异步编排。5 个模拟远程调用，全部用 `Thread.sleep` 模拟耗时，
`OrderDetailApi.CHAOS` 开关控制是否注入随机故障。

> 环境：**JDK 17**（L4 需要 `completeOnTimeout`，Java 9+ 才有）。
> `build.gradle` 用 toolchain 指定，IDEA 的 language level / bytecode target 同为 17。

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
L4Timeout.java        L4 · 超时兜底 completeOnTimeout
L5AllOf.java          L5 · 批量聚合 allOf
L6ThreadPool.java     L6 · 线程池对比
L7JoinTrap.java       L7 · join 位置反面教材
```

L0~L7 全部完成。`L7JoinTrap` 的两个「变体」（回调里 join、池 1 死锁）还没做。

每个 `main` 里的「建池 / 计时 / 打印 / shutdown」样板是**故意重复**的 ——
这些练习要观察的就是任务提交顺序和 `join` 的位置，抽成工具方法反而把重点藏起来。

## 关卡

| 关 | 目标 | 验收 | 状态 |
|---|---|---|---|
| L0 | 串行基线 | ~880ms，全部 `on main` | ✅ |
| L1 | `supplyAsync` 并行化 | ~420ms，全部 `on pool-1-thread-N` | ✅ |
| L2 | `thenCompose` vs `thenApply` | 把 `queryRecommend` 改成返回 `CompletableFuture` 后仍跑通 | ✅ |
| L3 | `exceptionally` 异常降级 | `CHAOS=true`，评论挂了页面照常渲染 | ✅ |
| L4 | `completeOnTimeout` 超时兜底 | 跑 10 次耗时稳定，不飙到 1.5s | ✅ |
| L5 | `allOf` 批量聚合 | 用 `allOf` 替代逐个 `join` | ✅ |
| L6 | 线程池对比 | 解释不同池大小下的耗时差异 | ✅ |
| L7 | `join` 位置反面教材 | 亲手写出退化成串行的版本 | ✅ 940ms |

### 各关结论

L0/L1/L2 的要点写在各自类注释里。下面是 L3~L6 做完之后记下来的东西：

- **L3 · `exceptionally`** 只在失败时跑，把「失败」就地换成一个正常值，类型不变。
  对照：`handle` 成功失败都进（得自己判 `ex == null`，**正常路径也要手动 `return r`**，
  忘了就把好结果弄丢），`whenComplete` 两边都进但**改不了结果** ——
  它收的是 `BiConsumer`，压根没有返回值可以交给下游。
  一句话：`exceptionally` 救、`handle` 换、`whenComplete` 只看不动。
- **L4 · `completeOnTimeout`** 原理是「谁先填上算谁的」——
  `CompletableFuture` 可以从外部完成，而结果只能填一次。
  `orTimeout` 是超时**抛异常**（配 `exceptionally` 用），`completeOnTimeout` 是超时**填兜底值**。
- **L5 · `allOf`** 返回 `CompletableFuture<Void>`，**只给信号不给结果**，值还得逐个 `join` 取
  （类型不一样，一个盒子装不下 5 种）。它**不加速任何东西**，
  换来的是一个「全部完成」的对象，可以往上挂 `thenRun`。
  内部是 `andTree`：把 N 个 future 两两配对搭二叉树，最后一个完成的顺手把通知推到根，零轮询零额外线程。
  **先降级、后聚合**：`allOf` 里只要有一个失败整个就失败，所以 L3/L4 必须排在 L5 前面。
- **L6 · 线程池** 见下面「线程池大小的影响」。
- **L7 · `join` 的位置** 池给到 5、五个线程全闲着，照样跑出 **940ms**（比 L0 的 880ms 还慢）。
  一句话：**`supplyAsync` 不阻塞，`join` 才阻塞 —— 把 `join` 写在两个 `supplyAsync` 中间，
  第二个任务就永远晚一步提交。**
  最毒的是日志：线程名从 `thread-1` 排到 `thread-5`，五个线程全用上了，看着完全是并行程序，
  **但任何时刻只有 1 个在干活**（轮流用 ≠ 同时用）。
  时间戳 116 → 256 → 471 → 628 → 940 一个接一个，零重叠。
  **光看线程名看不出假异步，得看耗时。**
  还没做的变体：在 `thenApply` 回调里面 join 另一个 future；池大小 1 时在回调里 join 同池任务 → 死锁。

## 笔记

### 方法命名规则

后缀决定回调签名，前缀决定触发时机，每个再 ×3 个执行变体（无后缀 / `Async` / `Async` + Executor）。

| 后缀 | 接口 | 入参 | 返回 | 结果盒子 |
|---|---|---|---|---|
| `...Apply` | `Function` | ✅ | ✅ | `CompletableFuture<R>` |
| `...Accept` | `Consumer` | ✅ | ❌ | `CompletableFuture<Void>` |
| `...Run` | `Runnable` | ❌ | ❌ | `CompletableFuture<Void>` |

`thenApply` ≈ `map`，`thenCompose` ≈ `flatMap`（回调本身返回 Future 时必须用后者）。

**`Async` 不是「要不要异步」的开关，是「换不换线程」的开关。** 带不带它，
main 都不阻塞，也都得等上游完成（那是数据依赖决定的）。区别只在于回调**由谁执行**：
不带 = 谁完成上游谁顺手跑；带 = 重新入队换人跑。

### L1 秘诀

**所有 `supplyAsync` 写在前面，所有 `join` 写在后面。**
`supplyAsync` 不阻塞（只是丢任务进池 + 返回空盒子），`join` 才阻塞。
5 个 `join` 顺序写但总时间不相加 —— 等第一个时其他也在同时倒计时。

`join()` 只有两个分支：**盒子里有东西 → 直接拿走（0ms）；没有 → 站着等**。
而且它是「看一眼」不是「取走」，结果一旦填进盒子就永久留着，取几次都是同一个。

### 线程池大小的影响（L6 实测）

本机 12 核，`CHAOS=false`，每档连跑 3 次：

| 配置 | 实测 | 理论 | `recommend` 开工 |
|---|---|---|---|
| commonPool(11) | 405ms | 380ms | t=80 |
| 池 5 | 405ms | 380ms | t=80 |
| 池 4 | 406ms | 380ms | t=80 |
| 池 2 | 590ms | 570ms | **t=270** |
| 池 1 | 885ms | 850ms | **t=550**（退化成串行，≈ L0 基线） |
| 池 2 + `thenApply` | 485ms | 470ms | t=80 |

多出的时间**不是均摊的**：代价全部集中在把 `queryRecommend`（关键路径上最长的 300ms）
的开工时间往后推。池 5 是 t=80 开工、380 结束；池 2 是 t=270 开工、570 结束 ——
差的 190ms = 270 − 80。

池 2 的两条泳道（`thenApplyAsync`，590ms）：

```
       0        80       120        270  280              570
线程1: |-product-|-------stock------------|      (闲着)
线程2: |---price----|---comments----|------recommend------|
```

线程1 干完 stock 是 t=280，线程2 干完 comments 是 t=270 —— **线程2 先空出来**，
队尾的 recommend 归它。日志里 comments 打印在 stock 前面，差的就是这 10ms。

去掉 `Async` 改成 `thenApply`（485ms）：

```
       0        80      120        320              380        470
线程1: |-product-|----------recommend--------------|    (闲着)
线程2: |---price----|-----stock------|------comments---------|
```

recommend 不入队，由完成 product 的线程1 顺手接着跑，比排队版早 190ms 开工。
线程2 全程没歇过，它那条泳道 120+200+150 = 470 就是答案。

⚠️ **快了 100ms 不是结论**：省下的排队时间是靠占用**上游的完成线程**换来的，
这次恰好有线程2 顶着才没出事。回调里有阻塞操作时一律用 `Async` + 显式 executor。

不传 executor 时默认丢进 `ForkJoinPool.commonPool()` —— 大小固定是「核数 − 1」，
**全 JVM 共享**。在上面跑阻塞 IO 是经典事故。

`newFixedThreadPool` 用的是**无界** `LinkedBlockingQueue`，生产环境应改用
`new ThreadPoolExecutor(...)` 显式指定有界队列 + 拒绝策略。

### 阻塞与不阻塞（L5 延伸）

`join()` = 站在出餐口等；`thenRun()` = 留句话就走，让最后完成的那个线程顺手把活干了。
`allOf` 把「全齐了」变成一个能留话的对象。

**客人等的时间没变，变的是服务员有没有被占住。** 前面几关练的是「让 5 个任务并行」，
省的是**时间**；`thenRun` 练的是「让线程不被占住」，省的是**线程**。
真实 Web 服务里那 200 个请求线程干等 380ms，没在算任何东西，纯耗着。

实测（同一段「招呼下一桌」的代码，位置没动，只看时间戳）：

| 写法 | 服务员腾出手 | 客人吃上饭 |
|---|---|---|
| `allOf.join()` | @405ms | @403ms |
| `allOf.thenRun(...)` | **@2ms** | @407ms |

### 踩过的坑

- **不带 `Async` 的回调跑在上游的完成线程上**，不受控制。回调里有阻塞操作时一律用 `Async` + 显式 executor。
- **`pool.shutdown()` 要放 `finally`**：`join` 抛异常时会跳过它，线程池的非守护线程吊着 JVM 不退出。L3 打开 CHAOS 后会亲眼看到。
- **`Supplier.get()` 不允许抛受检异常**，所以 `sleep()` 里要把 `InterruptedException` 包成非受检的。包之前记得 `Thread.currentThread().interrupt()` 恢复中断标志，否则 `shutdownNow()` 关不掉线程池。
- **`thenApplyAsync` 不是「现在提交任务」，是「等上游好了再提交」**。
  所以 main 提前 `pool.shutdown()` 会让它撞上 `RejectedExecutionException`，整条链异常完成。
  L5 改成 `thenRun` 不阻塞之后踩到过 —— 修法是在 `shutdown()` 前 `join()` 一下末端那个 future。
- **`thenRun` 只在正常完成时跑**。上游一异常它就悄悄不执行，不报错不打印，
  你只会觉得「怎么没输出」。异步链末尾挂个 `whenComplete` 打异常，否则连尸体都找不到。
- **降级值不能跟真实值撞**。`queryStock` 正常返回 42，兜底也填 42 就永远分不清哪次降级了；
  更糟的是填 0（页面显示「无货」）—— **降级值选错方向比不降级还伤**。
- **超时不等于取消**。`completeOnTimeout` 只是让你早点拿到值，慢任务还在池里睡满 1.5s。
  `shutdown()` 是「不收新活」，不是「掐掉在跑的」，想掐得用 `shutdownNow()`。
- **链式方法多数返回新 future，但有例外**：`completeOnTimeout` / `orTimeout` 返回的是 `this`
  （javadoc 写着 `@return this CompletableFuture`），是「在原盒子上装个闹钟」，不是「接一个新工位」。
  判断方法：看 javadoc 写的是 `@return this` 还是 `@return the new CompletionStage`。
