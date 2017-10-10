package org.lpj.some.collection;

import org.junit.Assert;
import org.junit.Test;

import java.util.*;
import java.util.Map.Entry;

public class TestMapIterator {
    @Test
    public void testMapIterator () {
        for (int i = 0; i < 60 * 1000; i+= 400 + new Random ().nextInt (400)) {
            System.out.println (i);
            final Map<Integer, Integer> bt = new TrieMap <Integer, Integer> ();
            for (int j = 0; j < i; j++) {
                Assert.assertEquals (null, bt.put (Integer.valueOf (j), Integer.valueOf (j)));
            }
            int count = 0;
            final Set<Integer> set = new HashSet<Integer> ();
            for (final Entry<Integer, Integer> e : bt.entrySet ()) {
                set.add (e.getKey ());
                count++;
            }
            for (final Integer j : set) {
                Assert.assertTrue (bt.containsKey (j));
            }
            for (final Integer j : bt.keySet ()) {
                Assert.assertTrue (set.contains (j));
            }

            Assert.assertEquals (i, count);
            Assert.assertEquals (i, bt.size ());

            for (final Entry<Integer, Integer> e : bt.entrySet()) {
                Assert.assertTrue (e.getValue() == bt.get(e.getKey()));
                e.setValue(e.getValue() + 1);
                Assert.assertTrue (e.getValue() == e.getKey() + 1);
                Assert.assertTrue (e.getValue() == bt.get(e.getKey()));
                e.setValue(e.getValue() - 1);
            }

            for (final Iterator<Integer> iter = bt.keySet ().iterator (); iter.hasNext ();) {
                final Integer k = iter.next ();
                Assert.assertTrue (bt.containsKey (k));
                iter.remove ();
                Assert.assertFalse (bt.containsKey (k));
            }

            Assert.assertEquals (0, bt.size ());
            Assert.assertTrue (bt.isEmpty ());
        }
    }
}
