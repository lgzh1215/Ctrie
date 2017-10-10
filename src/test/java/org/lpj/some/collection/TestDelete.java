package org.lpj.some.collection;

import org.junit.Assert;
import org.junit.Test;


public class TestDelete {
    @Test
    public void testDelete() {
        final TrieMap<Object, Object> bt = new TrieMap<Object, Object>();

        for (int i = 0; i < 10000; i++) {
            Assert.assertEquals (null, bt.put(i, i));
            final Object lookup = bt.get(i);
            Assert.assertEquals (i, lookup);
        }

        checkAddInsert(bt, 536);
        checkAddInsert(bt, 4341);
        checkAddInsert(bt, 8437);

        for (int i = 0; i < 10000; i++) {
            boolean removed = null != bt.remove(i);
            Assert.assertEquals (Boolean.TRUE, removed);
            final Object lookup = bt.get(i);
            Assert.assertEquals (null, lookup);
        }

        bt.toString();
    }

    private static void checkAddInsert(final TrieMap<Object, Object> bt, int k) {
        final Integer v = k;
        bt.remove(v);
        Object foundV = bt.get(v);
        Assert.assertEquals (null, foundV);
        Assert.assertEquals (null, bt.put(v, v));
        foundV = bt.get(v);
        Assert.assertEquals (v, foundV);

        Assert.assertEquals (v, bt.put(v, -1));
        Assert.assertEquals (-1, bt.put(v, v));
    }
}
