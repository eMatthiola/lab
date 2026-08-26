package reiner.ratelimiter;


import java.util.concurrent.atomic.AtomicReference;

public class Bucket {
//    //token
//    private Long token;
//
//    //refresh Lasttime
//    private Long refreshTime;

    //how many token at most in a bucket
    private Integer volume;

    //time gap, let's say 1 mins,  a user can visit once
    private Long gap;

    //visite time
    private Integer number;

    private final AtomicReference<BucketState> state;


    public Bucket(Integer volume, Long gap, Integer number) {
        this.volume = volume;
        this.gap = gap;
        this.number = number;
//        this.token = volume.longValue();
//        this.refreshTime = System.currentTimeMillis();
        this.state = new AtomicReference<>(new BucketState(volume.longValue(), System.currentTimeMillis()));
    }

//    //尝试获取一个令牌，返回是否成功
//    public synchronized boolean tryAcquire () {
//        //1.add token
//        Long addNumber = System.currentTimeMillis() - refreshTime;
//        if (addNumber > gap) {
//            token = Math.min(volume, token + addNumber / gap);
//            refreshTime = System.currentTimeMillis();
//            System.out.println("refresh"+ token);
//        }
//
//        //2 pass or not
//        if (token >= number) {
//            System.out.println("before"+ token);
//            token -= number;
//            System.out.println("after"+ token);
//            return true;
//        }
//
//        return false;
//
//    }

    public boolean tryAcquire () {
        while (true) {
            BucketState oldState  = state.get();
            // 1. 补令牌：基于 oldState.token 和 oldState.refreshTime 计算
            long addNumber = System.currentTimeMillis() - oldState.refreshTime;
            long newToken = oldState.token;
            long newRefreshTime = oldState.refreshTime;

            if (addNumber > gap) {
                newToken = Math.min(volume, oldState.token + addNumber / gap);
                newRefreshTime = System.currentTimeMillis();
                System.out.println("refresh"+ newToken);
            }

            // 2. 判断能否通过，基于newToken计算
            boolean canPass;
            if (newToken >= number) {
                System.out.println("before"+ newToken);
                newToken -= number;
                canPass = true;
                System.out.println("after"+ newToken);
            }else {
                canPass = false;
            }

            // 3. 打包成新状态，尝试原子替换
            BucketState newState = new BucketState(newToken, newRefreshTime);

            if(state.compareAndSet(oldState, newState)) {
                return canPass;
            }
        }

    }




}
