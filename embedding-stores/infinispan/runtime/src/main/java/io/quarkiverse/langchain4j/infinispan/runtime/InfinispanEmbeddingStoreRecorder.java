package io.quarkiverse.langchain4j.infinispan.runtime;

import java.util.function.Function;

import org.infinispan.client.hotrod.RemoteCacheManager;

import io.quarkiverse.langchain4j.infinispan.InfinispanEmbeddingStore;
import io.quarkiverse.langchain4j.runtime.NamedConfigUtil;
import io.quarkus.arc.SyntheticCreationalContext;
import io.quarkus.infinispan.client.InfinispanClientName;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

/**
 * Quarkus recorder that creates the {@link InfinispanEmbeddingStore} bean at runtime,
 * wiring the Infinispan cache manager and configuration together.
 */
@Recorder
public class InfinispanEmbeddingStoreRecorder {
    private final RuntimeValue<InfinispanEmbeddingStoreConfig> runtimeConfig;

    public InfinispanEmbeddingStoreRecorder(RuntimeValue<InfinispanEmbeddingStoreConfig> runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
    }

    public Function<SyntheticCreationalContext<InfinispanEmbeddingStore>, InfinispanEmbeddingStore> embeddingStoreFunction(
            String clientName, String storeName) {
        return new Function<>() {
            @Override
            public InfinispanEmbeddingStore apply(SyntheticCreationalContext<InfinispanEmbeddingStore> context) {
                InfinispanEmbeddingStore.Builder builder = new InfinispanEmbeddingStore.Builder();

                // Inject the appropriate RemoteCacheManager
                RemoteCacheManager cacheManager;
                if (clientName == null || NamedConfigUtil.isDefault(clientName)) {
                    cacheManager = context.getInjectedReference(RemoteCacheManager.class);
                } else {
                    cacheManager = context.getInjectedReference(RemoteCacheManager.class,
                            new InfinispanClientName.Literal(clientName));
                }
                builder.cacheManager(cacheManager);

                // Get the store-specific configuration
                InfinispanStoreRuntimeConfig config = correspondingStoreConfig(storeName);

                // Create schema with store-specific configuration
                builder.schema(new InfinispanSchema(
                        config.cacheName(),
                        config.dimension(),
                        config.distance(),
                        config.similarity(),
                        config.createCache(),
                        config.cacheConfig().orElse(null)));

                return builder.build();
            }
        };
    }

    private InfinispanStoreRuntimeConfig correspondingStoreConfig(String storeName) {
        InfinispanStoreRuntimeConfig storeConfig;
        if (NamedConfigUtil.isDefault(storeName)) {
            storeConfig = runtimeConfig.getValue().defaultConfig();
        } else {
            storeConfig = runtimeConfig.getValue().namedConfig().get(storeName);
        }
        return storeConfig;
    }
}
