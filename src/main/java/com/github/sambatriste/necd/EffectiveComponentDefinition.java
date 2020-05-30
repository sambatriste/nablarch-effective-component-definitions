package com.github.sambatriste.necd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SequenceWriter;
import nablarch.core.repository.di.ComponentCreator;
import nablarch.core.repository.di.ComponentDefinition;
import nablarch.core.repository.di.ComponentReference;
import nablarch.core.repository.di.DiContainer;
import nablarch.core.repository.di.InjectionType;
import nablarch.core.repository.di.config.ListComponentCreator;
import nablarch.core.repository.di.config.ListElementDefinition;
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

/**
 * 有効なコンポーネント設定を表すクラス。
 * <p>
 * 指定されたコンポーネント設定ファイルを読み込み、コンポーネント設定の最終的な評価結果を出力する。
 */
public class EffectiveComponentDefinition {
    /**
     * mainメソッド
     *
     * @param args プログラム引数
     * @see ProgramArguments
     * @see #show(String, Writer)
     */
    public static void main(String... args) {
        ProgramArguments arguments = new ProgramArguments(args);
        new EffectiveComponentDefinition().show(arguments.componentFileFqcn, arguments.outputFile);
    }

    /** プログラム引数 */
    private static class ProgramArguments {
        /** デフォルトの出力先 */
        static final String DEFAULT_OUTPUT_FILE_PATH = "effective-components.json";

        /** 処理対象コンポーネント定義ファイルのFQCN */
        final String componentFileFqcn;

        /** 出力ファイル */
        final File outputFile;

        /**
         * コンストラクタ。
         * <ol>
         *     <li>処理対象コンポーネント定義ファイルのFQCN</li>
         *     <li>出力ファイルのパス（省略時は、{@link ProgramArguments#DEFAULT_OUTPUT_FILE_PATH}）</li>
         * </ol>
         *
         * @param args Java起動時のプログラム引数
         */
        ProgramArguments(String... args) {
            if (args.length == 0) {
                throw new IllegalArgumentException(Arrays.toString(args));
            }
            this.componentFileFqcn = args[0];
            String outputFile = args.length == 2 ? args[1] : DEFAULT_OUTPUT_FILE_PATH;
            this.outputFile = new File(outputFile);
        }

    }

    /**
     * 有効なコンポーネント設定を出力する。
     *
     * @param componentFileFqcn 処理対象コンポーネント定義ファイルのFQCN
     * @param fileToWrite       出力先ファイル
     */
    public void show(String componentFileFqcn, File fileToWrite) {
        Writer writer = null;
        try {
            writer = open(fileToWrite);
            show(componentFileFqcn, writer);
        } finally {
            closeQuietly(writer);
        }
    }

    /**
     * 有効なコンポーネント設定を出力する。
     *
     * @param componentFileFqcn 処理対象コンポーネント定義ファイルのFQCN
     * @param writer            ライター
     */
    public void show(String componentFileFqcn, Writer writer) {
        ObjectGraphBuilder builder = new ObjectGraphBuilder(componentFileFqcn);
        Map<String, Object> graph = builder.build();
        writeJson(graph, writer);
    }

    /**
     * JSON形式で出力する。
     *
     * @param graph  オブジェクトグラフ
     * @param writer ライター
     */
    private void writeJson(Map<String, Object> graph, Writer writer) {
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

    /** コンポーネント設定ファイルからオブジェクトグラフを構築するクラス。 */
    static class ObjectGraphBuilder {
        /** コンポーネント定義 */
        private final List<ComponentDefinition> definitions;

        /** {@link nablarch.core.repository.di.DiContainer} */
        private final DiContainer diContainer;

        /** コンポーネント定義検索クラス */
        private final ComponentDefinitionFinder finder;

        /**
         * コンストラクタ。
         *
         * @param componentFileFqcn 処理対象コンポーネント定義ファイルのFQCN
         */
        ObjectGraphBuilder(String componentFileFqcn) {
            XmlComponentDefinitionLoader loader = new XmlComponentDefinitionLoader(componentFileFqcn);
            diContainer = new DiContainer(loader);
            definitions = loader.load(diContainer);
            finder = new ComponentDefinitionFinder(definitions);
        }

        /**
         * オブジェクトグラフを構築する。
         *
         * @return オブジェクトグラフ
         */
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

        /**
         * コンポーネント定義を評価する。
         *
         * @param def コンポーネント定義
         * @return 評価結果
         */
        private Object evaluate(ComponentDefinition def) {
            if (def == null) {
                return null;
            }

            ComponentCreator creator = def.getCreator();

            // リテラル
            if (creator instanceof LiteralComponentCreator) {
                // DIコンテナを使って値を取得する
                return creator.createComponent(diContainer, def);
            }
            // リスト
            if (creator instanceof ListComponentCreator) {
                return evaluate((ListComponentCreator) creator);
            }
            // マップ
            if (creator instanceof MapComponentCreator) {
                return evaluate((MapComponentCreator) creator);
            }

            List<ComponentReference> refs = def.getReferences();
            if (refs.isEmpty()) {
                // ネストしていない場合、コンポーネントのクラス名を評価結果とする
                return def.getType();
            }
            // ネストしている場合
            return evaluate(refs);
        }

        /**
         * マップリンポーネント生成クラスを評価する。
         *
         * @param creator マップリンポーネント生成クラス
         * @return 評価結果
         */
        private Map<String, Object> evaluate(MapComponentCreator creator) {
            List<MapEntryDefinition> entries = getEntriesOf(creator);
            Map<String, Object> map = new TreeMap<String, Object>();

            for (MapEntryDefinition entry : entries) {
                String key = entry.getKey();
                Object value = entry.getValue();
                switch (entry.getValueType()) {
                    case COMPONENT: {
                        ComponentDefinition def = finder.find(entry.getValueId());
                        value = evaluate(def);
                        break;
                    }
                    case REF: {
                        ComponentDefinition def = finder.find(entry.getValueRef());
                        value = evaluate(def);
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
                return (List<MapEntryDefinition>) f.get(componentCreator);
            } catch (NoSuchFieldException e) {
                throw new RuntimeException(e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * リストコンポーネント生成クラスを評価する。
         *
         * @param creator リストコンポーネント生成クラス
         * @return 評価結果
         */
        private List<Object> evaluate(ListComponentCreator creator) {
            List<ListElementDefinition> elementDefs = getElementDefinitionsOf(creator);
            List<Object> result = new ArrayList<Object>();
            for (ListElementDefinition elementDef : elementDefs) {
                ComponentDefinition componentDef = (elementDef.getName() == null) ?
                    finder.find(elementDef.getId()) :
                    finder.find(elementDef.getName());
                result.add(evaluate(componentDef));
            }
            return result;
        }

        /**
         * リストコンポーネント生成クラスから、リストの要素定義を取得する。
         *
         * @param componentCreator リストコンポーネント生成クラス
         * @return リストの要素定義
         */
        @SuppressWarnings("unchecked")
        private List<ListElementDefinition> getElementDefinitionsOf(ListComponentCreator componentCreator) {
            try {
                Field f = ListComponentCreator.class.getDeclaredField("elementDefinitions");
                f.setAccessible(true);
                return (List<ListElementDefinition>) f.get(componentCreator);
            } catch (NoSuchFieldException e) {
                throw new RuntimeException(e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * コンポーネント参照を評価する。
         *
         * @param refs コンポーネント参照
         * @return 評価結果
         */
        private Object evaluate(List<ComponentReference> refs) {
            Map<String, Object> props = new TreeMap<String, Object>();
            for (ComponentReference ref : refs) {
                ComponentDefinition targetDef = finder.find(ref);
                String name = ref.getPropertyName();
                Object value = evaluate(targetDef);
                props.put(name, value);
            }
            return props;
        }
    }

    /** コンポーネント定義を検索するクラス。 */
    static class ComponentDefinitionFinder {
        /** コンポーネント定義 */
        private final List<ComponentDefinition> definitions;

        /**
         * コンストラクタ
         *
         * @param definitions コンポーネント定義
         */
        ComponentDefinitionFinder(List<ComponentDefinition> definitions) {
            this.definitions = definitions;
        }

        /**
         * コンポーネント定義を検索する。
         *
         * @param id コンポーネントID
         * @return コンポーネント定義
         */
        private ComponentDefinition find(int id) {
            for (ComponentDefinition def : definitions) {
                if (def.getId() == id) {
                    return def;
                }
            }
            throw new NoSuchElementException("id: " + id);
        }

        /**
         * コンポーネント定義を検索する。
         *
         * @param name コンポーネント名
         * @return コンポーネント定義
         */
        private ComponentDefinition find(String name) {
            for (ComponentDefinition def : definitions) {
                if (name.equals(def.getName())) {
                    return def;
                }
            }
            throw new NoSuchElementException("name: " + name);
        }

        /**
         * コンポーネント定義を検索する。
         *
         * @param ref コンポーネント参照
         * @return コンポーネント定義
         */
        private ComponentDefinition find(ComponentReference ref) {
            for (ComponentDefinition def : definitions) {
                if (matches(def, ref)) {
                    return def;
                }
            }
            return null;
        }

        /**
         * コンポーネント定義がコンポーネント参照と合致するか判定する。
         *
         * @param def コンポーネント定義
         * @param ref コンポーネント参照
         * @return 合致する場合、真
         */
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

    /**
     * ファイルを出力用にオープンする
     * @param fileToWrite 出力先ファイル
     * @return ライター
     */
    private static BufferedWriter open(File fileToWrite) {
        try {
            return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(fileToWrite), Charset.forName("UTF-8")));
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * リソースをクローズする。
     * @param closeable クローズ対象
     */
    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException e) {
            // ignore;
        }
    }

}
