package com.hmdp.utils;

public interface Ilock {

    boolean tryLock(long timeout);


    void unlock();

}
