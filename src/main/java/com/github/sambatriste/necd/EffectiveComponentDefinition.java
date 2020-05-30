package com.github.sambatriste.necd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SequenceWriter;
import nablarch.core.repository.di.ComponentCreator;
import nablarch.core.repository.di.ComponentDefinition;
import nablarch.core.repository.di.ComponentReference;
import nablarch.core.repository.di.DiContainer;
import nablarch.core.repository.di.InjectionType;
import nablarch.core.repository.di.config.ListComponentCreator;
import nablarch.core.repository.di.config.LiteralComponentCreator;
import nablarch.core.repository.di.config.MapComponentCreator;
import nablarch.core.repository.di.config.MapEntryDefinition;
import nablarch.core.repository.di.config.xml.XmlComponentDefinitionLoader;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EffectiveComponentDefinition {

    private static class ProgramArguments {
        final String componentFileFqcn;
        final File outputFile;

        ProgramArguments(String... args) {
            if (args.length == 0) {
                throw new IllegalArgumentException(Arrays.toString(args));
            }
            this.componentFileFqcn = args[0];
            String outputFile = args.length == 2 ? args[1] : "effective-components.json";
            this.outputFile = new File(outputFile);
        }
    }

    private static EffectiveComponentDefinition effectiveDef = new EffectiveComponentDefinition();

    public static void main(String... args) {
        ProgramArguments arguments = new ProgramArguments(args);
        effectiveDef.show(arguments.componentFileFqcn, arguments.outputFile);
    }

    public void show(String componentFileFqcn, File fileToWrite) {
        Writer writer = null;
        try {
            writer = open(fileToWrite);
            show(componentFileFqcn, writer);
        } finally {
            closeQuietly(writer);
        }
    }

    public void show(String componentFileFqcn, Writer writer) {
        ObjectGraphBuilder builder = new ObjectGraphBuilder(componentFileFqcn);
        Map<String, Object> graph = builder.build();
        doWrite(graph, writer);
    }

    private void doWrite(Map<String, Object> graph, Writer writer) {
        ObjectMapper objectMapper = new ObjectMapper();
        //objectMapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

        SequenceWriter seqWriter = null;
        try {
            seqWriter = objectMapper.writerWithDefaultPrettyPrinter().writeValues(writer);
            seqWriter.write(graph);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            closeQuietly(seqWriter);
        }
    }


    static class ObjectGraphBuilder {

        private final List<ComponentDefinition> definitions;

        private final DiContainer diContainer;

        ObjectGraphBuilder(String componentFile) {
            XmlComponentDefinitionLoader loader = new XmlComponentDefinitionLoader(componentFile);
            diContainer = new DiContainer(loader);
            definitions = loader.load(diContainer);
        }

        Map<String, Object> build() {
            Map<String, Object> result = new TreeMap<String, Object>();
            for (ComponentDefinition def : definitions) {
                String name = def.getName();
                if (name != null) {
                    Object value = evaluate(def);
                    result.put(name, value);
                }
            }
            return result;
        }

        private Object evaluate(ComponentDefinition def) {
            if (def == null) {
                return null;
            }

            ComponentCreator creator = def.getCreator();
            if (creator instanceof LiteralComponentCreator) {
                return creator.createComponent(diContainer, def);
            }

            if (creator instanceof ListComponentCreator) {
                return evaluate((ListComponentCreator) creator);
            }

            if (creator instanceof MapComponentCreator) {
                return evaluate((MapComponentCreator) creator);
            }
            List<ComponentReference> refs = def.getReferences();
            if (refs.isEmpty()) {
                return def.getType();
            }

            return evaluate(refs);
        }

        private Object evaluate(MapComponentCreator creator) {
            List<MapEntryDefinition> entries = getEntriesOf(creator);
            Map<String, Object> map = new TreeMap<String, Object>();

            for (MapEntryDefinition entry : entries) {
                String key = entry.getKey();
                Object value = entry.getValue();
                switch (entry.getValueType()) {
                    case COMPONENT: {
                        ComponentDefinition def = find(entry.getValueId());
                        value = evaluate(def);
                        break;
                    }
                    case REF: {
                        ComponentDefinition def = find(entry.getValueRef());
                        value = evaluate(def );
                        break;
                    }
                    case STRING:
                        value = entry.getValue();
                }
                map.put(key, value);
            }
            return map;
        }

        @SuppressWarnings("unchecked")
        private List<MapEntryDefinition> getEntriesOf(MapComponentCreator componentCreator) {
            try {
                Field f = MapComponentCreator.class.getDeclaredField("entries");
                f.setAccessible(true);
                return  (List<MapEntryDefinition>) f.get(componentCreator);
            } catch (NoSuchFieldException e) {
                throw new RuntimeException(e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        private static Pattern LIST_VALUE_PATTERN = Pattern.compile("list objects = \\[(.+)]");

        private Object evaluate(ListComponentCreator creator) {
            String orig = creator.toString();
            Matcher valueMatcher = LIST_VALUE_PATTERN.matcher(orig);
            boolean found = valueMatcher.find();
            assert found;
            String listValue = valueMatcher.group(1);
            String[] elements = listValue.split(",");
            List<Object> result = new ArrayList<Object>();
            for (String e : elements) {
                String[] split = e.split(":");
                String type = split[0];
                String val = split[1];
                ComponentDefinition listElement = null;
                if (type.equals("id")) {
                    listElement = find(Integer.parseInt(val));
                } else if (type.equals("name")) {
                    listElement = find(val);
                }
                result.add(evaluate(listElement));
            }
            return result;
        }

        private Object evaluate(List<ComponentReference> refs) {
            Map<String, Object> props = new TreeMap<String, Object>();
            for (ComponentReference ref : refs) {
                ComponentDefinition targetDef = find(ref);
                String name = ref.getPropertyName();
                Object value = evaluate(targetDef);  // tail recursion でないがええじゃろ
                props.put(name, value);
            }
            return props;
        }

        private ComponentDefinition find(int id) {
            for (ComponentDefinition def : definitions) {
                if (def.getId() == id) {
                    return def;
                }
            }
            throw new NoSuchElementException("id: " + id);
        }

        private ComponentDefinition find(String name) {
            for (ComponentDefinition def : definitions) {
                if (name.equals(def.getName())) {
                    return def;
                }
            }
            throw new NoSuchElementException("name: " + name);
        }


        private ComponentDefinition find(ComponentReference ref) {
            for (ComponentDefinition def : definitions) {
                if (matches(def, ref)) {
                    return def;
                }
            }
            return null;
        }

        private boolean matches(ComponentDefinition def, ComponentReference ref) {
            InjectionType injectionType = ref.getInjectionType();
            switch (injectionType) {
                case ID:
                    return (ref.getTargetId() == def.getId());
                case REF:
                case BY_NAME:
                    return ref.getReferenceName().equals(def.getName());
                case BY_TYPE:
                    return ref.getRequiredType().isAssignableFrom(def.getType());
                default:
                    throw new IllegalStateException(injectionType.toString());
            }
        }
    }

    private static BufferedWriter open(File fileToWrite) {
        try {
            return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(fileToWrite), Charset.forName("UTF-8")));
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException e) {
            // ignore;
        }
    }

}
