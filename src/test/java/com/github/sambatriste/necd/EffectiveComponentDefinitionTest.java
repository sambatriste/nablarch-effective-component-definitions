package com.github.sambatriste.necd;

import com.github.sambatriste.necd.EffectiveComponentDefinition.ObjectGraphBuilder;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@SuppressWarnings("unchecked")
public class EffectiveComponentDefinitionTest {

    private ObjectGraphBuilder sut;

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
        assertThat((Integer) nestedObject.get("value"), is(100));
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

    @Test
    public void testList() {
        sut = new ObjectGraphBuilder("com/github/sambatriste/necd/list.xml");
        Map<String, Object> m = sut.build();

        List<Map<String, Object>> compoList = (List<Map<String, Object>>) m.get("compoList");
        assertThat((String) compoList.get(0).get("name"), is("aaa"));
        assertThat((String) compoList.get(1).get("name"), is("bbb"));
        assertThat(compoList.size(), is(2));

        List<String> valueList = (List<String>) m.get("valueList");
        assertThat(valueList.get(0), is("aaa"));
        assertThat(valueList.get(1), is("bbb"));

    }

    @Test
    public void testMap() {
        sut = new ObjectGraphBuilder("com/github/sambatriste/necd/map.xml");
        Map<String, Object> m = sut.build();
        System.out.println("m = " + m);
        Map<String, Object> map = (Map<String, Object>) m.get("literalMap");
        assertThat((String) map.get("foo"), is("bar"));
        assertThat((String) map.get("hoge"), is("fuga"));

        Map<String, Object> componentMap = (Map<String, Object>) m.get("componentMap");
        Map<String, Object> plainObject = (Map<String, Object>) componentMap.get("buz");
        assertThat((String) plainObject.get("name"), is("aaa"));

    }

}