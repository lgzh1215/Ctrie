package org.lpj.some.collection;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.ConcurrentMap;

public class TestConcurrentMapPutIfAbsent {
    private static final int COUNT = 50*1000;

    @Test
    public void testConcurrentMapPutIfAbsent () {
        final ConcurrentMap<Object, Object> map = new TrieMap<>();

        for (int i = 0; i < COUNT; i++) {
            Assert.assertTrue (null == map.putIfAbsent (i, i));
            Assert.assertTrue (Integer.valueOf (i).equals (map.putIfAbsent (i, i)));
        }
    }
}
