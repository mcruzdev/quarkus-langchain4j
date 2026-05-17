package io.quarkiverse.langchain4j.infinispan;

import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigGroup;
import io.smallrye.config.WithDefault;

@ConfigGroup
public interface InfinispanNamedStoreBuildTimeConfig {

    /**
     * The name of the Infinispan client to use for this named store.
     * These clients are configured by means of the `infinispan-client` extension.
     * <p>
     * If unspecified, the default Infinispan client will be used.
     * <p>
     * Example configuration for a named store using a specific client:
     * <pre>
     * quarkus.langchain4j.infinispan.products.client-name=products-client
     * quarkus.infinispan-client.products-client.hosts=products-infinispan:11222
     * </pre>
     */
    @WithDefault("<default>")
    Optional<String> clientName();
}
