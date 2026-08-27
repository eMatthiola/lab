package reiner.completablefuture;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * L1 · 并行化
 * <p>
 * 目标：价格、库存、评论三个互不依赖 → {@code supplyAsync} 发起，最后统一 {@code join}。
 * <p>
 * 验收：耗时从 850ms 降到关键路径附近（等于最慢的那条线，不是几条之和），
 * 日志全部 {@code on pool-1-thread-N}。
 * <p>
 * 秘诀：所有 {@code supplyAsync} 写在前面，所有 {@code join} 写在后面。
 * <p>
 * ⚠️ 当前池大小停在 2，是 README「线程池大小的影响」那张对比表的实验状态（611ms），
 * 不是 L1 的验收配置（池 5 → 421ms）。改这个数字重跑，观察多出来的时间花在哪。
 */
public class L1Parallel {

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
