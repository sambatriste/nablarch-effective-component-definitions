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
import nablarch.core.repository.di.config.xml.XmlComponentDefinitionLoader;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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

    public static void main(String... args) throws IOException {
        ProgramArguments arguments = new ProgramArguments(args);
        effectiveDef.show(arguments.componentFileFqcn, arguments.outputFile);
    }

    public void show(String componentFileFqcn, File fileToWrite) throws IOException {
        Writer writer = null;
        try {
            writer = open(fileToWrite);
            show(componentFileFqcn, writer);
        } finally {
            closeQuietly(writer);
        }
    }

    public void show(String componentFileFqcn, Writer writer) throws IOException {
        ObjectGraphBuilder builder = new ObjectGraphBuilder(componentFileFqcn);
        Map<String, Object> graph = builder.build();
        doWrite(graph, writer);
    }

    private void doWrite(Map<String, Object> graph, Writer writer) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        //objectMapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

        SequenceWriter seqWriter = null;
        try {
            seqWriter = objectMapper.writerWithDefaultPrettyPrinter().writeValues(writer);
            seqWriter.write(graph);
        } finally {
            if (seqWriter != null) {
                seqWriter.close();
            }
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
                return evaluate((LiteralComponentCreator) creator);
            }

            if (creator instanceof ListComponentCreator) {
                return evaluate((ListComponentCreator) creator);
            }
            List<ComponentReference> refs = def.getReferences();
            if (refs.isEmpty()) {
                return def.getType();
            }

            return evaluate(refs);
        }

        private Object evaluate(ListComponentCreator creator) {
            return creator.toString();
        }

        private static Pattern VALUE_PATTERN = Pattern.compile(".*,value=(.+)]$");

        private static Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{(.+)}");

        private Object evaluate(LiteralComponentCreator c) {
            // "literal object = [type=java.lang.String,value=${nablarch.ibmExtendedCharset.allowedCharacters}]"
            String orig = c.toString();
            Matcher valueMatcher = VALUE_PATTERN.matcher(orig);
            if (valueMatcher.find()) {
                String value = valueMatcher.group(1);
                Matcher variableMatcher = VARIABLE_PATTERN.matcher(value);
                if (variableMatcher.find()) {
                    String varName = variableMatcher.group(1);
                    return diContainer.getComponentByName(varName);
                }
                return value;
            }
            return orig;
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

    private static BufferedWriter open(File fileToWrite) throws FileNotFoundException {
        return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(fileToWrite), Charset.forName("UTF-8")));
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            // ignore;
        }
    }

}
