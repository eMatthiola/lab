package reiner.completablefuture;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * L3 · 异常降级：exceptionally
 * <p>
 * 场景：评论服务 10% 概率抛异常。评论挂了不该让整个详情页 500，该降级成空列表照常渲染。
 * <p>
 * 准备：main 第一行加 {@code OrderDetailApi.CHAOS = true;} 打开故障注入。
 * 因为是概率触发，跑一次可能不复现，多跑几次。
 * <p>
 * 步骤：
 * <ol>
 *   <li>先<b>不加</b>任何处理直接跑，看异常怎么从 {@code join()} 抛出来
 *       —— 注意抛的是 {@code CompletionException}，不是你原来那个 {@code RuntimeException}，
 *       真正的异常在 {@code getCause()} 里</li>
 *   <li>给 commentsF 挂 {@code .exceptionally(ex -> Collections.emptyList())}，页面恢复正常</li>
 *   <li>换成 {@code handle((r, ex) -> ...)} 再写一遍，体会它<b>正常和异常都会进</b></li>
 *   <li>换成 {@code whenComplete((r, ex) -> ...)}，观察它<b>改不了结果</b>
 *       —— 这是三者最大的区别，异常照样往下传</li>
 * </ol>
 * 验收：CHAOS 开着连跑 10 次，一次都不崩，评论挂了就显示空列表。
 * <p>
 * ⚠️ 这一关会撞上另一个坑：{@code pool.shutdown()} 现在写在 {@code join()} 后面，
 * 异常一抛就跳过去了，线程池的非守护线程会吊着 JVM 不退出（IDEA 里表现为程序跑完了但红色停止按钮还亮着）。
 * 你笔记里记的「shutdown 要放 finally」说的就是这个 —— <b>先亲眼看一次再修</b>。
 */
public class L3Fallback {


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
