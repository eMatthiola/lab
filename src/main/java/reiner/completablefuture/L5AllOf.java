package reiner.completablefuture;

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
}
