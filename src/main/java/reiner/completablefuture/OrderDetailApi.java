package reiner.completablefuture;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 商品详情页依赖的 5 个「远程调用」，全部用 {@code Thread.sleep} 模拟耗时。
 * <p>
 * 这里只放脚手架，不放编排逻辑 —— 每一关的编排各自写在 {@code L0Serial}、{@code L1Parallel} …… 里，
 * 这样任何一关都能随时单独重跑，用来跟别的关卡对照耗时。
 * <p>
 * 耗时和依赖关系见同目录 README.md。
 */
public class OrderDetailApi {

    /** L0/L1/L2 关着跑，好量准耗时；练 L3(降级) / L4(超时) 时改成 true */
//    static boolean CHAOS = true;
    static boolean CHAOS = false;

    // ==================== 模拟的 5 个远程调用 ====================

    public Product queryProduct(int id) {
        sleep(80);
        log("queryProduct");
        return new Product(id, "商品-" + id, 100 + id);
    }

    public int queryPrice(int id) {
        sleep(120);
        log("queryPrice");
        return 9900;
    }

    public int queryStock(int id) {
        // 20% 概率卡 1500ms —— 留给 L4 练 completeOnTimeout
        sleep(CHAOS && ThreadLocalRandom.current().nextInt(10) < 2 ? 1500 : 200);
        log("queryStock");
        return 42;
    }

    public List<String> queryComments(int id) {
        sleep(150);
        log("queryComments");
        // 10% 概率抛异常 —— 留给 L3 练 exceptionally
        if (CHAOS && ThreadLocalRandom.current().nextInt(10) == 0) {
            throw new RuntimeException("评论服务挂了");
        }
        return Arrays.asList("好评", "还行");
    }

    /** 注意：入参是 categoryId，必须先拿到 queryProduct 的结果 —— L2 的 thenCompose 就练这个 */
    public List<String> queryRecommend(int categoryId) {
        sleep(300);
        log("queryRecommend");
        return Arrays.asList("推荐A", "推荐B");
    }


    public CompletableFuture<List<String>> queryRecommendAsync (int categoryId, ExecutorService pool) {

        return  CompletableFuture.supplyAsync(() -> queryRecommend(categoryId), pool);
    }



    // ==================== 工具 ====================

    /**
     * 把 InterruptedException 这个受检异常挡在外面。
     * 很重要：Supplier.get() 不允许抛受检异常，
     * 否则 L1 写 supplyAsync(() -> queryProduct(id)) 时会编译不过。
     */
    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // 别把中断标志吞了
            throw new IllegalStateException(e);
        }
    }

    private static void log(String name) {
        System.out.println(name + " on " + Thread.currentThread().getName());
    }
}
