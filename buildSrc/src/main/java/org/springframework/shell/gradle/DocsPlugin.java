/*
 * Copyright 2022-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.shell.gradle;

import java.io.File;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.asciidoctor.gradle.base.AsciidoctorAttributeProvider;
import org.asciidoctor.gradle.jvm.AbstractAsciidoctorTask;
import org.asciidoctor.gradle.jvm.AsciidoctorJExtension;
import org.asciidoctor.gradle.jvm.AsciidoctorJPlugin;
import org.asciidoctor.gradle.jvm.AsciidoctorTask;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.plugins.JavaLibraryPlugin;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.PluginManager;
import org.gradle.api.publish.tasks.GenerateModuleMetadata;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.Sync;
import org.springframework.util.StringUtils;

/**
 * @author Janne Valkealahti
 */
class DocsPlugin implements Plugin<Project> {

	private static final String ASCIIDOCTORJ_VERSION = "2.4.3";
	private static final String EXTENSIONS_CONFIGURATION_NAME = "asciidoctorExtensions";

	@Override
	public void apply(Project project) {
		PluginManager pluginManager = project.getPluginManager();
		pluginManager.apply(JavaPlugin.class);
		pluginManager.apply(JavaLibraryPlugin.class);
		pluginManager.apply(ManagementConfigurationPlugin.class);
		pluginManager.apply(SpringMavenPlugin.class);
		pluginManager.apply(AsciidoctorJPlugin.class);

		ExtractVersionConstraints dependencyVersions = project.getTasks().create("dependencyVersions",
			ExtractVersionConstraints.class, task -> {
				task.enforcedPlatform(":spring-shell-management");
			});

		project.getPlugins().withType(AsciidoctorJPlugin.class, (asciidoctorPlugin) -> {
			// makeAllWarningsFatal(project);
			upgradeAsciidoctorJVersion(project);
			createAsciidoctorExtensionsConfiguration(project);
			project.getTasks()
				.withType(AbstractAsciidoctorTask.class,
						(asciidoctorTask) -> configureAsciidoctorTask(project, asciidoctorTask, dependencyVersions));
		});
		project.getTasks().withType(GenerateModuleMetadata.class, metadata -> {
			metadata.setEnabled(false);
		});
	}

	private void upgradeAsciidoctorJVersion(Project project) {
		project.getExtensions().getByType(AsciidoctorJExtension.class).setVersion(ASCIIDOCTORJ_VERSION);
	}

	private void createAsciidoctorExtensionsConfiguration(Project project) {
		project.getConfigurations().create(EXTENSIONS_CONFIGURATION_NAME, (configuration) -> {
			configuration.getDependencies()
				.add(project.getDependencies()
					.create("io.spring.asciidoctor.backends:spring-asciidoctor-backends:0.0.5"));
		});
	}

	private void configureAsciidoctorTask(Project project, AbstractAsciidoctorTask asciidoctorTask, ExtractVersionConstraints dependencyVersions) {
		asciidoctorTask.configurations(EXTENSIONS_CONFIGURATION_NAME);
		configureCommonAttributes(project, asciidoctorTask, dependencyVersions);
		configureOptions(asciidoctorTask);
		asciidoctorTask.baseDirFollowsSourceDir();
		createSyncDocumentationSourceTask(project, asciidoctorTask, dependencyVersions);
		if (asciidoctorTask instanceof AsciidoctorTask task) {
			task.outputOptions((outputOptions) -> outputOptions.backends("spring-html"));
		}
	}

	private void configureOptions(AbstractAsciidoctorTask asciidoctorTask) {
		asciidoctorTask.options(Collections.singletonMap("doctype", "book"));
	}

	private Sync createSyncDocumentationSourceTask(Project project, AbstractAsciidoctorTask asciidoctorTask, ExtractVersionConstraints dependencyVersions) {
		Sync syncDocumentationSource = project.getTasks()
			.create("syncDocumentationSourceFor" + StringUtils.capitalize(asciidoctorTask.getName()), Sync.class);
		syncDocumentationSource.preserve(filter -> {
			filter.include("**/*");
		});
		File syncedSource = new File(project.getBuildDir(), "docs/src/" + asciidoctorTask.getName());
		syncDocumentationSource.setDestinationDir(syncedSource);
		syncDocumentationSource.from("src/main/");
		asciidoctorTask.dependsOn(syncDocumentationSource);
		asciidoctorTask.dependsOn(dependencyVersions);
		Sync snippetsResources = createSnippetsResourcesTask(project);
		asciidoctorTask.dependsOn(snippetsResources);
		asciidoctorTask.getInputs()
			.dir(syncedSource)
			.withPathSensitivity(PathSensitivity.RELATIVE)
			.withPropertyName("synced source");
		asciidoctorTask.setSourceDir(project.relativePath(new File(syncedSource, "asciidoc/")));
		return syncDocumentationSource;
	}

	private Sync createSnippetsResourcesTask(Project project) {
		Sync sync = project.getTasks().create("snippetResources", Sync.class, s -> {
			s.from(new File(project.getRootProject().getRootDir(), "spring-shell-docs/src/test/java/org/springframework/shell"), spec -> {
				spec.include("docs/*");
			});
			s.preserve(filter -> {
				filter.include("**/*");
			});
			File destination = new File(project.getBuildDir(), "docs/src/asciidoctor/asciidoc");
			s.into(destination);
		});
		return sync;
	}

	private void configureCommonAttributes(Project project, AbstractAsciidoctorTask asciidoctorTask,
			ExtractVersionConstraints dependencyVersions) {
		asciidoctorTask.doFirst(new Action<Task>() {

			@Override
			public void execute(Task arg0) {
				asciidoctorTask.getAttributeProviders().add(new AsciidoctorAttributeProvider() {
					@Override
					public Map<String, Object> getAttributes() {
						Map<String, String> versionConstraints = dependencyVersions.getVersionConstraints();
						Map<String, Object> attrs = new HashMap<>();
						attrs.put("spring-version", versionConstraints.get("org.springframework:spring-core"));
						attrs.put("spring-boot-version", versionConstraints.get("org.springframework.boot:spring-boot"));
						return attrs;
					}
				});
			}
		});

		Map<String, Object> attributes = new HashMap<>();
		attributes.put("toc", "left");
		attributes.put("icons", "font");
		attributes.put("idprefix", "");
		attributes.put("idseparator", "-");
		attributes.put("docinfo", "shared");
		attributes.put("sectanchors", "");
		attributes.put("sectnums", "");
		attributes.put("today-year", LocalDate.now().getYear());
		attributes.put("snippets", "docs");

		asciidoctorTask.getAttributeProviders().add(new AsciidoctorAttributeProvider() {
			@Override
			public Map<String, Object> getAttributes() {
				Object version = project.getVersion();
				Map<String, Object> attrs = new HashMap<>();
				if (version != null && version.toString() != Project.DEFAULT_VERSION) {
					attrs.put("project-version", version);
				}
				return attrs;
			}
		});
		asciidoctorTask.attributes(attributes);
	}
}
