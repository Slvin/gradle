/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.component.external.model.maven;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.attributes.Usage;
import org.gradle.api.internal.artifacts.ImmutableModuleIdentifierFactory;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.result.AttributeContainerSerializer;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.internal.component.external.descriptor.Configuration;
import org.gradle.internal.component.external.descriptor.MavenScope;
import org.gradle.internal.component.external.model.AbstractRealisedModuleComponentResolveMetadata;
import org.gradle.internal.component.external.model.AbstractRealisedModuleResolveMetadataSerializationHelper;
import org.gradle.internal.component.external.model.ComponentVariant;
import org.gradle.internal.component.external.model.ConfigurationBoundExternalDependencyMetadata;
import org.gradle.internal.component.external.model.ExternalDependencyDescriptor;
import org.gradle.internal.component.external.model.GradleDependencyMetadata;
import org.gradle.internal.component.external.model.ImmutableCapabilities;
import org.gradle.internal.component.external.model.LazyToRealisedModuleComponentResolveMetadataHelper;
import org.gradle.internal.component.external.model.ModuleComponentResolveMetadata;
import org.gradle.internal.component.external.model.ModuleDependencyMetadata;
import org.gradle.internal.component.external.model.RealisedConfigurationMetadata;
import org.gradle.internal.component.model.ConfigurationMetadata;
import org.gradle.internal.component.model.DependencyMetadata;
import org.gradle.internal.component.model.ExcludeMetadata;
import org.gradle.internal.component.model.IvyArtifactName;
import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.Encoder;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class RealisedMavenModuleResolveMetadataSerializationHelper extends AbstractRealisedModuleResolveMetadataSerializationHelper {

    private static final String COMPILE_DERIVED_VARIANT_NAME = "compile___derived";
    private static final String RUNTIME_DERIVED_VARIANT_NAME = "runtime___derived";

    public RealisedMavenModuleResolveMetadataSerializationHelper(AttributeContainerSerializer attributeContainerSerializer, ImmutableModuleIdentifierFactory moduleIdentifierFactory) {
        super(attributeContainerSerializer, moduleIdentifierFactory);
    }

    @Override
    public void writeRealisedConfigurationsData(Encoder encoder, AbstractRealisedModuleComponentResolveMetadata transformed) throws IOException {
        if (transformed instanceof RealisedMavenModuleResolveMetadata) {
            writeDerivedVariants(encoder, (RealisedMavenModuleResolveMetadata) transformed);
        }
        super.writeRealisedConfigurationsData(encoder, transformed);
    }

    public ModuleComponentResolveMetadata readMetadata(Decoder decoder, DefaultMavenModuleResolveMetadata resolveMetadata) throws IOException {
        Map<String, List<GradleDependencyMetadata>> variantToDependencies = readVariantDependencies(decoder);
        ImmutableList<? extends ComponentVariant> variants = resolveMetadata.getVariants();
        ImmutableList.Builder<AbstractRealisedModuleComponentResolveMetadata.ImmutableRealisedVariantImpl> builder = ImmutableList.builder();
        for (ComponentVariant variant: variants) {
            builder.add(new AbstractRealisedModuleComponentResolveMetadata.ImmutableRealisedVariantImpl(resolveMetadata.getId(), variant.getName(), variant.getAttributes().asImmutable(), variant.getDependencies(), variant.getDependencyConstraints(),
                variant.getFiles(), ImmutableCapabilities.of(variant.getCapabilities().getCapabilities()), variantToDependencies.get(variant.getName())));
        }
        ImmutableList<AbstractRealisedModuleComponentResolveMetadata.ImmutableRealisedVariantImpl> realisedVariants = builder.build();

        Map<String, ConfigurationMetadata> configurations = readMavenConfigurationsAndDerivedVariants(decoder, resolveMetadata);
        List<ConfigurationMetadata> derivedVariants = Lists.newArrayListWithCapacity(2);
        addDerivedVariant(configurations, derivedVariants, COMPILE_DERIVED_VARIANT_NAME);
        addDerivedVariant(configurations, derivedVariants, RUNTIME_DERIVED_VARIANT_NAME);
        return new RealisedMavenModuleResolveMetadata(resolveMetadata, realisedVariants, derivedVariants, configurations);
    }

    protected void writeDependencies(Encoder encoder, ConfigurationMetadata configuration) throws IOException {
        List<? extends DependencyMetadata> dependencies = configuration.getDependencies();
        encoder.writeSmallInt(dependencies.size());
        for (DependencyMetadata dependency: dependencies) {
            if (dependency instanceof GradleDependencyMetadata) {
                encoder.writeByte(GRADLE_DEPENDENCY_METADATA);
                writeDependencyMetadata(encoder, (GradleDependencyMetadata) dependency);
            } else if (dependency instanceof ConfigurationBoundExternalDependencyMetadata) {
                ConfigurationBoundExternalDependencyMetadata dependencyMetadata = (ConfigurationBoundExternalDependencyMetadata) dependency;
                ExternalDependencyDescriptor dependencyDescriptor = dependencyMetadata.getDependencyDescriptor();
                if (dependencyDescriptor instanceof MavenDependencyDescriptor) {
                    encoder.writeByte(MAVEN_DEPENDENCY_METADATA);
                    writeMavenDependency(encoder, (MavenDependencyDescriptor) dependencyDescriptor);
                } else {
                    throw new IllegalStateException("Unknown type of dependency descriptor: " + dependencyDescriptor.getClass());
                }
                encoder.writeNullableString(dependency.getReason());
            }
        }
    }

    private void writeDerivedVariants(Encoder encoder, RealisedMavenModuleResolveMetadata metadata) throws IOException {
        encoder.writeBoolean(!metadata.getDerivedVariants().isEmpty());
    }

    private Map<String, ConfigurationMetadata> readMavenConfigurationsAndDerivedVariants(Decoder decoder, DefaultMavenModuleResolveMetadata metadata) throws IOException {
        ImmutableMap<String, Configuration> configurationDefinitions = metadata.getConfigurationDefinitions();

        boolean derivedVariants = decoder.readBoolean();
        int configurationsCount = decoder.readSmallInt();
        Map<String, ConfigurationMetadata> configurations = Maps.newHashMapWithExpectedSize(configurationsCount);
        for (int i = 0; i < configurationsCount; i++) {
            String configurationName = decoder.readString();
            Configuration configuration = configurationDefinitions.get(configurationName);
            ImmutableSet<String> hierarchy = LazyToRealisedModuleComponentResolveMetadataHelper.constructHierarchy(configuration, configurationDefinitions);
            ImmutableAttributes attributes = getAttributeContainerSerializer().read(decoder);
            ImmutableCapabilities capabilities = readCapabilities(decoder);

            RealisedConfigurationMetadata configurationMetadata = new RealisedConfigurationMetadata(metadata.getId(), configurationName, configuration.isTransitive(), configuration.isVisible(),
                hierarchy, RealisedMavenModuleResolveMetadata.getArtifactsForConfiguration(metadata.getId(), configurationName), ImmutableList.<ExcludeMetadata>of(), attributes, capabilities);
            ImmutableList.Builder<ModuleDependencyMetadata> builder = ImmutableList.builder();
            int dependenciesCount = decoder.readSmallInt();
            for (int j = 0; j < dependenciesCount; j++) {
                byte dependencyType = decoder.readByte();
                switch(dependencyType) {
                    case GRADLE_DEPENDENCY_METADATA:
                        builder.add(readDependencyMetadata(decoder));
                        break;
                    case MAVEN_DEPENDENCY_METADATA:
                        MavenDependencyDescriptor mavenDependencyDescriptor = readMavenDependency(decoder);
                        ModuleDependencyMetadata dependencyMetadata = RealisedMavenModuleResolveMetadata.contextualize(configurationMetadata, metadata.getId(), mavenDependencyDescriptor);
                        builder.add(dependencyMetadata.withReason(decoder.readNullableString()));
                        break;
                    case IVY_DEPENDENCY_METADATA:
                        throw new IllegalStateException("Unexpected Ivy dependency for Maven module");
                    default:
                        throw new IllegalStateException("Unknown dependency type " + dependencyType);
                }
            }
            ImmutableList<ModuleDependencyMetadata> dependencies = builder.build();
            configurationMetadata.setDependencies(dependencies);

            configurations.put(configurationName, configurationMetadata);
            if (derivedVariants) {
                if (configurationName.equals("compile")) {
                    ConfigurationMetadata compileDerivedVariant = RealisedMavenModuleResolveMetadata.withUsageAttribute(configurationMetadata, Usage.JAVA_API, metadata.getAttributesFactory(), attributes, metadata.getObjectInstantiator());
                    configurations.put(COMPILE_DERIVED_VARIANT_NAME, compileDerivedVariant);
                } else if (configurationName.equals("runtime")) {
                    ConfigurationMetadata runtimeDerivedVariant = RealisedMavenModuleResolveMetadata.withUsageAttribute(configurationMetadata, Usage.JAVA_RUNTIME, metadata.getAttributesFactory(), attributes, metadata.getObjectInstantiator());
                    configurations.put(RUNTIME_DERIVED_VARIANT_NAME, runtimeDerivedVariant);
                }
            }
        }
        return configurations;
    }

    private void addDerivedVariant(Map<String, ConfigurationMetadata> configurations, List<ConfigurationMetadata> derivedVariants, String name) {
        ConfigurationMetadata configurationMetadata = configurations.remove(name);
        if (configurationMetadata != null) {
            derivedVariants.add(configurationMetadata);
        }
    }

    private MavenDependencyDescriptor readMavenDependency(Decoder decoder) throws IOException {
        ModuleComponentSelector requested = getComponentSelectorSerializer().read(decoder);
        IvyArtifactName artifactName = readNullableArtifact(decoder);
        List<ExcludeMetadata> mavenExcludes = readMavenExcludes(decoder);
        MavenScope scope = MavenScope.values()[decoder.readSmallInt()];
        MavenDependencyType type = MavenDependencyType.values()[decoder.readSmallInt()];
        return new MavenDependencyDescriptor(scope, type, requested, artifactName, mavenExcludes);
    }

    private void writeMavenDependency(Encoder encoder, MavenDependencyDescriptor mavenDependency) throws IOException {
        getComponentSelectorSerializer().write(encoder, mavenDependency.getSelector());
        writeNullableArtifact(encoder, mavenDependency.getDependencyArtifact());
        writeMavenExcludeRules(encoder, mavenDependency.getAllExcludes());
        encoder.writeSmallInt(mavenDependency.getScope().ordinal());
        encoder.writeSmallInt(mavenDependency.getType().ordinal());
    }

}
