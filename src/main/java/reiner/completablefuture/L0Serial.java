package reiner.completablefuture;

import java.util.List;

/**
 * L0 · 串行基线
 * <p>
 * 目标：先写纯串行版本，记录总耗时。应该是 80+120+200+150+300 ≈ 850ms。
 * 这个数字是后面所有优化的参照，所以这一关必须能随时重跑。
 * <p>
 * 验收：~880ms，5 行日志全部 {@code on main}。
 */
public class L0Serial {

    public static void main(String[] args) {
        OrderDetailApi api = new OrderDetailApi();
        int id = 1;

        long start = System.currentTimeMillis();

        Product product        = api.queryProduct(id);
        int price              = api.queryPrice(id);
        int stock              = api.queryStock(id);
        List<String> comments  = api.queryComments(id);
        List<String> recommend = api.queryRecommend(product.categoryId);

        long cost = System.currentTimeMillis() - start;
        System.out.println("---- 串行总耗时: " + cost + "ms ----");
        System.out.println(product + " price=" + price + " stock=" + stock
                + " comments=" + comments + " recommend=" + recommend);
        System.out.println("main 在 " + Thread.currentThread().getName());
    }
}
