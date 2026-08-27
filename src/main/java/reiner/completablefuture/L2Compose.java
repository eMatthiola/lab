package reiner.completablefuture;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * L2 · 串行依赖：thenCompose vs thenApply
 * <p>
 * 推荐需要 {@code product.categoryId}，必须等 {@code queryProduct} 出结果。
 * <p>
 * 三拍，中间两次停下来看编译器：
 * <ol>
 *   <li>给 {@code OrderDetailApi} 加一个返回 {@code CompletableFuture<List<String>>} 的
 *       {@code queryRecommendAsync}，其余照抄 L1 → 看编译错误怎么说</li>
 *   <li>顺着编译器把左边类型改成两层 {@code CompletableFuture<CompletableFuture<List<String>>>}，
 *       {@code join().join()} → 它能跑通，但类型会往上游传染</li>
 *   <li>换 {@code thenCompose} 塌平</li>
 * </ol>
 * 验收：类型回到一层，耗时与 L1 持平（thenCompose 只消掉类型层数，不改调度行为）。
 * <p>
 * TODO: main 待写。
 */
public class L2Compose {

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
                productF.thenCompose(product -> api.queryRecommendAsync(product.categoryId, pool));

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
