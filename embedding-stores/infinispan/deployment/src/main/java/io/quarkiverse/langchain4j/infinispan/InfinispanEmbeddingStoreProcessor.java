package io.quarkiverse.langchain4j.infinispan;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;

import org.infinispan.client.hotrod.RemoteCacheManager;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ClassType;
import org.jboss.jandex.DotName;
import org.jboss.jandex.ParameterizedType;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.quarkiverse.langchain4j.EmbeddingStoreName;
import io.quarkiverse.langchain4j.deployment.EmbeddingStoreBuildItem;
import io.quarkiverse.langchain4j.infinispan.runtime.InfinispanEmbeddingStoreRecorder;
import io.quarkiverse.langchain4j.runtime.NamedConfigUtil;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.infinispan.client.InfinispanClientName;
import io.quarkus.infinispan.client.deployment.InfinispanClientNameBuildItem;

/**
 * Quarkus build step processor that registers the Infinispan embedding store extension.
 * Sets up the Infinispan client and creates the embedding store beans (default and named).
 */
public class InfinispanEmbeddingStoreProcessor {

    public static final DotName INFINISPAN_EMBEDDING_STORE = DotName.createSimple(InfinispanEmbeddingStore.class);

    private static final String FEATURE = "langchain4j-infinispan";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    public void requestInfinispanClients(
            BuildProducer<InfinispanClientNameBuildItem> infinispanClientProducer,
            BuildProducer<io.quarkus.arc.deployment.AdditionalBeanBuildItem> additionalBeans,
            InfinispanEmbeddingStoreBuildTimeConfig config) {

        additionalBeans.produce(io.quarkus.arc.deployment.AdditionalBeanBuildItem.unremovableOf(SchemaAndMarshallerProducer.class));

        // Request default client if default store is enabled
        if (config.defaultConfig().defaultStoreEnabled()) {
            String defaultClientName = config.defaultConfig().clientName().orElse("<default>");
            infinispanClientProducer.produce(new InfinispanClientNameBuildItem(defaultClientName));
        }

        // Request clients for named stores
        for (Map.Entry<String, InfinispanNamedStoreBuildTimeConfig> entry : config.namedConfig().entrySet()) {
            String clientName = entry.getValue().clientName().orElse("<default>");
            infinispanClientProducer.produce(new InfinispanClientNameBuildItem(clientName));
        }
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    public void createBeans(
            BuildProducer<SyntheticBeanBuildItem> beanProducer,
            InfinispanEmbeddingStoreRecorder recorder,
            BuildProducer<EmbeddingStoreBuildItem> embeddingStoreProducer,
            InfinispanEmbeddingStoreBuildTimeConfig buildTimeConfig,
            io.quarkiverse.langchain4j.infinispan.runtime.InfinispanEmbeddingStoreConfig runtimeConfig) {

        // Validate dimension consistency across all stores
        if (buildTimeConfig.defaultConfig().defaultStoreEnabled()) {
            Long defaultDimension = runtimeConfig.defaultConfig().dimension();
            for (Map.Entry<String, io.quarkiverse.langchain4j.infinispan.runtime.InfinispanStoreRuntimeConfig> entry : runtimeConfig.namedConfig().entrySet()) {
                Long namedDimension = entry.getValue().dimension();
                if (namedDimension != null && defaultDimension != null && !namedDimension.equals(defaultDimension)) {
                    throw new IllegalStateException(
                            String.format("Named store '%s' has dimension %d which differs from default store dimension %d. " +
                                    "All Infinispan embedding stores must use the same dimension because they share a global Protobuf schema.",
                                    entry.getKey(), namedDimension, defaultDimension));
                }
            }
        }

        // Create default store if enabled
        if (buildTimeConfig.defaultConfig().defaultStoreEnabled()) {
            String defaultClientName = buildTimeConfig.defaultConfig().clientName().orElse(null);
            AnnotationInstance infinispanClientQualifier = resolveClientQualifier(defaultClientName);

            beanProducer.produce(SyntheticBeanBuildItem
                    .configure(INFINISPAN_EMBEDDING_STORE)
                    .types(ClassType.create(EmbeddingStore.class),
                            ParameterizedType.create(EmbeddingStore.class, ClassType.create(TextSegment.class)))
                    .setRuntimeInit()
                    .defaultBean()
                    .unremovable()
                    .scope(ApplicationScoped.class)
                    .addInjectionPoint(ClassType.create(DotName.createSimple(RemoteCacheManager.class)),
                            infinispanClientQualifier)
                    .createWith(recorder.embeddingStoreFunction(defaultClientName, NamedConfigUtil.DEFAULT_NAME))
                    .done());

            embeddingStoreProducer.produce(new EmbeddingStoreBuildItem());
        }

        // Create named stores
        Map<String, InfinispanNamedStoreBuildTimeConfig> namedStores = buildTimeConfig.namedConfig();
        for (Map.Entry<String, InfinispanNamedStoreBuildTimeConfig> entry : namedStores.entrySet()) {
            String storeName = entry.getKey();
            String clientName = entry.getValue().clientName().orElse(null);

            AnnotationInstance storeNameQualifier = AnnotationInstance.builder(EmbeddingStoreName.class)
                    .add("value", storeName)
                    .build();
            AnnotationInstance infinispanClientQualifier = resolveClientQualifier(clientName);

            beanProducer.produce(SyntheticBeanBuildItem
                    .configure(INFINISPAN_EMBEDDING_STORE)
                    .types(ClassType.create(EmbeddingStore.class),
                            ParameterizedType.create(EmbeddingStore.class, ClassType.create(TextSegment.class)))
                    .setRuntimeInit()
                    .unremovable()
                    .scope(ApplicationScoped.class)
                    .addQualifier(storeNameQualifier)
                    .addInjectionPoint(ClassType.create(DotName.createSimple(RemoteCacheManager.class)),
                            infinispanClientQualifier)
                    .createWith(recorder.embeddingStoreFunction(clientName, storeName))
                    .done());

            embeddingStoreProducer.produce(new EmbeddingStoreBuildItem());
        }
    }

    private AnnotationInstance resolveClientQualifier(String clientName) {
        if (clientName != null && !NamedConfigUtil.isDefault(clientName)) {
            return AnnotationInstance.builder(InfinispanClientName.class)
                    .add("value", clientName)
                    .build();
        }
        return AnnotationInstance.builder(Default.class).build();
    }
}
