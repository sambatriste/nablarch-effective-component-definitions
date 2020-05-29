package com.github.sambatriste.necd;

import com.github.sambatriste.necd.EffectiveComponentDefinition.ObjectGraphBuilder;
import org.junit.Test;

import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;


public class EffectiveComponentDefinitionTest {

    @Test
    public void testPlainObject() {
        ObjectGraphBuilder sut = new ObjectGraphBuilder("com/github/sambatriste/necd/plainObject.xml");
        Map<String, Object> m = sut.build();
        Map<String, Object> plainObject = (Map) m.get("plainObject");
        assertThat((String) plainObject.get("name"), is("aaa"));
    }

}