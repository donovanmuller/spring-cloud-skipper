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
package org.springframework.cloud.skipper.server.service;

import java.util.Map;
import java.util.StringJoiner;
import java.util.StringTokenizer;

import io.codearte.props2yaml.Props2YAML;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import org.springframework.cloud.servicebroker.model.CreateServiceInstanceRequest;
import org.springframework.cloud.servicebroker.model.CreateServiceInstanceResponse;
import org.springframework.cloud.servicebroker.model.DeleteServiceInstanceRequest;
import org.springframework.cloud.servicebroker.model.DeleteServiceInstanceResponse;
import org.springframework.cloud.servicebroker.model.GetLastServiceOperationRequest;
import org.springframework.cloud.servicebroker.model.GetLastServiceOperationResponse;
import org.springframework.cloud.servicebroker.model.OperationState;
import org.springframework.cloud.servicebroker.model.UpdateServiceInstanceRequest;
import org.springframework.cloud.servicebroker.model.UpdateServiceInstanceResponse;
import org.springframework.cloud.servicebroker.service.ServiceInstanceService;
import org.springframework.cloud.skipper.ReleaseNotFoundException;
import org.springframework.cloud.skipper.domain.ConfigValues;
import org.springframework.cloud.skipper.domain.Info;
import org.springframework.cloud.skipper.domain.InstallProperties;
import org.springframework.cloud.skipper.domain.InstallRequest;
import org.springframework.cloud.skipper.domain.PackageIdentifier;
import org.springframework.cloud.skipper.domain.Release;
import org.springframework.cloud.skipper.domain.StatusCode;

/**
 * @author Donovan Muller
 */
public class ServiceBrokerInstanceService implements ServiceInstanceService {

	private static final Logger log = LoggerFactory.getLogger(ServiceBrokerInstanceService.class);

	private final ReleaseService releaseService;

	public ServiceBrokerInstanceService(ReleaseService releaseService) {
		this.releaseService = releaseService;
	}

	@Override
	public CreateServiceInstanceResponse createServiceInstance(
			CreateServiceInstanceRequest createServiceInstanceRequest) {
		log.debug("Creating service instance: {}", createServiceInstanceRequest);

		try {
			StatusCode statusCode = releaseService
					.status(createServiceInstanceRequest.getServiceInstanceId())
					.getStatus().getStatusCode();
			if (statusCode.equals(StatusCode.DEPLOYED)
					|| statusCode.equals(StatusCode.UNKNOWN)) {
				CreateServiceInstanceResponse createServiceInstanceResponse = new CreateServiceInstanceResponse();
				return createServiceInstanceResponse
						.withAsync(false)
						.withOperation("provisioning")
						.withInstanceExisted(true);
			}
		}
		catch (ReleaseNotFoundException e) {
			log.debug("Release doesn't exist. Deploying...");
		}

		deploy(createServiceInstanceRequest);

		CreateServiceInstanceResponse createServiceInstanceResponse = new CreateServiceInstanceResponse();
		return createServiceInstanceResponse
				.withAsync(true)
				.withOperation("provisioning")
				.withInstanceExisted(false);
	}

	@Override
	public GetLastServiceOperationResponse getLastOperation(
			GetLastServiceOperationRequest getLastServiceOperationRequest) {
		log.debug("Getting last operation: {}", getLastServiceOperationRequest);

		OperationState state = OperationState.FAILED;
		GetLastServiceOperationResponse getLastServiceOperationResponse = new GetLastServiceOperationResponse()
				.withOperationState(state);
		try {
			Info status = releaseService.status(getLastServiceOperationRequest.getServiceInstanceId());

			switch (status.getStatus().getStatusCode()) {
			case FAILED:
				state = OperationState.FAILED;
				break;
			case DELETED:
				state = OperationState.SUCCEEDED;
				break;
			case UNKNOWN:
				state = OperationState.IN_PROGRESS;
				break;
			case DEPLOYED:
				state = OperationState.SUCCEEDED;
				break;
			default:
				state = OperationState.FAILED;
			}

			return getLastServiceOperationResponse
					.withOperationState(state)
					.withDescription(status.getDescription());
		}
		catch (ReleaseNotFoundException e) {
			log.debug("Could not find release: {}", getLastServiceOperationRequest.getServiceInstanceId());
		}

		return getLastServiceOperationResponse;
	}

	@Override
	public DeleteServiceInstanceResponse deleteServiceInstance(
			DeleteServiceInstanceRequest deleteServiceInstanceRequest) {
		log.debug("Deleting release: {}", deleteServiceInstanceRequest);

		try {
			Release deletedRelease = releaseService.delete(deleteServiceInstanceRequest.getServiceInstanceId());
			log.debug("Deleted release: {}", deletedRelease);
			return new DeleteServiceInstanceResponse().withAsync(true);
		}
		catch (ReleaseNotFoundException e) {
			log.warn("Could not find release: {}",
					deleteServiceInstanceRequest.getServiceInstanceId());
			return new DeleteServiceInstanceResponse().withAsync(true);
		}
	}

	@Override
	public UpdateServiceInstanceResponse updateServiceInstance(
			UpdateServiceInstanceRequest updateServiceInstanceRequest) {
		throw new UnsupportedOperationException(
				"Updating service instance not supported");
	}

	private void deploy(CreateServiceInstanceRequest createServiceInstanceRequest) {
		StringJoiner installProperties = new StringJoiner(",");
		createServiceInstanceRequest.getParameters().forEach((key, value) -> {
			if (!key.equals("version") &&
					!key.equals("platform") &&
					!key.equals("deploymentProperties")) {
				installProperties.add(String.format("%s=%s: %s", "spec.applicationProperties",
						key.replaceAll("-", "."),
						value));
			}
		});

		StringJoiner deploymentProperties = new StringJoiner(",");
		String deploymentPropertiesParameter = (String) createServiceInstanceRequest
				.getParameters().get("deploymentProperties");
		if (StringUtils.isNotBlank(deploymentPropertiesParameter)) {
			for (String deploymentProperty : StringUtils.split(deploymentPropertiesParameter,
					",")) {
				deploymentProperties.add(StringUtils.prependIfMissing(deploymentProperty,
						"spec.deploymentProperties="));
			}
		}

		StringJoiner properties = new StringJoiner(",");
		properties.merge(installProperties);
		properties.merge(deploymentProperties);

		log.debug("Using application install properties: {}", properties.toString());
		Map<String, Object> parameters = createServiceInstanceRequest.getParameters();
		releaseService.install(getInstallRequest(
				createServiceInstanceRequest.getPlanId(),
				(String) parameters.get("version"),
				properties.toString(),
				createServiceInstanceRequest.getServiceInstanceId(),
				(String) createServiceInstanceRequest.getParameters().get("platform")));
	}

	/**
	 * See org.springframework.cloud.skipper.shell.command.SkipperCommands#getInstallRequest
	 */
	private InstallRequest getInstallRequest(String packageName, String packageVersion,
			String properties, String releaseName, String platformName) {
		InstallProperties installProperties = getInstallProperties(releaseName,
				platformName, properties);
		InstallRequest installRequest = new InstallRequest();
		installRequest.setInstallProperties(installProperties);
		PackageIdentifier packageIdentifier = new PackageIdentifier();
		packageIdentifier.setPackageName(packageName);
		packageIdentifier.setPackageVersion(packageVersion);
		installRequest.setPackageIdentifier(packageIdentifier);
		return installRequest;
	}

	/**
	 * See
	 * org.springframework.cloud.skipper.shell.command.SkipperCommands#getInstallProperties
	 */
	private InstallProperties getInstallProperties(String releaseName,
			String platformName, String propertiesToOverride) {
		InstallProperties installProperties = new InstallProperties();
		if (org.springframework.util.StringUtils.hasText(releaseName)) {
			installProperties.setReleaseName(releaseName);
		}
		installProperties.setPlatformName(platformName);
		String configValuesYML = convertToYaml(propertiesToOverride);
		if (StringUtils.isNotBlank(configValuesYML)) {
			ConfigValues configValues = new ConfigValues();
			configValues.setRaw(configValuesYML);
			installProperties.setConfigValues(configValues);
		}
		return installProperties;
	}

	private static final String APPLICATION_PROPERTIES_PREFIX = "spec.applicationProperties.";

	private static final String DEPLOYMENT_PROPERTIES_PREFIX = "spec.deploymentProperties.";

	private static final String SPEC_APPLICATION_PROPERTIES_REPLACEMENT = "REPLACE_APPLICATION_PROPERTIES";

	private static final String SPEC_DEPLOYMENT_PROPERTIES_REPLACEMENT = "REPLACE_DEPLOYMENT_PROPERTIES";

	private static final String DOT_CHAR = "\\.";

	private static final String DOT_CHAR_REPLACEMENT = "------";

	private static final String DOT_SPEC_STRING = ".spec.";

	private static final String SPEC_STRING = "spec.";

	/**
	 * See org.springframework.cloud.skipper.shell.command.support.YmlUtils#convertToYaml
	 */
	private static String convertToYaml(String propertiesAsCsvString) {
		String configValuesYML;
		StringTokenizer tokenizer = new StringTokenizer(propertiesAsCsvString, ",");
		StringBuilder sb = new StringBuilder();
		while (tokenizer.hasMoreElements()) {
			String value = tokenizer.nextToken();
			if (value.contains(DOT_SPEC_STRING)) {
				int i = value.indexOf(DOT_SPEC_STRING) + 1;
				String trimmed = value.substring(i);
				String prefix = value.substring(0, i);
				String modifiedString = modifyString(trimmed);
				value = new String(prefix + modifiedString);
			}
			else if (value.contains(SPEC_STRING)) {
				value = modifyString(value);
			}
			sb.append(value);
			sb.append("\n");
		}
		String ymlString = Props2YAML.fromContent(sb.toString()).convert();
		// Revert original property keys' dots
		ymlString = ymlString.replaceAll(DOT_CHAR_REPLACEMENT, DOT_CHAR);
		Yaml yaml = new Yaml();
		configValuesYML = yaml.dump(yaml.load(ymlString));
		return configValuesYML;
	}

	private static String modifyString(String property) {
		String propertyValue = property.replaceAll(APPLICATION_PROPERTIES_PREFIX,
				SPEC_APPLICATION_PROPERTIES_REPLACEMENT);
		propertyValue = propertyValue.replaceAll(DEPLOYMENT_PROPERTIES_PREFIX,
				SPEC_DEPLOYMENT_PROPERTIES_REPLACEMENT);
		// Replace the original property keys' dots to avoid type errors when using Props2YML
		propertyValue = propertyValue.replaceAll(DOT_CHAR, DOT_CHAR_REPLACEMENT);
		propertyValue = propertyValue.replaceAll(SPEC_APPLICATION_PROPERTIES_REPLACEMENT,
				APPLICATION_PROPERTIES_PREFIX);
		propertyValue = propertyValue.replaceAll(SPEC_DEPLOYMENT_PROPERTIES_REPLACEMENT,
				DEPLOYMENT_PROPERTIES_PREFIX);
		return propertyValue;
	}
}
