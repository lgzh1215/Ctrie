package org.lpj.some.collection;

import org.junit.Assert;
import org.junit.Test;

import java.util.Map;

public class TestCNodeInsertionIncorrectOrder {

    @Test
    public void testCNodeInsertionIncorrectOrder () {
        final Map<Object, Object> map = new TrieMap<>();
        final Integer z3884 = Integer.valueOf (3884);
        final Integer z4266 = Integer.valueOf (4266);
        map.put (z3884, z3884);
        Assert.assertTrue (null != map.get (z3884));

        map.put (z4266, z4266);
        Assert.assertTrue (null != map.get (z3884));
        Assert.assertTrue (null != map.get (z4266));
    }
}
