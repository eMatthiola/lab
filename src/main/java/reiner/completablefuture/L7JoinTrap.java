package reiner.completablefuture;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * L7 · 反面教材：join 的位置
 * <p>
 * 前面所有关卡都遵守一条秘诀：<b>所有 supplyAsync 写在前面，所有 join 写在后面。</b>
 * 这一关反着来，亲手把它写坏，看看坏在哪。
 * <p>
 * 做法：在链条<b>中间</b>插一个 {@code join()}，比如
 * <pre>
 *   CompletableFuture&lt;Product&gt; productF =
 *           CompletableFuture.supplyAsync(() -&gt; api.queryProduct(id), pool);
 *   Product product = productF.join();          // ← 就这一行
 *
 *   CompletableFuture&lt;Integer&gt; priceF =
 *           CompletableFuture.supplyAsync(() -&gt; api.queryPrice(id), pool);
 *   ...
 * </pre>
 * <p>
 * 为什么会退化：{@code supplyAsync} 只是「把任务扔进队列然后立刻返回」，
 * 所以只要你把 5 个 supplyAsync 连着写完，5 个任务就已经同时在跑了。
 * 但中间插一个 {@code join()}，main 线程就<b>停在那儿等</b> ——
 * 后面那几个 supplyAsync 根本还没执行到，任务压根没提交，谈何并行。
 * <p>
 * 耗时会从 ~620ms 涨回 L0 的 ~880ms —— 代码看起来全是 CompletableFuture，
 * 跑起来是纯串行。这是 review 时最常见的一类假异步。
 * <p>
 * 验收：写出退化版本，耗时接近 L0 基线；能一句话说清楚为什么。
 * <p>
 * 变体（也很常见，可以顺手试）：
 * <ul>
 *   <li>在 {@code thenApply} 的回调<b>里面</b> join 另一个 future</li>
 *   <li>更狠的：回调跑在池里，又在回调里 join 同一个池的任务，池大小 1 时直接<b>死锁</b>
 *       —— 池里唯一的线程在等一个永远排不上队的任务</li>
 * </ul>
 */
public class L7JoinTrap {

    public static void main(String[] args) {
        OrderDetailApi api = new OrderDetailApi();
        int id = 1;

        long start = System.currentTimeMillis();

        // 池给到 5，五个线程全闲着 —— 这一关的重点就是：【池子多大都救不了你】
        ExecutorService pool = Executors.newFixedThreadPool(5);

        try {
            // ==================== 反面写法：下一单，取一次餐，再下一单 ====================
            // 前六关都是「5 张单子一口气递完，最后统一取餐」。
            // 这里反着来：每递一张单子就站在窗口把它等回来，再递下一张。
            //
            // supplyAsync 只是「把单子递进后厨」，本身不花时间。
            // 但 main 卡在下面第一个 join() 上的那 80ms 里，
            // 第二张单子【还没递出去】——代码根本没执行到那一行。
            // 后厨 4 个厨师全程闲着，因为压根没人给他们单子。
            // ========================================================================

            CompletableFuture<Product> productF =
                    CompletableFuture.supplyAsync(() -> api.queryProduct(id), pool);
            Product product = productF.join();                  // 0 → 80，main 停在这里
            mark(start, "queryProduct");

            CompletableFuture<Integer> priceF =
                    CompletableFuture.supplyAsync(() -> api.queryPrice(id), pool);
            Integer price = priceF.join();                      // 80 → 200
            mark(start, "queryPrice");

            CompletableFuture<Integer> stockF =
                    CompletableFuture.supplyAsync(() -> api.queryStock(id), pool);
            Integer stock = stockF.join();                      // 200 → 400
            mark(start, "queryStock");

            CompletableFuture<List<String>> commentsF =
                    CompletableFuture.supplyAsync(() -> api.queryComments(id), pool);
            List<String> comments = commentsF.join();           // 400 → 550
            mark(start, "queryComments");

            CompletableFuture<List<String>> recommendF =
                    CompletableFuture.supplyAsync(() -> api.queryRecommend(product.categoryId), pool);
            List<String> recommend = recommendF.join();         // 550 → 850
            mark(start, "queryRecommend");

            long cost = System.currentTimeMillis() - start;
            System.out.println("---- 「并行」总耗时: " + cost + "ms ----");
            System.out.println(product + " price=" + price + " stock=" + stock
                    + " comments=" + comments + " recommend=" + recommend);

            // ==================== 结论 ====================
            // 满屏 CompletableFuture、supplyAsync、线程名全是 pool-1-thread-N，
            // 跑出来 ~880ms —— 跟 L0 那个连 CompletableFuture 都没用的串行版一模一样。
            //
            // 一句话：【supplyAsync 不阻塞，join 才阻塞。
            //          把 join 写在两个 supplyAsync 中间，第二个任务就永远晚一步提交。】
            //
            // 而且注意线程名：每次 join 完，池里那个线程就空了，
            // 下一个任务又被分给它（或另一个），所以你会看到 thread 编号在跳，
            // 看着「用了好几个线程」，实际上任何时刻都只有 1 个在干活。
            // 这是 review 时最容易看走眼的一类假异步 —— 光看线程名是看不出来的，得看耗时。
            // ============================================
        } finally {
            pool.shutdown();
        }
    }

    /** 打一个时间戳，用来看每个任务实际是什么时候完成的 */
    private static void mark(long start, String name) {
        System.out.println("    ↑ " + name + " 完成 @" + (System.currentTimeMillis() - start) + "ms");
    }
}
