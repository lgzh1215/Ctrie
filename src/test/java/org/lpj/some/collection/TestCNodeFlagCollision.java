package org.lpj.some.collection;

import org.junit.Assert;
import org.junit.Test;

import java.util.Map;

public class TestCNodeFlagCollision {
    @Test
    public void testCNodeFlagCollision () {
        final Map<Object, Object> map = new TrieMap<>();
        final Integer z15169 = Integer.valueOf (15169);
        final Integer z28336 = Integer.valueOf (28336);

        Assert.assertTrue (null == map.get (z15169));
        Assert.assertTrue (null == map.get (z28336));

        map.put (z15169, z15169);
        Assert.assertTrue (null != map.get (z15169));
        Assert.assertTrue (null == map.get (z28336));

        map.put (z28336, z28336);
        Assert.assertTrue (null != map.get (z15169));
        Assert.assertTrue (null != map.get (z28336));

        map.remove (z15169);

        Assert.assertTrue (null == map.get (z15169));
        Assert.assertTrue (null != map.get (z28336));

        map.remove (z28336);

        Assert.assertTrue (null == map.get (z15169));
        Assert.assertTrue (null == map.get (z28336));
    }
}
