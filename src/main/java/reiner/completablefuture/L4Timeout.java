package reiner.completablefuture;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * L4 · 超时兜底：completeOnTimeout
 * <p>
 * 场景：库存服务 20% 概率卡 1500ms。详情页不能因为库存慢就整体拖到 1.5s，
 * 超过 300ms 就别等了，先按「有货」渲染。
 * <p>
 * 准备两件事：
 * <ol>
 *   <li>{@code OrderDetailApi.CHAOS = true;} —— 概率触发，跑一次可能不复现，多跑几次</li>
 *   <li><b>把线程池从 2 改成 5</b>：池 2 时那个卡住的 {@code queryStock}
 *       会占死两个线程里的一个，剩下的任务全挤在另一条线上排队，
 *       你会误以为「超时没生效」。那是线程被占死的问题，属于 L6，先把变量隔离掉</li>
 * </ol>
 * <p>
 * 步骤：
 * <ol>
 *   <li>先<b>不加</b>超时裸跑十来次，记下耗时。大多数次正常，偶尔一次飙到 1.5s+
 *       —— 这就是要治的病</li>
 *   <li>给 stockF 挂 {@code .completeOnTimeout(兜底值, 300, TimeUnit.MILLISECONDS)}，
 *       再跑十次，耗时稳住</li>
 *   <li>盯着程序<b>退出</b>的时机看，见下面第二个坑</li>
 * </ol>
 * <p>
 * 原理一句话：{@code CompletableFuture} 可以<b>从外部被完成</b>，
 * 而且结果只能填一次，谁先到算谁的。
 * 库存 200ms 正常返回就用真实值；卡住了，定时器就抢先把兜底值填进去，
 * 后到的真实值再也塞不进来。
 * <p>
 * 顺带分清两个名字像的：{@code orTimeout} 是超时就<b>异常完成</b>
 * （得配 L3 的 {@code exceptionally} 一起用）；
 * {@code completeOnTimeout} 是超时就<b>填兜底值</b>。这一关要的是后者。
 * <p>
 * ⚠ <b>兜底值别用 0。</b>{@code queryStock} 正常返回 42（件数），
 * 填 0 页面会显示「无货」—— 用户直接走了，比慢更糟。
 * 要「按有货渲染」，兜底值得明确表示「有货、数量未知」，
 * 比如 999，或者定个常量 {@code STOCK_UNKNOWN} 让语义自己说话。
 * <b>降级值选错方向，比不降级还伤</b>—— 这是这一关真正的业务坑。
 * <p>
 * ⚠ <b>超时不等于取消。</b>{@code completeOnTimeout} 只是让你早点拿到值，
 * 那个睡 1.5 秒的 {@code queryStock} 任务<b>还在池里跑</b>，没人掐它。
 * 表现：打印的耗时稳定在 400ms 上下（验收通过），但程序要愣一下才真正退出。
 * {@code pool.shutdown()} 是「不收新活了」，不是「把在跑的掐掉」——
 * 想掐得用 {@code shutdownNow()}。
 * <p>
 * 验收：CHAOS 开着连跑 10 次，总耗时始终稳定，不会某次飙到 1.5s。
 * <p>
 * 延伸（想懂原理就做，不做也不影响过关）：把这一行拆开自己实现一遍 ——
 * <pre>
 *   ScheduledExecutorService timer = Executors.newScheduledThreadPool(1);
 *   timer.schedule(() -&gt; stockF.complete(999), 300, TimeUnit.MILLISECONDS);
 *   // 最后别忘了 timer.shutdown()，多一个执行器就多一个不关就不退出的线程
 * </pre>
 * {@code completeOnTimeout} 内部干的就是这件事，自己写一遍就不神秘了。
 * <p>
 * 环境：项目已在本关升到 <b>JDK 17</b>——{@code build.gradle} 改用 toolchain 指定，
 * IDEA 的 language level / bytecode target 同步改成 17。
 * {@code completeOnTimeout} / {@code orTimeout} 这些 Java 9+ 的 API 现在可以直接用。
 */
public class L4Timeout {


    public static void main(String[] args) {
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
