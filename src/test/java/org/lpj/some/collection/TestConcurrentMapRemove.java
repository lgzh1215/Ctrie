package org.lpj.some.collection;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.ConcurrentMap;

public class TestConcurrentMapRemove {
    private static final int COUNT = 50*1000;

    @Test
    public void testConcurrentMapRemove () {
        final ConcurrentMap<Object, Object> map = new TrieMap<>();

        for (int i = 128; i < COUNT; i++) {
            Assert.assertFalse (map.remove (i, i));
            Assert.assertTrue (null == map.put (i, i));
            Assert.assertFalse (map.remove (i, "lol"));
            Assert.assertTrue (map.containsKey (i));
            Assert.assertTrue (map.remove (i, i));
            Assert.assertFalse (map.containsKey (i));
            Assert.assertTrue (null == map.put (i, i));
        }
    }
}
