package reiner.completablefuture;

/**
 * L4 · 超时兜底
 * <p>
 * 场景：库存服务 20% 概率卡 1500ms。详情页不能因为库存慢就整体拖到 1.5s，
 * 超过 300ms 就别等了，先按「有货」渲染。
 * <p>
 * 准备：{@code OrderDetailApi.CHAOS = true;}
 * <p>
 * ⚠️ 本关有 JDK 版本问题：{@code completeOnTimeout} / {@code orTimeout} 是 <b>Java 9+</b>，
 * 本项目 {@code sourceCompatibility = '1.8'}，直接写编译不过。两条路：
 * <p>
 * <b>路线 A（推荐先做）：在 Java 8 上手写一遍超时</b>
 * <pre>
 *   ScheduledExecutorService timer = Executors.newScheduledThreadPool(1);
 *   timer.schedule(() -&gt; stockF.complete(0), 300, TimeUnit.MILLISECONDS);
 * </pre>
 * 关键点：{@code CompletableFuture} 可以<b>从外部被完成</b>。谁先到算谁的 ——
 * 库存 200ms 正常返回就用真实值，卡住了就被定时器用兜底值抢先填上。
 * {@code completeOnTimeout} 内部干的就是这件事，自己写一遍就不神秘了。
 * 记得最后 {@code timer.shutdown()}。
 * <p>
 * <b>路线 B：升到 JDK 17，用现成的一行</b>
 * <pre>
 *   stockF.completeOnTimeout(0, 300, TimeUnit.MILLISECONDS);
 * </pre>
 * 需要先改 build.gradle（机器上 D:\Java 17 已装，不用另外下载）：
 * <pre>
 *   // 删掉 sourceCompatibility = '1.8'，换成：
 *   java {
 *       toolchain { languageVersion = JavaLanguageVersion.of(17) }
 *   }
 * </pre>
 * <p>
 * 建议 A 做完再做 B，然后对比——你会发现 B 就是 A 的封装。
 * <p>
 * 验收：CHAOS 开着连跑 10 次，总耗时始终稳定，不会某次飙到 1.5s。
 */
public class L4Timeout {
}
