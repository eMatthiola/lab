# lab

Java 练习场。每个包一个主题，配一份 README 记录练习目标、验收标准和踩过的坑。

## 环境

- JDK 17（`build.gradle` 用 toolchain 指定；L4 的 `completeOnTimeout` 需要 Java 9+，练到那关时从 1.8 升上来的）
- Gradle Wrapper（不需要本地装 Gradle）
- Spring Boot 2.6.13

## 目录

| 包 | 主题 | 说明 |
|---|---|---|
| [`reiner.completablefuture`](src/main/java/reiner/completablefuture) | CompletableFuture 异步编排 | 商品详情页聚合场景，分 L0~L7 关卡（L0~L6 已完成） |
| `reiner.ratelimiter` | 限流器 | 令牌桶实现 |

## 运行

```bash
./gradlew compileJava     # 编译
./gradlew test            # 跑测试
```

单个练习直接运行对应类的 `main` 方法。
