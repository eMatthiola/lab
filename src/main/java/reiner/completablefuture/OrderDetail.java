package reiner.completablefuture;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

//分关卡练
//
//L0 · 基线
//先写纯串行版本，记录总耗时。应该是 80+120+200+150+300 ≈ 850ms。这个数字是后面所有优化的参照。
//
//L1 · 并行化
//价格、库存、评论三个互不依赖 → supplyAsync 发起，thenCombine 汇总。
//        验收：耗时从 850mms 降到 ~200ms（等于最慢的那个，不是三者之和）。
//
//L2 · 串行依赖
//推荐需要 product.categoryId，必须等 queryProduct 出结果。
//故意先用 thenApply 写一遍，看到 CompletableFuture<CompletableFuture<List<Item>>> 这个套娃类型编译不过或没法用，再换成 thenCompose。这个"踩一脚"比直接看对的写法记得牢。
//
//L3 · 异常降级
//评论挂了不该让整个页面 500，降级成空列表 → exceptionally。
//然后对比：把它换成 handle 和 whenComplete，观察三者行为差异（尤其 whenComplete 改不了结果这点）。
//
//L4 · 超时兜底
//库存偶尔卡 1.5s → completeOnTimeout(有货, 300, MILLISECONDS)。
//        ✅ 验收：跑 10 次，总耗时始终稳定，不不会某次飙到 1.5s。
//
//L5 · 批量聚合
//把 5 个结果用 allOf 统一等待，再逐个 join 取值。
//注意体会 allOf 返回的是 CompletableFuture<Void>——不带结果，这是新手最常卡住的地方。
//
//L6 · 线程池（最重要的一关）
//        1. 全程用默认池跑，看线程名是 ForkJoinPool.commonPool-worker-N
//  2. 换成 Executors.newFixedThreadPool(4) 显式传入，看线程名变成 pool-1-thread-N
//  3. 故意实验：把线程池设成 newFixedThreadPool(1)，再发起 5 个并行任务，观察它怎么退化回串行——这就是生产上"用 commonPool 跑阻塞 IO 拖垮全站"的微缩版
//  4. 对比 thenApply 和 thenApplyAsync 打出来的线程名分别是什么
//
//L7 · 反面教材
//  在链条中间插一个 .join()，测耗时，看它怎么把并行打回串行。亲手写一次这个 bug，以后 review 别人代码时一眼就能看出来。
public class OrderDetail {

    /** L0/L1/L2 先关着跑，好量准耗时；练 L3(降级) / L4(超时) 时改成 true */
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

    public static class Product {
        public final int id;
        public final String name;
        public final int categoryId;

        public Product(int id, String name, int categoryId) {
            this.id = id;
            this.name = name;
            this.categoryId = categoryId;
        }

        @Override
        public String toString() {
            return "Product{id=" + id + ", name=" + name + ", categoryId=" + categoryId + "}";
        }
    }

    // ==================== L0：串行基线 ====================
//
//    public static void main(String[] args) {
//        OrderDetail od = new OrderDetail();
//        int id = 1;
//
//        long start = System.currentTimeMillis();
//
//        Product product        = od.queryProduct(id);
//        int price              = od.queryPrice(id);
//        int stock              = od.queryStock(id);
//        List<String> comments  = od.queryComments(id);
//        List<String> recommend = od.queryRecommend(product.categoryId);
//
//        long cost = System.currentTimeMillis() - start;
//        System.out.println("---- 串行总耗时: " + cost + "ms ----");
//        System.out.println(product + " price=" + price + " stock=" + stock
//                + " comments=" + comments + " recommend=" + recommend);
//    }

    // ==================== L1 · 并行化 ====================

    public static void main(String[] args) {
        OrderDetail od = new OrderDetail();
        int id = 1;

        long start = System.currentTimeMillis();

        ExecutorService pool = Executors.newFixedThreadPool(2);

        // ---- 4 条互不依赖的线，supplyAsync 立刻返回，四个任务同时开跑 ----
        CompletableFuture<Product> productF =
                CompletableFuture.supplyAsync(() -> od.queryProduct(id), pool);
        CompletableFuture<Integer> priceF =
                CompletableFuture.supplyAsync(() -> od.queryPrice(id), pool);
        CompletableFuture<Integer> stockF =
                CompletableFuture.supplyAsync(() -> od.queryStock(id), pool);
        CompletableFuture<List<String>> commentsF =
                CompletableFuture.supplyAsync(() -> od.queryComments(id), pool);

        //// ---- 第 1 条线的延续：必须先拿到 Product，才知道 categoryId ----
        //          // p 就是 productF 完成后的结果，类型是 Product
        CompletableFuture<List<String>>  recommendF = productF.thenApplyAsync(product -> od.queryRecommend(product.categoryId), pool);


        //// ---- 到这里为止一个任务都没等，main 只花了几毫秒 ----
        //          // join() 才开始阻塞等待，此时 4 条线早已在并行跑了
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
