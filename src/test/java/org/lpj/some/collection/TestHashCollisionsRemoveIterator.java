package org.lpj.some.collection;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

public class TestHashCollisionsRemoveIterator {
    @Test
    public void testHashCollisionsRemoveIterator () {
        final Map<Object, Object> bt = new TrieMap<Object, Object> ();
        int count = 50000;
        for (int j = 0; j < count; j++) {
            bt.put (Integer.valueOf (j), Integer.valueOf (j));
        }
        
        final Collection<Object> list = new ArrayList <Object> ();
        for (final Iterator<Entry<Object, Object>> i = bt.entrySet ().iterator (); i.hasNext ();) {
            final Entry<Object, Object> e = i.next ();
            final Object key = e.getKey ();
            list.add (key);
            i.remove ();
        }

        TestHelper.assertEquals (0, bt.size ());
        TestHelper.assertTrue (bt.isEmpty ());
    }
}
