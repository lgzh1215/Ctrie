package org.lpj.some.collection;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.ConcurrentMap;

public class TestConcurrentMapReplace {
    private static final int COUNT = 50*1000;

    @Test
    public void testConcurrentMapReplace () {
        final ConcurrentMap<Object, Object> map = new TrieMap<Object, Object> ();
        
        for (int i = 0; i < COUNT; i++) {
            Assert.assertTrue (null == map.replace (i, "lol"));
            Assert.assertFalse (map.replace (i, i, "lol2"));
            Assert.assertTrue (null == map.put (i, i));
            Assert.assertTrue (Integer.valueOf (i).equals (map.replace (i, "lol")));
            Assert.assertFalse (map.replace (i, i, "lol2"));
            Assert.assertTrue (map.replace (i, "lol", i));
        }
    }
}
