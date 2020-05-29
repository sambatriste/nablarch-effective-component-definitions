package com.github.sambatriste.necd;

import com.github.sambatriste.necd.EffectiveComponentDefinition.ObjectGraphBuilder;
import org.hamcrest.CoreMatchers;
import org.junit.Test;

import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@SuppressWarnings("unchecked")
public class EffectiveComponentDefinitionTest {

    private ObjectGraphBuilder sut ;
    @Test

    public void testPlainObject() {
        sut = new ObjectGraphBuilder("com/github/sambatriste/necd/plainObject.xml");
        Map<String, Object> m = sut.build();
        Map<String, Object> plainObject = (Map<String, Object>) m.get("plainObject");
        assertThat((String) plainObject.get("name"), is("aaa"));
    }

    @Test
    public void testNestedObject() {
        sut = new ObjectGraphBuilder("com/github/sambatriste/necd/nestedObject.xml");
        Map<String, Object> m = sut.build();
        Map<String, Object> nestedObject = (Map<String, Object>) m.get("nestedObject");
        assertThat((String) nestedObject.get("value"), is("100"));
        Map<String, Object> plainObject = (Map<String, Object>) nestedObject.get("plainObject");
        assertThat((String) plainObject.get("name"), is("aaa"));
    }

    @Test
    public void testOverride() {
        sut = new ObjectGraphBuilder("com/github/sambatriste/necd/override.xml");
        Map<String, Object> m = sut.build();
        assertThat(m.size(), is(1));
        Map<String, Object> plainObject = (Map<String, Object>) m.get("plainObject");
        assertThat((String) plainObject.get("name"), is("bbb"));
    }

    @Test
    public void testConfig() {
        sut = new ObjectGraphBuilder("com/github/sambatriste/necd/config.xml");
        Map<String, Object> m = sut.build();
        Map<String, Object> plainObject = (Map<String, Object>) m.get("plainObject");
        assertThat((String) plainObject.get("name"), is("ccc"));
    }
}