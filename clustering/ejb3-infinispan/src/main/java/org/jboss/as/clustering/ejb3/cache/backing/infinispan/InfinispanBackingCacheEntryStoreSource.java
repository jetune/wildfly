/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.clustering.ejb3.cache.backing.infinispan;

import java.io.Serializable;

import org.infinispan.Cache;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.eviction.EvictionStrategy;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.remoting.transport.Address;
import org.jboss.as.clustering.infinispan.affinity.KeyAffinityServiceFactory;
import org.jboss.as.clustering.infinispan.affinity.KeyAffinityServiceFactoryService;
import org.jboss.as.clustering.infinispan.invoker.BatchCacheInvoker;
import org.jboss.as.clustering.infinispan.invoker.CacheInvoker;
import org.jboss.as.clustering.infinispan.invoker.RetryingCacheInvoker;
import org.jboss.as.clustering.infinispan.subsystem.CacheService;
import org.jboss.as.clustering.infinispan.subsystem.EmbeddedCacheManagerService;
import org.jboss.as.clustering.marshalling.MarshalledValue;
import org.jboss.as.clustering.marshalling.MarshalledValueFactory;
import org.jboss.as.clustering.marshalling.MarshallingContext;
import org.jboss.as.clustering.marshalling.SimpleMarshalledValueFactory;
import org.jboss.as.clustering.marshalling.SimpleMarshallingContextFactory;
import org.jboss.as.ejb3.cache.Cacheable;
import org.jboss.as.ejb3.cache.IdentifierFactory;
import org.jboss.as.ejb3.cache.PassivationManager;
import org.jboss.as.ejb3.cache.impl.backing.clustering.ClusteredBackingCacheEntryStoreConfig;
import org.jboss.as.ejb3.cache.impl.backing.clustering.ClusteredBackingCacheEntryStoreSource;
import org.jboss.as.ejb3.cache.impl.backing.clustering.ClusteredBackingCacheEntryStoreSourceService;
import org.jboss.as.ejb3.cache.spi.BackingCacheEntryStore;
import org.jboss.as.ejb3.cache.spi.SerializationGroup;
import org.jboss.as.ejb3.cache.spi.SerializationGroupMember;
import org.jboss.as.ejb3.cache.spi.impl.AbstractBackingCacheEntryStoreSource;
import org.jboss.as.ejb3.component.stateful.StatefulTimeoutInfo;
import org.jboss.as.ejb3.remote.EJBRemotingConnectorClientMappingsEntryProviderService;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceBuilder.DependencyType;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.ValueService;
import org.jboss.msc.value.InjectedValue;
import org.wildfly.clustering.group.NodeFactory;
import org.wildfly.clustering.registry.Registry;
import org.wildfly.clustering.registry.RegistryEntryProvider;

/**
 * {@link org.jboss.as.ejb3.cache.spi.BackingCacheEntryStoreSource} that provides instances of {@link InfinispanBackingCacheEntryStore}.
 *
 * @author Brian Stansberry
 */
public class InfinispanBackingCacheEntryStoreSource<K extends Serializable, V extends Cacheable<K>, G extends Serializable> extends AbstractBackingCacheEntryStoreSource<K, V, G> implements ClusteredBackingCacheEntryStoreSource<K, V, G> {

    private String cacheContainerName = DEFAULT_CACHE_CONTAINER;
    private String beanCacheName = DEFAULT_BEAN_CACHE;
    private String clientMappingsCacheName = DEFAULT_CLIENT_MAPPING_CACHE;
    private int maxSize = ClusteredBackingCacheEntryStoreConfig.DEFAULT_MAX_SIZE;
    private boolean passivateEventsOnReplicate = DEFAULT_PASSIVATE_EVENTS_ON_REPLICATE;

    private CacheInvoker invoker = new RetryingCacheInvoker(new BatchCacheInvoker(), 10, 100);
    @SuppressWarnings("rawtypes")
    private final InjectedValue<Cache> groupCache = new InjectedValue<>();
    @SuppressWarnings("rawtypes")
    private final InjectedValue<Registry> registry = new InjectedValue<>();
    @SuppressWarnings("rawtypes")
    private final InjectedValue<NodeFactory> nodeFactory = new InjectedValue<>();
    private final InjectedValue<KeyAffinityServiceFactory> affinityFactory = new InjectedValue<>();

    @SuppressWarnings({ "rawtypes" })
    @Override
    public void addDependencies(ServiceTarget target, ServiceBuilder<?> builder) {
        ServiceName groupCacheServiceName = CacheService.getServiceName(this.cacheContainerName, this.beanCacheName);
        // AS7-3906 Ensure that the cache manager's rpc dispatcher starts before GroupCommunicationService's
        builder.addDependency(groupCacheServiceName, Cache.class, this.groupCache);
        // This is optional - only use if we're actually in a cluster
        builder.addDependency(DependencyType.OPTIONAL, ServiceName.JBOSS.append("clustering", "registry", this.cacheContainerName, this.clientMappingsCacheName), Registry.class, this.registry);
        builder.addDependency(DependencyType.OPTIONAL, ServiceName.JBOSS.append("clustering", "nodes", this.cacheContainerName, this.clientMappingsCacheName), NodeFactory.class, this.nodeFactory);
        builder.addDependency(KeyAffinityServiceFactoryService.getServiceName(this.cacheContainerName), KeyAffinityServiceFactory.class, this.affinityFactory);

        InjectedValue<RegistryEntryProvider> registryEntryProvider = new InjectedValue<>();
        target.addService(ServiceName.JBOSS.append("clustering", "registry", this.cacheContainerName, this.clientMappingsCacheName, "entry"), new ValueService<>(registryEntryProvider))
                .addDependency(EJBRemotingConnectorClientMappingsEntryProviderService.SERVICE_NAME, RegistryEntryProvider.class, registryEntryProvider)
                .setInitialMode(ServiceController.Mode.ON_DEMAND)
                .install()
        ;
        InjectedValue<EmbeddedCacheManager> container = new InjectedValue<>();
        target.addService(ClusteredBackingCacheEntryStoreSourceService.getCacheContainerClusterNameServiceName(this.cacheContainerName), new ClusterNameService(container))
                .addDependency(EmbeddedCacheManagerService.getServiceName(this.cacheContainerName), EmbeddedCacheManager.class, container)
                .setInitialMode(ServiceController.Mode.ON_DEMAND)
                .install()
        ;
    }

    @Override
    public <E extends SerializationGroup<K, V, G>> BackingCacheEntryStore<G, Cacheable<G>, E> createGroupIntegratedObjectStore(IdentifierFactory<G> identifierFactory, PassivationManager<G, E> passivationManager, StatefulTimeoutInfo timeout) {
        Cache<G, MarshalledValue<E, MarshallingContext>> cache = this.groupCache.getValue();
        MarshallingContext context = new SimpleMarshallingContextFactory().createMarshallingContext(passivationManager, passivationManager.getClassLoader());
        MarshalledValueFactory<MarshallingContext> valueFactory = new SimpleMarshalledValueFactory(context);
        Registry<String, ?> registry = this.registry.getOptionalValue();
        NodeFactory<Address> nodeFactory = this.nodeFactory.getOptionalValue();
        return new InfinispanBackingCacheEntryStore<G, Cacheable<G>, E, MarshallingContext>(cache, this.invoker, identifierFactory, this.affinityFactory.getValue(), null, timeout, this, false, valueFactory, context, nodeFactory, registry);
    }

    @Override
    public <E extends SerializationGroupMember<K, V, G>> BackingCacheEntryStore<K, V, E> createIntegratedObjectStore(final String beanName, IdentifierFactory<K> identifierFactory, PassivationManager<K, E> passivationManager, StatefulTimeoutInfo timeout) {
        Cache<?, ?> groupCache = this.groupCache.getValue();
        Configuration groupCacheConfiguration = groupCache.getCacheConfiguration();
        EmbeddedCacheManager container = groupCache.getCacheManager();
        ConfigurationBuilder builder = new ConfigurationBuilder().read(groupCacheConfiguration);
        builder.storeAsBinary().enable().storeKeysAsBinary(true).storeValuesAsBinary(false);
        if (this.maxSize > 0) {
            if (!groupCacheConfiguration.eviction().strategy().isEnabled()) {
                builder.eviction().strategy(EvictionStrategy.LRU);
            }
            builder.eviction().maxEntries(this.maxSize);
        }
        groupCache.getCacheManager().defineConfiguration(beanName, builder.build());
        Cache<K, MarshalledValue<E, MarshallingContext>> cache = container.<K, MarshalledValue<E, MarshallingContext>>getCache(beanName);
        MarshallingContext context = new SimpleMarshallingContextFactory().createMarshallingContext(passivationManager, passivationManager.getClassLoader());
        MarshalledValueFactory<MarshallingContext> valueFactory = new SimpleMarshalledValueFactory(context);
        NodeFactory<Address> nodeFactory = this.nodeFactory.getOptionalValue();
        Registry<String, ?> registry = this.registry.getOptionalValue();
        return new InfinispanBackingCacheEntryStore<K, V, E, MarshallingContext>(cache, this.invoker, identifierFactory, this.affinityFactory.getValue(), this.passivateEventsOnReplicate ? passivationManager : null, timeout, this, true, valueFactory, context, nodeFactory, registry);
    }

    public void setKeyAffinityServiceFactory(KeyAffinityServiceFactory affinityFactory) {
        this.affinityFactory.inject(affinityFactory);
    }

    @Override
    public String getCacheContainer() {
        return this.cacheContainerName;
    }

    @Override
    public void setCacheContainer(String cacheContainerName) {
        this.cacheContainerName = cacheContainerName;
    }

    @Override
    public String getClientMappingCache() {
        return this.clientMappingsCacheName;
    }

    @Override
    public void setClientMappingCache(String cacheName) {
        this.clientMappingsCacheName = cacheName;
    }

    @Override
    public String getBeanCache() {
        return this.beanCacheName;
    }

    @Override
    public void setBeanCache(String cacheName) {
        this.beanCacheName = cacheName;
    }

    @Override
    public boolean isPassivateEventsOnReplicate() {
        return this.passivateEventsOnReplicate;
    }

    @Override
    public void setPassivateEventsOnReplicate(boolean passivateEventsOnReplicate) {
        this.passivateEventsOnReplicate = passivateEventsOnReplicate;
    }

    @Override
    public int getMaxSize() {
        return this.maxSize;
    }

    @Override
    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }

    @Override
    public void started() {
    }

    @Override
    public void stopped() {
    }
}
