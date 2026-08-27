package reiner.completablefuture;

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
}
