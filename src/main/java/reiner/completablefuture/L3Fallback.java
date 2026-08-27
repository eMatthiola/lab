package reiner.completablefuture;

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
}
