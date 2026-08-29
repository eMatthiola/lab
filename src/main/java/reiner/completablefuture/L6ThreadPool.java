package reiner.completablefuture;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * L6 · 线程池（最重要的一关）
 * <p>
 * 前面几关都在练「怎么编排」，这一关练「跑在谁身上」。生产事故基本都出在这里。
 * <p>
 * 拿 L2 的编排当底子，只改线程池，四个实验：
 * <ol>
 *   <li><b>去掉所有 pool 参数</b>，全程用默认池。看线程名变成
 *       {@code ForkJoinPool.commonPool-worker-N}。
 *       这就是不传 executor 时的默认去处 —— 大小 = CPU 核数 - 1，全 JVM 共享。</li>
 *   <li><b>换回 {@code newFixedThreadPool(4)}</b> 显式传入，线程名变回 {@code pool-1-thread-N}。</li>
 *   <li><b>故意把池设成 {@code newFixedThreadPool(1)}</b>，再发起 5 个并行任务，
 *       观察耗时怎么退化回 L0 的串行水平 —— 5 个任务排一队，一个个来。
 *       这就是生产上「用 commonPool 跑阻塞 IO 拖垮全站」的微缩版：
 *       池被阻塞任务占满，后面的活全在排队。</li>
 *   <li><b>对比 {@code thenApply} 和 {@code thenApplyAsync}</b> 打出来的线程名。
 *       在回调里加 {@code System.out.println(Thread.currentThread().getName())} 就能看到。
 *       （L2 里已经提前踩过一次：不带 Async 是「谁完成上游谁顺手接着跑」，
 *       带 Async 是「重新入队换人跑」。）</li>
 * </ol>
 * <p>
 * 实测数据（本机 12 核，CHAOS=false，每档连跑 3 次取代表值）：
 * <pre>
 *   配置                实测      理论     recommend 开工
 *   -----------------------------------------------------------
 *   commonPool(11)     405ms     380ms    t=80
 *   池 5               405ms     380ms    t=80
 *   池 4               406ms     380ms    t=80
 *   池 2               590ms     570ms    t=270   排在 stock、comments 后面
 *   池 1               885ms     850ms    t=550   退化成串行，约等于 L0 基线
 *
 *   池 2 + thenApply   485ms     470ms    t=80    不入队，顺手就跑
 * </pre>
 * 多出来的时间<b>不是均摊的</b>：代价全部集中在把 {@code queryRecommend}
 * （关键路径上最长的 300ms 任务）的开工时间往后推 ——
 * 池 5 是 t=80 开工、380 结束，池 2 是 t=270 开工、570 结束，差的 190ms = 270 - 80。
 * <p>
 * 池 2 的两条泳道（{@code thenApplyAsync}，590ms）：
 * <pre>
 *          0        80       120        270  280              570
 *   线程1: |-product-|-------stock------------|      (闲着)
 *   线程2: |---price----|---comments----|------recommend------|
 * </pre>
 * 线程1 干完 stock 是 t=280，线程2 干完 comments 是 t=270 —— <b>线程2 先空出来</b>，
 * 于是队尾的 recommend 归它。日志里 comments 打印在 stock 前面，差的就是这 10ms。
 * <p>
 * 池 2 的两条泳道（去掉 Async 改成 {@code thenApply}，485ms）：
 * <pre>
 *          0        80      120        320              380        470
 *   线程1: |-product-|----------recommend--------------|    (闲着)
 *   线程2: |---price----|-----stock------|------comments---------|
 * </pre>
 * recommend 不入队，由<b>完成 product 的线程1 顺手接着跑</b>，比排队版早 190ms 开工。
 * 线程2 全程没歇过，它那条泳道的总和 120+200+150 = 470 就是答案。
 * <p>
 * ⚠ 去掉 {@code Async} 反而快 100ms，但<b>别当成结论</b>：它省下的排队时间，
 * 是靠占用【上游的完成线程】换来的 —— 这次恰好有线程2 顶着才没出事。
 * 回调里一旦有阻塞操作，占的可能是 commonPool 的全局共享 worker，
 * 或者 Netty/Reactor 的 IO 线程，直接把整条连接堵死。
 * <b>回调里有阻塞操作时，一律用 Async + 显式 executor。</b>
 * <p>
 * 验收：能解释清楚每档池大小的耗时是怎么来的（画出时间线，谁在什么时候拿到线程）。
 * <p>
 * 延伸：{@code newFixedThreadPool} 用的是<b>无界</b> {@code LinkedBlockingQueue}，
 * 任务堆积时会 OOM。生产环境应改用 {@code new ThreadPoolExecutor(...)}
 * 显式指定有界队列 + 拒绝策略。
 */
public class L6ThreadPool {

    public static void main(String[] args) {
        OrderDetailApi api = new OrderDetailApi();
        int id = 1;

        long start = System.currentTimeMillis();

        ExecutorService pool = Executors.newFixedThreadPool(2);

        // ---- 4 条互不依赖的线，supplyAsync 立刻返回，四个任务同时开跑 ----
        CompletableFuture<Product> productF =
                CompletableFuture.supplyAsync(() -> api.queryProduct(id), pool);
        CompletableFuture<Integer> priceF =
                CompletableFuture.supplyAsync(() -> api.queryPrice(id), pool);
        CompletableFuture<Integer> stockF =
                CompletableFuture.supplyAsync(() -> api.queryStock(id), pool);
        CompletableFuture<List<String>> commentsF =
                CompletableFuture.supplyAsync(() -> api.queryComments(id), pool);

        // ---- 第 1 条线的延续：必须先拿到 Product，才知道 categoryId ----
        //      product 就是 productF 完成后的结果
        CompletableFuture<List<String>> recommendF =
                productF.thenApply(product -> api.queryRecommend(product.categoryId));

        // ---- 到这里为止一个任务都没等，main 只花了几毫秒 ----
        //      join() 才开始阻塞等待，此时几条线早已在并行跑了
        Product product = productF.join();
        Integer price = priceF.join();
        Integer stock = stockF.join();
        List<String> comments = commentsF.join();
        List<String> recommend = recommendF.join();

        long cost = System.currentTimeMillis() - start;
        System.out.println("---- 并行总耗时: " + cost + "ms ----");
        System.out.println(product + " price=" + price + " stock=" + stock
                + " comments=" + comments + " recommend=" + recommend);

        pool.shutdown();
    }
}
