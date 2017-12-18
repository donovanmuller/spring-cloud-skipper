/*
 * Copyright 2017 the original author or authors.
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
package org.springframework.cloud.skipper.server.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.configurationmetadata.ConfigurationMetadataProperty;
import org.springframework.cloud.dataflow.configuration.metadata.ApplicationConfigurationMetadataResolver;
import org.springframework.cloud.dataflow.configuration.metadata.BootApplicationConfigurationMetadataResolver;
import org.springframework.cloud.deployer.resource.maven.MavenProperties;
import org.springframework.cloud.deployer.resource.maven.MavenResourceLoader;
import org.springframework.cloud.servicebroker.model.Catalog;
import org.springframework.cloud.servicebroker.model.MethodSchema;
import org.springframework.cloud.servicebroker.model.Plan;
import org.springframework.cloud.servicebroker.model.Schemas;
import org.springframework.cloud.servicebroker.model.ServiceDefinition;
import org.springframework.cloud.servicebroker.model.ServiceInstanceSchema;
import org.springframework.cloud.servicebroker.service.CatalogService;
import org.springframework.cloud.skipper.domain.ConfigValues;
import org.springframework.cloud.skipper.domain.Deployer;
import org.springframework.cloud.skipper.domain.Package;
import org.springframework.cloud.skipper.domain.PackageMetadata;
import org.springframework.cloud.skipper.server.domain.SpringCloudDeployerApplicationManifest;
import org.springframework.cloud.skipper.server.domain.SpringCloudDeployerApplicationManifestReader;
import org.springframework.cloud.skipper.server.repository.DeployerRepository;
import org.springframework.cloud.skipper.server.repository.PackageMetadataRepository;
import org.springframework.cloud.skipper.server.service.ConfigValueUtils;
import org.springframework.cloud.skipper.server.service.ManifestUtils;
import org.springframework.cloud.skipper.server.service.PackageService;
import org.springframework.cloud.skipper.server.service.ReleaseService;
import org.springframework.cloud.skipper.server.service.ServiceBrokerInstanceService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author Donovan Muller
 */
@Configuration
public class ServiceBrokerCatalogConfiguration {

	private static final Logger log = LoggerFactory.getLogger(ServiceBrokerCatalogConfiguration.class);

	@Bean
	public ApplicationConfigurationMetadataResolver metadataResolver() {
		return new BootApplicationConfigurationMetadataResolver();
	}

	@Bean
	public CatalogService beanCatalogService(
			PackageMetadataRepository packageMetadataRepository,
			PackageService packageService,
			SpringCloudDeployerApplicationManifestReader applicationManifestReader,
			ApplicationConfigurationMetadataResolver metadataResolver,
			DeployerRepository deployerRepository, MavenProperties mavenProperties) {
		return new CatalogService() {

			private List<ServiceDefinition> serviceDefinitions = new ArrayList<>();

			@Override
			public Catalog getCatalog() {
				log.debug("Getting catalog...");

				DynamicCatalog dynamicCatalog = new DynamicCatalog(packageMetadataRepository,
						packageService,
						applicationManifestReader,
						metadataResolver,
						deployerRepository, mavenProperties);

				serviceDefinitions.clear();
				serviceDefinitions.addAll(dynamicCatalog.getServiceDefinitions());

				log.debug("Catalog: {}", dynamicCatalog);
				return new Catalog(dynamicCatalog.getServiceDefinitions());
			}

			@Override
			public ServiceDefinition getServiceDefinition(String serviceId) {
				return serviceDefinitions.stream().filter(
						serviceDefinition -> serviceDefinition.getId().equals(serviceId))
						.findFirst()
						.orElseThrow(() -> new IllegalArgumentException(
								String.format("Service definition [%s] does not exist", serviceId)));
			}
		};
	}

	@Bean
	public Catalog catalog(PackageMetadataRepository packageMetadataRepository,
			PackageService packageService,
			SpringCloudDeployerApplicationManifestReader applicationManifestReader,
			ApplicationConfigurationMetadataResolver metadataResolver,
			DeployerRepository deployerRepository, MavenProperties mavenProperties) {

		return new DynamicCatalog(packageMetadataRepository, packageService,
				applicationManifestReader, metadataResolver, deployerRepository, mavenProperties);
	}

	@Bean
	public ServiceBrokerInstanceService serviceBrokerInstanceService(ReleaseService releaseService) {
		return new ServiceBrokerInstanceService(releaseService);
	}

	class DynamicCatalog extends Catalog {

		private final PackageMetadataRepository packageMetadataRepository;

		private final PackageService packageService;

		private final SpringCloudDeployerApplicationManifestReader applicationManifestReader;

		private final ApplicationConfigurationMetadataResolver metadataResolver;

		private final DeployerRepository deployerRepository;

		private MavenProperties mavenProperties;

		DynamicCatalog(PackageMetadataRepository packageMetadataRepository,
				PackageService packageService,
				SpringCloudDeployerApplicationManifestReader applicationManifestReader,
				ApplicationConfigurationMetadataResolver metadataResolver,
				DeployerRepository deployerRepository, MavenProperties mavenProperties) {
			this.packageMetadataRepository = packageMetadataRepository;
			this.packageService = packageService;
			this.applicationManifestReader = applicationManifestReader;
			this.metadataResolver = metadataResolver;
			this.deployerRepository = deployerRepository;
			this.mavenProperties = mavenProperties;
		}

		public List<ServiceDefinition> getServiceDefinitions() {
			log.info("Getting Skipper packages as Service Definitions...");

			// extract all the packages by name
			Stream<PackageMetadata> packageMetadata = StreamSupport
					.stream(packageMetadataRepository.findAll().spliterator(), false);
			Map<String, List<PackageMetadata>> packages = packageMetadata
					.collect(Collectors.groupingBy(PackageMetadata::getName));

			Map<String, String> versions = getVersions(packages);
			String platforms = getPlatforms();

			MavenResourceLoader resourceLoader = new MavenResourceLoader(mavenProperties);

			return packages.entrySet().stream()
					.map(aggregatedPackages -> {
						PackageMetadata metadata = aggregatedPackages.getValue().get(0);

						Set<Plan> plans = new HashSet<>();
						aggregatedPackages.getValue().forEach(planMetadata -> {
							Package aPackage = packageService.downloadPackage(metadata);

							// See
							// org.springframework.cloud.skipper.server.service.ReleaseService#install(org.springframework.cloud.skipper.domain.Release)
							Map<String, Object> mergedMap = ConfigValueUtils.mergeConfigValues(aPackage,
									new ConfigValues());
							String manifest = ManifestUtils.createManifest(aPackage, mergedMap);
							List<SpringCloudDeployerApplicationManifest> manifests = applicationManifestReader
									.read(manifest);

							// Use the metadata artifact to grab a list of application properties to surface in the
							// Service Catalog UI
							String resourceMetadata = manifests.get(0).getSpec().getResourceMetadata();
							List<ConfigurationMetadataProperty> properties = metadataResolver
									.listProperties(resourceLoader.getResource(resourceMetadata));

							plans.add(new Plan(planMetadata.getName(),
									planMetadata.getName(),
									planMetadata.getDescription(),
									null,
									true,
									false,
									buildSchema(properties, versions.get(metadata.getName()), platforms)));
						});

						return new ServiceDefinition(
								metadata.getName(),
								metadata.getName(),
								metadata.getDescription(),
								false,
								true,
								new ArrayList<>(plans),
								Arrays.asList(StringUtils.stripAll(StringUtils.split(metadata.getTags(), ','))),
								getServiceDefinitionMetadata(
										metadata.getDisplayName() == null ? metadata.getName()
												: metadata.getDisplayName(),
										metadata.getIconUrl(),
										metadata.getPackageHomeUrl()),
								null,
								null);
					})
					.collect(Collectors.toList());
		}

		private Map<String, String> getVersions(Map<String, List<PackageMetadata>> packages) {
			Map<String, String> versions = new HashMap<>();
			packages.forEach((name, packageMetadata1) -> {
				StringJoiner joiner = new StringJoiner(",");
				packageMetadata1.forEach(metadata -> joiner.add(metadata.getVersion()));

				versions.put(name, joiner.toString());
			});

			return versions;
		}

		private String getPlatforms() {
			return StreamSupport.stream(deployerRepository.findAll().spliterator(), false)
					.map(Deployer::getName)
					.collect(Collectors.joining(", "));
		}

		private Schemas buildSchema(List<ConfigurationMetadataProperty> properties, String versions, String platforms) {
			Map<String, Object> applicationProperties = new LinkedHashMap<>();

			// Surface the application properties in the Service Catalog UI
			properties.forEach(property -> {
				Map<String, Object> applicationProperty = new HashMap<>();
				applicationProperty.put("title",
						StringUtils.capitalize(StringUtils.join(
								StringUtils.splitByCharacterTypeCamelCase(property.getName()),
								StringUtils.SPACE)));
				applicationProperty.put("description", property.getShortDescription());
				applicationProperty.put("default", property.getDefaultValue());
				applicationProperty.put("type", property.getType().replaceAll("java.lang.", "")
						.toLowerCase());

				applicationProperties.put(property.getId().replaceAll("\\.", "-"),
						applicationProperty);
			});

			Map<String, Object> version = new HashMap<>();
			version.put("title", "Versions");
			version.put("description", String.format("Choose from one of the following versions: %s", versions));
			version.put("default",
					StringUtils.defaultIfBlank(StringUtils.substringBefore(versions, ","), versions.trim()));
			version.put("type", "string");
			applicationProperties.put("version", version);

			Map<String, Object> platform = new HashMap<>();
			platform.put("title", "Platforms");
			platform.put("description", String.format("Choose from one of the following platforms: %s", platforms));
			platform.put("default",
					StringUtils.defaultIfBlank(StringUtils.substringAfter(platforms, ","), platforms).trim());
			platform.put("type", "string");
			applicationProperties.put("platform", platform);

			Map<String, Object> deploymentProperties = new HashMap<>();
			deploymentProperties.put("title", "Deployment properties");
			deploymentProperties.put("description", "Provide deployment properties to the deployer platform");
			deploymentProperties.put("type", "string");
			applicationProperties.put("deploymentProperties", deploymentProperties);

			Map<String, Object> parameters = new HashMap<>();
			parameters.put("$schema", "http://json-schema.org/draft-04/schema");
			parameters.put("type", "object");
			parameters.put("additionalProperties", false);
			parameters.put("properties", applicationProperties);

			return new Schemas(new ServiceInstanceSchema(
					new MethodSchema(parameters), null),
					null);
		}

		private Map<String, Object> getServiceDefinitionMetadata(String displayName,
				String icon, String supportUrl) {
			Map<String, Object> metadata = new HashMap<>();
			metadata.put("displayName", displayName);
			metadata.put("imageUrl", icon);
			metadata.put("supportUrl", supportUrl);
			return metadata;
		}
	}
}
