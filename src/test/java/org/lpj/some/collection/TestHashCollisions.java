package org.lpj.some.collection;


import org.junit.Assert;
import org.junit.Test;

public class TestHashCollisions {
    @Test
    public void testHashCollisions () {
        final TrieMap<Object, Object> bt = new TrieMap<Object, Object> ();

        insertStrings (bt);
        insertChars (bt);
        insertInts (bt);
        insertBytes (bt);
        
        removeStrings (bt);
        removeChars (bt);
        removeInts (bt);
        removeBytes (bt);

        insertStrings (bt);
        insertInts (bt);
        insertBytes (bt);
        insertChars (bt);

        removeBytes (bt);
        removeStrings (bt);
        removeChars (bt);
        removeInts (bt);

        insertStrings (bt);
        insertInts (bt);
        insertBytes (bt);
        insertChars (bt);

        removeStrings (bt);
        removeChars (bt);
        removeInts (bt);
        removeBytes (bt);

        insertStrings (bt);
        insertInts (bt);
        insertBytes (bt);
        insertChars (bt);

        removeChars (bt);
        removeInts (bt);
        removeBytes (bt);
        removeStrings (bt);

        insertStrings (bt);
        insertInts (bt);
        insertBytes (bt);
        insertChars (bt);

        removeInts (bt);
        removeBytes (bt);
        removeStrings (bt);
        removeChars (bt);

        System.out.println (bt);
    }

    private static void insertChars (final TrieMap<Object, Object> bt) {
        Assert.assertEquals (null, bt.put ('a', 'a'));
        Assert.assertEquals (null, bt.put ('b', 'b'));
        Assert.assertEquals (null, bt.put ('c', 'c'));
        Assert.assertEquals (null, bt.put ('d', 'd'));
        Assert.assertEquals (null, bt.put ('e', 'e'));

        Assert.assertEquals ('a', bt.put ('a', 'a'));
        Assert.assertEquals ('b', bt.put ('b', 'b'));
        Assert.assertEquals ('c', bt.put ('c', 'c'));
        Assert.assertEquals ('d', bt.put ('d', 'd'));
        Assert.assertEquals ('e', bt.put ('e', 'e'));
    }

    private static void insertStrings (final TrieMap<Object, Object> bt) {
        Assert.assertEquals (null, bt.put ("a", "a"));
        Assert.assertEquals (null, bt.put ("b", "b"));
        Assert.assertEquals (null, bt.put ("c", "c"));
        Assert.assertEquals (null, bt.put ("d", "d"));
        Assert.assertEquals (null, bt.put ("e", "e"));

        Assert.assertEquals ("a", bt.put ("a", "a"));
        Assert.assertEquals ("b", bt.put ("b", "b"));
        Assert.assertEquals ("c", bt.put ("c", "c"));
        Assert.assertEquals ("d", bt.put ("d", "d"));
        Assert.assertEquals ("e", bt.put ("e", "e"));
    }

    private static void insertBytes (final TrieMap<Object, Object> bt) {
        for (byte i = 0; i < 128 && i >= 0; i++) {
            final Byte bigB = Byte.valueOf (i);
            Assert.assertEquals (null, bt.put (bigB, bigB));
            Assert.assertEquals (bigB, bt.put (bigB, bigB));
        }
    }

    private static void insertInts (final TrieMap<Object, Object> bt) {
        for (int i = 0; i < 128; i++) {
            final Integer bigI = Integer.valueOf (i);
            Assert.assertEquals (null, bt.put (bigI, bigI));
            Assert.assertEquals (bigI, bt.put (bigI, bigI));
        }
    }

    private static void removeChars (final TrieMap<Object, Object> bt) {
        Assert.assertTrue (null != bt.get('a'));
        Assert.assertTrue (null != bt.get('b'));
        Assert.assertTrue (null != bt.get('c'));
        Assert.assertTrue (null != bt.get('d'));
        Assert.assertTrue (null != bt.get('e'));

        Assert.assertTrue (null != bt.remove ('a'));
        Assert.assertTrue (null != bt.remove ('b'));
        Assert.assertTrue (null != bt.remove ('c'));
        Assert.assertTrue (null != bt.remove ('d'));
        Assert.assertTrue (null != bt.remove ('e'));

        Assert.assertFalse (null != bt.remove ('a'));
        Assert.assertFalse (null != bt.remove ('b'));
        Assert.assertFalse (null != bt.remove ('c'));
        Assert.assertFalse (null != bt.remove ('d'));
        Assert.assertFalse (null != bt.remove ('e'));

        Assert.assertTrue (null == bt.get('a'));
        Assert.assertTrue (null == bt.get('b'));
        Assert.assertTrue (null == bt.get('c'));
        Assert.assertTrue (null == bt.get('d'));
        Assert.assertTrue (null == bt.get('e'));
    }

    private static void removeStrings (final TrieMap<Object, Object> bt) {
        Assert.assertTrue (null != bt.get("a"));
        Assert.assertTrue (null != bt.get("b"));
        Assert.assertTrue (null != bt.get("c"));
        Assert.assertTrue (null != bt.get("d"));
        Assert.assertTrue (null != bt.get("e"));

        Assert.assertTrue (null != bt.remove ("a"));
        Assert.assertTrue (null != bt.remove ("b"));
        Assert.assertTrue (null != bt.remove ("c"));
        Assert.assertTrue (null != bt.remove ("d"));
        Assert.assertTrue (null != bt.remove ("e"));

        Assert.assertFalse (null != bt.remove ("a"));
        Assert.assertFalse (null != bt.remove ("b"));
        Assert.assertFalse (null != bt.remove ("c"));
        Assert.assertFalse (null != bt.remove ("d"));
        Assert.assertFalse (null != bt.remove ("e"));

        Assert.assertTrue (null == bt.get("a"));
        Assert.assertTrue (null == bt.get("b"));
        Assert.assertTrue (null == bt.get("c"));
        Assert.assertTrue (null == bt.get("d"));
        Assert.assertTrue (null == bt.get("e"));
    }

    private static void removeInts (final TrieMap<Object, Object> bt) {
        for (int i = 0; i < 128; i++) {
            final Integer bigI = Integer.valueOf (i);
            Assert.assertTrue (null != bt.get(bigI));
            Assert.assertTrue (null != bt.remove (bigI));
            Assert.assertFalse (null != bt.remove (bigI));
            Assert.assertTrue (null == bt.get(bigI));
        }
    }

    private static void removeBytes (final TrieMap<Object, Object> bt) {
        for (byte i = 0; i < 128 && i >= 0; i++) {
            final Byte bigB = Byte.valueOf (i);
            Assert.assertTrue (null != bt.get(bigB));
            Assert.assertTrue (null != bt.remove (bigB));
            Assert.assertFalse (null != bt.remove (bigB));
            Assert.assertTrue (null == bt.get(bigB));
        }
    }
}
