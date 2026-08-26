package reiner.ratelimiter;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class BucketTest {


    @Test
    void test01() {

        Bucket bucket = new Bucket(3,60000L,1);

        assertTrue(bucket.tryAcquire());
        assertTrue(bucket.tryAcquire());
        assertTrue(bucket.tryAcquire());
        assertFalse(bucket.tryAcquire());
    }

    @Test
    void test02() throws InterruptedException {

        Bucket bucket = new Bucket(3,500L,1);

        //consume all token
        assertTrue(bucket.tryAcquire());
        assertTrue(bucket.tryAcquire());
        assertTrue(bucket.tryAcquire());

        Thread.sleep(600);   // 让当前线程暂停600毫秒
        assertTrue(bucket.tryAcquire());
    }

    @Test
    void test03() throws InterruptedException {

        Bucket bucket = new Bucket(10,500L,1);
        AtomicInteger successCounter = new AtomicInteger(0);
        int thread = 50;

        // 需要：起50个线程，每个线程调用一次tryAcquire()，
        //       如果返回true，就把successCount加1
        // 需要：主线程要等所有50个线程都执行完，才能去检查最终结果
        //       （不然可能主线程先跑到断言那一行，线程还没跑完）
        CountDownLatch latch = new CountDownLatch(thread);

        for(int i=0;i<thread;i++) {
            Thread t = new Thread(() -> {
                if(bucket.tryAcquire()) {
                    successCounter.incrementAndGet();
                }
                latch.countDown();
            });
            t.start();

        }
        latch.await();

        // 断言：successCount最终应该等于10
        assertEquals(10, successCounter.get());
    }
}
