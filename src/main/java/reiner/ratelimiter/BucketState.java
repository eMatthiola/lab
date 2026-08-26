package reiner.ratelimiter;

import java.util.concurrent.atomic.AtomicReference;

// 1. 定义一个不可变的状态类，把token和refreshTime打包在一起
public class BucketState {
    final long token;
    final long refreshTime;

    public BucketState(long token, long refreshTime) {
        this.token = token;
        this.refreshTime = refreshTime;
    }







}
