package org.lpj.some.collection;

import org.junit.Test;


public class TestDelete {
    @Test
    public void testDelete() {
        final TrieMap<Object, Object> bt = new TrieMap<Object, Object>();

        for (int i = 0; i < 10000; i++) {
            TestHelper.assertEquals(null, bt.put(i, i));
            final Object lookup = bt.get(i);
            TestHelper.assertEquals(i, lookup);
        }

        checkAddInsert(bt, 536);
        checkAddInsert(bt, 4341);
        checkAddInsert(bt, 8437);

        for (int i = 0; i < 10000; i++) {
            boolean removed = null != bt.remove(i);
            TestHelper.assertEquals(Boolean.TRUE, removed);
            final Object lookup = bt.get(i);
            TestHelper.assertEquals(null, lookup);
        }

        bt.toString();
    }

    private static void checkAddInsert(final TrieMap<Object, Object> bt, int k) {
        final Integer v = k;
        bt.remove(v);
        Object foundV = bt.get(v);
        TestHelper.assertEquals(null, foundV);
        TestHelper.assertEquals(null, bt.put(v, v));
        foundV = bt.get(v);
        TestHelper.assertEquals(v, foundV);

        TestHelper.assertEquals(v, bt.put(v, -1));
        TestHelper.assertEquals(-1, bt.put(v, v));
    }
}
