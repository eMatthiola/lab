package reiner.completablefuture;

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
 * 已有的数据（L1 实测，池大小对耗时的影响）：
 * <pre>
 *   池 5 → 421ms
 *   池 2 → 611ms / 634ms
 * </pre>
 * 多出来的时间<b>不是均摊的</b>：{@code queryRecommend}（最长，300ms）排在队尾，
 * 到 t=270 才拿到线程。并行度不足的代价是<b>推迟关键路径上最长任务的开工时间</b>，
 * 不是每个任务都慢一点。把池 1 和池 4 的数字也补进这张表。
 * <p>
 * 验收：能解释清楚每档池大小的耗时是怎么来的（画出时间线，谁在什么时候拿到线程）。
 * <p>
 * 延伸：{@code newFixedThreadPool} 用的是<b>无界</b> {@code LinkedBlockingQueue}，
 * 任务堆积时会 OOM。生产环境应改用 {@code new ThreadPoolExecutor(...)}
 * 显式指定有界队列 + 拒绝策略。
 */
public class L6ThreadPool {
}
