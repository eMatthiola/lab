package reiner.completablefuture;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * L5 · 批量聚合：allOf
 * <p>
 * 现在 5 个 {@code join()} 是一行行写的。改成用 {@code allOf} 统一等待，再逐个取值。
 * <p>
 * <pre>
 *   CompletableFuture&lt;Void&gt; all =
 *           CompletableFuture.allOf(productF, priceF, stockF, commentsF, recommendF);
 *   all.join();          // 一次等完
 *   Product p = productF.join();   // 此时全都完成了，这些 join 不再阻塞
 * </pre>
 * <p>
 * 要体会的点：{@code allOf} 返回的是 {@code CompletableFuture<Void>} —— <b>不带结果</b>。
 * 它只负责「全都完成了」这个信号，值还得从原来那几个 future 上一个个拿。
 * 这是新手最常卡住的地方：以为 allOf 会把结果收集成一个 List。
 * <p>
 * 想一想：既然最后还是要逐个 {@code join}，那 {@code allOf} 到底带来了什么？
 * （提示：跟 L3 的异常处理、L4 的超时结合起来看 —— 有一个「全部完成」的时间点可以挂后续动作，
 * 跟「在 main 里一个个阻塞等」不是一回事。试试 {@code all.thenRun(() -> 渲染页面)}。）
 * <p>
 * 顺带对比 {@code anyOf}：任意一个完成就返回，返回 {@code CompletableFuture<Object>}。
 * <p>
 * 验收：用 {@code allOf} 替代逐个 join，耗时与 L2 持平。
 */
public class L5AllOf {

    public static void main(String[] args) {
        System.out.println("我是服务员，在 " + Thread.currentThread().getName());
        OrderDetailApi api = new OrderDetailApi();
        int id = 1;

        long start = System.currentTimeMillis();

        ExecutorService pool = Executors.newFixedThreadPool(5);

        // ---- 4 条互不依赖的线，supplyAsync 立刻返回，四个任务同时开跑 ----
        CompletableFuture<Product> productF =
                CompletableFuture.supplyAsync(() -> api.queryProduct(id), pool);

        CompletableFuture<Integer> priceF =
                CompletableFuture.supplyAsync(() -> api.queryPrice(id), pool);

        CompletableFuture<Integer> stockF =
                CompletableFuture.supplyAsync(() -> api.queryStock(id), pool)
                        .completeOnTimeout(999,300, TimeUnit.MILLISECONDS);

        CompletableFuture<List<String>> commentsF =
                CompletableFuture.supplyAsync(() -> api.queryComments(id), pool)
                        .exceptionally(ex -> {System.out.println("评论服务降级：" + ex.getCause());
                            return Collections.emptyList();});


        // ---- 第 1 条线的延续：必须先拿到 Product，才知道 categoryId ----
        //      product 就是 productF 完成后的结果
        CompletableFuture<List<String>> recommendF =
                productF.thenApplyAsync(product -> api.queryRecommend(product.categoryId), pool);

        // ---- 到这里为止一个任务都没等，main 只花了几毫秒 ----
        //      5 条线早就在池里并行跑了，下面才开始等。

        // ==================== 用 allOf 和不用 allOf 的区别 ====================
        //
        // 不用（L4 的写法）：5 个 join 一行行排队等
        //      productF.join()    盒子空 → 等到 t=80
        //      priceF.join()      到这儿已经 t=80，price 在 t=120 好 → 再等 40ms
        //      ...                后面几个陆续发现「已经好了」→ 0ms
        //      总时间 = 最慢的那条线 = 380ms
        //
        // 用（现在这样）：等待全压进 allOf.join() 一行
        //      总时间 = 最慢的那条线 = 380ms
        //
        // 【两种写法耗时一模一样】。allOf 不加速任何东西 ——
        // 任务什么时候提交、什么时候跑完，它一个字都没改。
        //
        // 那它换来了什么？换来一个【统一的「全部完成」时间点】。
        // 5 个散着的 join 是 5 个时刻，你只能用 main 线程一个个去等；
        // allOf 把它们收成一个 future，于是可以往上挂东西：
        //      allOf.thenRun(() -> 渲染页面);   ← 留个话就走，main 不用站着等
        // 这才是 allOf 的用处，下一步要试的就是这个。
        // ====================================================================

        // ---- allOf 内部怎么知道「全好了」----
        //      不是开线程轮询问「你好了吗」，而是【登记回调】：
        //      把 5 个盒子两两配对搭成一棵二叉树（源码里的 andTree / BiRelay），
        //      每个节点的规则只有一条 —— 我下面两个都完成了，我才算完成。
        //      最后一个完成的任务，顺手把通知一路推到树根，allOf 的格子才被填上。
        //      零轮询、零额外线程。
        CompletableFuture<Void> allOf = CompletableFuture.allOf(productF, stockF, priceF, commentsF, recommendF);

        // ======================== 服务员比方 ========================
        // 一个线程 = 一个服务员。客人点了 5 样菜，
        // 后厨（线程池）5 个厨师同时做，最慢的那样 380ms。
        // 这 380ms 里服务员干什么？两种干法：
        //
        //   干法一：allOf.join()
        //       站在出餐口盯着，一动不动 380ms。门口新来的客人没人理。
        //
        //   干法二（现在这样）：allOf.thenRun(() -> 上菜)
        //       递单子时多说一句「5 样齐了直接端给 3 号桌」，
        //       说完转身去招呼下一位。380ms 后厨师自己端出去。
        //
        // 关键：3 号桌那位客人，两种干法下都等 380ms——菜就是要做那么久。
        //       区别是干法二里【服务员没被占住】，同样一个人能多接十桌。
        //       省的不是客人的时间，是服务员的时间。
        //
        // 这个练习里 main 反正没别的事干，看不出差别。但真实 Web 服务里
        // 「服务员」是 Tomcat 那 200 个请求线程，每个用户占一个。
        // 干等 380ms 的线程没在算任何东西，纯耗着——这才是要省的东西。
        //
        // 实测（同一段「招呼下一桌」的代码，位置一个字没动，只看时间戳）：
        //       干法一   招呼 2 号桌 @405ms   ← 被占住了
        //       干法二   招呼 2 号桌 @2ms     ← 立刻自由
        // ========================================================================

        // ---- 干法一：站着等（留作对照。想跑就解开这段，把下面 thenRun 那段注掉）----
//        allOf.join();
//        Product product          = productF.join();
//        Integer price            = priceF.join();
//        Integer stock            = stockF.join();
//        List<String> comments    = commentsF.join();
//        List<String> recommend   = recommendF.join();
//        long cost = System.currentTimeMillis() - start;
//        System.out.println("---- 并行总耗时: " + cost + "ms ----");
//        System.out.println(product + " price=" + price + " stock=" + stock
//                + " comments=" + comments + " recommend=" + recommend);
//        System.out.println("上菜 on " + Thread.currentThread().getName());

        // ---- 干法二：留句话就走 ----
        //      thenRun 返回一个【新的 future】，代表「上菜这件事」本身。
        //      拿住它（done），下面才有得等 —— 这是最后那个坑的解药。
        CompletableFuture<Void> done = allOf.thenRun(() -> {
            Product product          = productF.join();   // 全好了，这 5 行 0ms
            Integer price            = priceF.join();
            Integer stock            = stockF.join();
            List<String> comments    = commentsF.join();
            List<String> recommend   = recommendF.join();

            long cost = System.currentTimeMillis() - start;
            System.out.println("---- 并行总耗时: " + cost + "ms ----");
            System.out.println(product + " price=" + price + " stock=" + stock
                    + " comments=" + comments + " recommend=" + recommend);
            System.out.println("上菜 on " + Thread.currentThread().getName());
        });

        // ---- 异步链上必须留个口子看异常 ----
        //      thenRun【只在正常完成时】才跑。上游一旦异常完成，它就悄悄不执行，
        //      不报错、不打印，你只会觉得「上菜怎么没了」。
        //      下面这行是唯一能看见尸体的地方 —— L3 的回马枪。
        done.whenComplete((r, ex) -> {
            if (ex != null) System.out.println("!! 上菜失败: " + ex.getCause());
        });

        // ---- 服务员没被占住，立刻就走到这儿了 ----
        //      下面这几行就是「去招呼其他客人」。它不是什么特殊 API，就是普通代码。
        //      干法一里它们要等到 @405ms 才轮得上，干法二里 @2ms 就执行了。
        System.out.println("服务员走了 on " + Thread.currentThread().getName()
                + "，此刻才过了 " + (System.currentTimeMillis() - start) + "ms");
        for (int i = 2; i <= 3; i++) {
            System.out.println("招呼 " + i + " 号桌 @" + (System.currentTimeMillis() - start) + "ms");
        }

        // ======================== 走之前必须收工 ========================
        // 少了下面那行 done.join()，「上菜」会失败，报 RejectedExecutionException。
        //
        // 为什么？回头看这一行：
        //       recommendF = productF.thenApplyAsync(..., pool);
        // 它【并没有】把任务提交给池，只是登记「等 productF 好了再提交」。于是：
        //
        //       t=2ms    main 一路跑到 pool.shutdown() → 池开始关门
        //       t=80ms   productF 完成 → 这时才去提交查推荐的活 → 池已经关门了
        //                → RejectedExecutionException
        //                → recommendF 异常完成 → allOf 异常完成 → thenRun 不执行
        //
        // 你走得太早，把厨房门锁了，后面那道菜根本没人做。
        // 干法一没这问题，是因为 join() 把 main 摁在那儿 400ms，活干完才走到 shutdown。
        //
        // 修法不是「回去站着等」，而是【中间全程不等，只在最外层等一次】：
        done.join();        // ← 等「上菜」这件事真的做完
        pool.shutdown();    // ← 再关门
        //
        // 真实服务里这一步是交给框架的：Controller 直接返回 CompletableFuture，
        // 框架在最外层等，你的业务线程一秒都没被占住。
        // ================================================================
    }
}
