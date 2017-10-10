package org.lpj.some.collection;

import org.junit.Assert;
import org.junit.Test;

public class TestInsert {
    @Test
    public void testInsert () {
        final TrieMap<Object, Object> bt = new TrieMap<Object, Object> ();
        Assert.assertEquals (null, bt.put ("a", "a"));
        Assert.assertEquals (null, bt.put ("b", "b"));
        Assert.assertEquals (null, bt.put ("c", "b"));
        Assert.assertEquals (null, bt.put ("d", "b"));
        Assert.assertEquals (null, bt.put ("e", "b"));

        for (int i = 0; i < 10000; i++) {
            Assert.assertEquals (null, bt.put (Integer.valueOf (i), Integer.valueOf (i)));
            final Object lookup = bt.get(Integer.valueOf (i));
            Assert.assertEquals (Integer.valueOf (i), lookup);
        }

        bt.toString ();
    }
}
