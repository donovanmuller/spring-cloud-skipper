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
package org.springframework.cloud.skipper.shell.command.support;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.cloud.skipper.client.SkipperClientProperties;
import org.springframework.core.annotation.Order;
import org.springframework.shell.ResultHandler;
import org.springframework.shell.jline.DefaultShellApplicationRunner;

/**
 * An {@link ApplicationRunner} implementation that initializes the connecction to the
 * Data Flow Server. Has higher precedence than {@link InteractiveModeApplicationRunner}.
 * @author Eric Bottard
 */
@Order(DefaultShellApplicationRunner.PRECEDENCE - 10)
@SuppressWarnings("unchecked")
public class InitializeConnectionApplicationRunner implements ApplicationRunner {

	private TargetHolder targetHolder;

	private SkipperClientProperties skipperClientProperties;

	private ResultHandler resultHandler;

	/**
	 * Construct a new InitializeConnectionApplicationRunner instance.
	 * @param targetHolder
	 * @param resultHandler
	 * @param skipperClientProperties
	 */
	public InitializeConnectionApplicationRunner(TargetHolder targetHolder,
			ResultHandler resultHandler,
			SkipperClientProperties skipperClientProperties) {
		this.targetHolder = targetHolder;
		this.resultHandler = resultHandler;
		this.skipperClientProperties = skipperClientProperties;
	}

	@Override
	public void run(ApplicationArguments args) throws Exception {
		Target target = new Target(skipperClientProperties.getUri(), skipperClientProperties.getUsername(),
				skipperClientProperties.getPassword(), skipperClientProperties.isSkipSslValidation());

		// Attempt connection (including against default values) but do not crash the shell on
		// error
		try {
			targetHolder.changeTarget(target, null);
		}
		catch (Exception e) {
			resultHandler.handleResult(e);
		}

	}
}
