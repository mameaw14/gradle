/*
 * Copyright 2011 the original author or authors.
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

package org.gradle.api.internal.artifacts.configurations;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.api.Attribute;
import org.gradle.api.AttributeContainer;
import org.gradle.api.AttributesSchema;
import org.gradle.api.DomainObjectSet;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencyResolutionListener;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.artifacts.ExcludeRule;
import org.gradle.api.artifacts.FileCollectionDependency;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.artifacts.PublishArtifactSet;
import org.gradle.api.artifacts.ResolutionStrategy;
import org.gradle.api.artifacts.ResolvableDependencies;
import org.gradle.api.artifacts.ResolvedConfiguration;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.result.ResolutionResult;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.ClosureBackedAction;
import org.gradle.api.internal.CompositeDomainObjectSet;
import org.gradle.api.internal.DefaultDomainObjectSet;
import org.gradle.api.internal.artifacts.AttributeContainerInternal;
import org.gradle.api.internal.artifacts.ConfigurationResolver;
import org.gradle.api.internal.artifacts.DefaultDependencySet;
import org.gradle.api.internal.artifacts.DefaultExcludeRule;
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier;
import org.gradle.api.internal.artifacts.DefaultPublishArtifactSet;
import org.gradle.api.internal.artifacts.DefaultResolverResults;
import org.gradle.api.internal.artifacts.ExcludeRuleNotationConverter;
import org.gradle.api.internal.artifacts.Module;
import org.gradle.api.internal.artifacts.ResolverResults;
import org.gradle.api.internal.artifacts.component.ComponentIdentifierFactory;
import org.gradle.api.internal.artifacts.dsl.dependencies.ProjectFinder;
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.ConfigurationComponentMetaDataBuilder;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.projectresult.ResolvedProjectConfiguration;
import org.gradle.api.internal.file.AbstractFileCollection;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.file.FileCollectionInternal;
import org.gradle.api.internal.file.FileSystemSubset;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.tasks.DefaultTaskDependency;
import org.gradle.api.specs.Spec;
import org.gradle.api.specs.Specs;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.initialization.ProjectAccessListener;
import org.gradle.internal.Cast;
import org.gradle.internal.component.local.model.DefaultLocalComponentMetadata;
import org.gradle.internal.component.model.ComponentResolveMetadata;
import org.gradle.internal.event.ListenerBroadcast;
import org.gradle.internal.event.ListenerManager;
import org.gradle.listener.ClosureBackedMethodInvocationDispatch;
import org.gradle.util.CollectionUtils;
import org.gradle.util.WrapUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.gradle.api.internal.artifacts.configurations.ConfigurationInternal.InternalState.*;

public class DefaultConfiguration extends AbstractFileCollection implements ConfigurationInternal, MutationValidator {

    private final ConfigurationResolver resolver;
    private final ListenerManager listenerManager;
    private final DependencyMetaDataProvider metaDataProvider;
    private final DefaultDependencySet dependencies;
    private final CompositeDomainObjectSet<Dependency> inheritedDependencies;
    private final DefaultDependencySet allDependencies;
    private final List<Action<? super DependencySet>> defaultDependencyActions = new ArrayList<Action<? super DependencySet>>();
    private final DefaultPublishArtifactSet artifacts;
    private final CompositeDomainObjectSet<PublishArtifact> inheritedArtifacts;
    private final DefaultPublishArtifactSet allArtifacts;
    private final ConfigurationResolvableDependencies resolvableDependencies = new ConfigurationResolvableDependencies();
    private final ListenerBroadcast<DependencyResolutionListener> dependencyResolutionListeners;
    private final ProjectAccessListener projectAccessListener;
    private final ProjectFinder projectFinder;
    private final ResolutionStrategyInternal resolutionStrategy;
    private final ConfigurationComponentMetaDataBuilder configurationComponentMetaDataBuilder;
    private final FileCollectionFactory fileCollectionFactory;
    private final ComponentIdentifierFactory componentIdentifierFactory;

    private final Set<MutationValidator> childMutationValidators = Sets.newHashSet();
    private final MutationValidator parentMutationValidator = new MutationValidator() {
        @Override
        public void validateMutation(MutationType type) {
            DefaultConfiguration.this.validateParentMutation(type);
        }
    };

    // These fields are not covered by mutation lock
    private final String path;
    private final String name;

    private boolean visible = true;
    private boolean transitive = true;
    private String format;
    private Set<Configuration> extendsFrom = new LinkedHashSet<Configuration>();
    private String description;
    private ConfigurationsProvider configurationsProvider;
    private Set<ExcludeRule> excludeRules = new LinkedHashSet<ExcludeRule>();

    private final Object observationLock = new Object();
    private InternalState observedState = UNRESOLVED;
    private final Object resolutionLock = new Object();
    private InternalState resolvedState = UNRESOLVED;
    private boolean insideBeforeResolve;

    private ResolverResults cachedResolverResults = new DefaultResolverResults();
    private boolean dependenciesModified;
    private boolean canBeConsumed = true;
    private boolean canBeResolved = true;
    private final DefaultAttributeContainer configurationAttributes = new DefaultAttributeContainer();

    public DefaultConfiguration(String path, String name, ConfigurationsProvider configurationsProvider,
                                ConfigurationResolver resolver, ListenerManager listenerManager,
                                DependencyMetaDataProvider metaDataProvider,
                                ResolutionStrategyInternal resolutionStrategy,
                                ProjectAccessListener projectAccessListener,
                                ProjectFinder projectFinder,
                                ConfigurationComponentMetaDataBuilder configurationComponentMetaDataBuilder,
                                FileCollectionFactory fileCollectionFactory, ComponentIdentifierFactory componentIdentifierFactory) {
        this.path = path;
        this.name = name;
        this.configurationsProvider = configurationsProvider;
        this.resolver = resolver;
        this.listenerManager = listenerManager;
        this.metaDataProvider = metaDataProvider;
        this.resolutionStrategy = resolutionStrategy;
        this.projectAccessListener = projectAccessListener;
        this.projectFinder = projectFinder;
        this.configurationComponentMetaDataBuilder = configurationComponentMetaDataBuilder;
        this.fileCollectionFactory = fileCollectionFactory;
        this.componentIdentifierFactory = componentIdentifierFactory;

        dependencyResolutionListeners = listenerManager.createAnonymousBroadcaster(DependencyResolutionListener.class);

        DefaultDomainObjectSet<Dependency> ownDependencies = new DefaultDomainObjectSet<Dependency>(Dependency.class);
        ownDependencies.beforeChange(validateMutationType(this, MutationType.DEPENDENCIES));

        final String displayName = getDisplayName();
        dependencies = new DefaultDependencySet(displayName + " dependencies", this, ownDependencies);
        inheritedDependencies = CompositeDomainObjectSet.create(Dependency.class, ownDependencies);
        allDependencies = new DefaultDependencySet(displayName + " all dependencies", this, inheritedDependencies);

        DefaultDomainObjectSet<PublishArtifact> ownArtifacts = new DefaultDomainObjectSet<PublishArtifact>(PublishArtifact.class);
        ownArtifacts.beforeChange(validateMutationType(this, MutationType.ARTIFACTS));

        artifacts = new DefaultPublishArtifactSet(displayName + " artifacts", ownArtifacts, fileCollectionFactory);
        inheritedArtifacts = CompositeDomainObjectSet.create(PublishArtifact.class, ownArtifacts);
        allArtifacts = new DefaultPublishArtifactSet(displayName + " all artifacts", inheritedArtifacts, fileCollectionFactory);

        resolutionStrategy.setMutationValidator(this);
    }

    private static Runnable validateMutationType(final MutationValidator mutationValidator, final MutationType type) {
        return new Runnable() {
            @Override
            public void run() {
                mutationValidator.validateMutation(type);
            }
        };
    }

    public String getName() {
        return name;
    }

    public State getState() {
        synchronized (resolutionLock) {
            if (resolvedState == ARTIFACTS_RESOLVED || resolvedState == GRAPH_RESOLVED) {
                if (cachedResolverResults.hasError()) {
                    return State.RESOLVED_WITH_FAILURES;
                } else {
                    return State.RESOLVED;
                }
            } else {
                return State.UNRESOLVED;
            }
        }
    }

    public InternalState getResolvedState() {
        return resolvedState;
    }

    public Module getModule() {
        return metaDataProvider.getModule();
    }

    public boolean isVisible() {
        return visible;
    }

    public Configuration setVisible(boolean visible) {
        validateMutation(MutationType.DEPENDENCIES);
        this.visible = visible;
        return this;
    }

    public Set<Configuration> getExtendsFrom() {
        return Collections.unmodifiableSet(extendsFrom);
    }

    public Configuration setExtendsFrom(Iterable<Configuration> extendsFrom) {
        validateMutation(MutationType.DEPENDENCIES);
        for (Configuration configuration : this.extendsFrom) {
            inheritedArtifacts.removeCollection(configuration.getAllArtifacts());
            inheritedDependencies.removeCollection(configuration.getAllDependencies());
            ((ConfigurationInternal) configuration).removeMutationValidator(parentMutationValidator);
        }
        this.extendsFrom = new HashSet<Configuration>();
        for (Configuration configuration : extendsFrom) {
            extendsFrom(configuration);
        }
        return this;
    }

    public Configuration extendsFrom(Configuration... extendsFrom) {
        validateMutation(MutationType.DEPENDENCIES);
        for (Configuration configuration : extendsFrom) {
            if (configuration.getHierarchy().contains(this)) {
                throw new InvalidUserDataException(String.format(
                    "Cyclic extendsFrom from %s and %s is not allowed. See existing hierarchy: %s", this,
                    configuration, configuration.getHierarchy()));
            }
            if (this.extendsFrom.add(configuration)) {
                inheritedArtifacts.addCollection(configuration.getAllArtifacts());
                inheritedDependencies.addCollection(configuration.getAllDependencies());
                ((ConfigurationInternal) configuration).addMutationValidator(parentMutationValidator);
            }
        }
        return this;
    }

    public boolean isTransitive() {
        return transitive;
    }

    public Configuration setTransitive(boolean transitive) {
        validateMutation(MutationType.DEPENDENCIES);
        this.transitive = transitive;
        return this;
    }

    @Override
    public String getFormat() {
        return format;
    }

    @Override
    public Configuration setFormat(String format) {
        this.format = format;
        return this;
    }

    public String getDescription() {
        return description;
    }

    public Configuration setDescription(String description) {
        this.description = description;
        return this;
    }

    public Set<Configuration> getHierarchy() {
        Set<Configuration> result = WrapUtil.<Configuration>toLinkedSet(this);
        collectSuperConfigs(this, result);
        return result;
    }

    private void collectSuperConfigs(Configuration configuration, Set<Configuration> result) {
        for (Configuration superConfig : configuration.getExtendsFrom()) {
            if (result.contains(superConfig)) {
                result.remove(superConfig);
            }
            result.add(superConfig);
            collectSuperConfigs(superConfig, result);
        }
    }

    @Override
    public Configuration defaultDependencies(Action<? super DependencySet> action) {
        validateMutation(MutationType.DEPENDENCIES);
        this.defaultDependencyActions.add(action);
        return this;
    }

    @Override
    public void triggerWhenEmptyActionsIfNecessary() {
        if (!defaultDependencyActions.isEmpty()) {
            for (Action<? super DependencySet> action : defaultDependencyActions) {
                if (!dependencies.isEmpty()) {
                    break;
                }
                action.execute(dependencies);
            }
        }
        for (Configuration superConfig : extendsFrom) {
            ((ConfigurationInternal) superConfig).triggerWhenEmptyActionsIfNecessary();
        }
    }

    public Set<Configuration> getAll() {
        return configurationsProvider.getAll();
    }

    public Set<File> resolve() {
        return getFiles();
    }

    public Set<File> getFiles() {
        return doGetFiles(Specs.<Dependency>satisfyAll());
    }

    public Set<File> files(Dependency... dependencies) {
        return fileCollection(dependencies).getFiles();
    }

    public Set<File> files(Closure dependencySpecClosure) {
        return fileCollection(dependencySpecClosure).getFiles();
    }

    public Set<File> files(Spec<? super Dependency> dependencySpec) {
        return fileCollection(dependencySpec).getFiles();
    }

    public FileCollection fileCollection(Spec<? super Dependency> dependencySpec) {
        return new ConfigurationFileCollection(dependencySpec);
    }

    public FileCollection fileCollection(Closure dependencySpecClosure) {
        return new ConfigurationFileCollection(dependencySpecClosure);
    }

    public FileCollection fileCollection(Dependency... dependencies) {
        return new ConfigurationFileCollection(WrapUtil.toLinkedSet(dependencies));
    }

    public void markAsObserved(InternalState requestedState) {
        markThisObserved(requestedState);
        markParentsObserved(requestedState);
    }

    private void markThisObserved(InternalState requestedState) {
        synchronized (observationLock) {
            if (observedState.compareTo(requestedState) < 0) {
                observedState = requestedState;
            }
        }
    }

    private void markParentsObserved(InternalState requestedState) {
        for (Configuration configuration : extendsFrom) {
            ((ConfigurationInternal) configuration).markAsObserved(requestedState);
        }
    }

    public ResolvedConfiguration getResolvedConfiguration() {
        resolveToStateOrLater(ARTIFACTS_RESOLVED);
        return cachedResolverResults.getResolvedConfiguration();
    }

    private void resolveToStateOrLater(InternalState requestedState) {
        assertResolvingAllowed();
        synchronized (resolutionLock) {
            if (requestedState == GRAPH_RESOLVED || requestedState == ARTIFACTS_RESOLVED) {
                resolveGraphIfRequired(requestedState);
            }
            if (requestedState == ARTIFACTS_RESOLVED) {
                resolveArtifactsIfRequired();
            }
        }
    }

    private void resolveGraphIfRequired(final InternalState requestedState) {
        if (resolvedState == ARTIFACTS_RESOLVED) {
            if (dependenciesModified) {
                throw new InvalidUserDataException(String.format("Attempted to resolve %s that has been resolved previously.", getDisplayName()));
            }
            return;
        }
        if (resolvedState == GRAPH_RESOLVED) {
            if (!dependenciesModified) {
                return;
            }
            throw new InvalidUserDataException(String.format("Resolved %s again after modification", getDisplayName()));
        }
        if (resolvedState != UNRESOLVED) {
            throw new IllegalStateException("Graph resolution already performed");
        }

        ResolvableDependencies incoming = getIncoming();
        performPreResolveActions(incoming);

        resolver.resolveGraph(this, cachedResolverResults);
        dependenciesModified = false;
        resolvedState = GRAPH_RESOLVED;

        // Mark all affected configurations as observed
        markParentsObserved(requestedState);
        markReferencedProjectConfigurationsObserved(requestedState);

        dependencyResolutionListeners.getSource().afterResolve(incoming);
    }

    private void performPreResolveActions(ResolvableDependencies incoming) {
        DependencyResolutionListener dependencyResolutionListener = dependencyResolutionListeners.getSource();
        insideBeforeResolve = true;
        try {
            dependencyResolutionListener.beforeResolve(incoming);
        } finally {
            insideBeforeResolve = false;
        }
        triggerWhenEmptyActionsIfNecessary();
    }

    private void markReferencedProjectConfigurationsObserved(final InternalState requestedState) {
        for (ResolvedProjectConfiguration projectResult : cachedResolverResults.getResolvedLocalComponents().getResolvedProjectConfigurations()) {
            if (projectResult.getId().getBuild().isCurrentBuild()) {
                ProjectInternal project = projectFinder.getProject(projectResult.getId().getProjectPath());
                ConfigurationInternal targetConfig = (ConfigurationInternal) project.getConfigurations().getByName(projectResult.getTargetConfiguration());
                targetConfig.markAsObserved(requestedState);
            }
        }
    }

    private void resolveArtifactsIfRequired() {
        if (resolvedState == ARTIFACTS_RESOLVED) {
            return;
        }
        if (resolvedState != GRAPH_RESOLVED) {
            throw new IllegalStateException("Cannot resolve artifacts before graph has been resolved.");
        }
        resolver.resolveArtifacts(this, cachedResolverResults);
        resolvedState = ARTIFACTS_RESOLVED;
    }

    public TaskDependency getBuildDependencies() {
        if (resolutionStrategy.resolveGraphToDetermineTaskDependencies()) {
            // Force graph resolution as this is required to calculate build dependencies
            resolveToStateOrLater(GRAPH_RESOLVED);
        }
        assertResolvingAllowed();
        ResolverResults results;
        if (getState() == State.UNRESOLVED) {
            // Traverse graph
            results = new DefaultResolverResults();
            resolver.resolveBuildDependencies(this, results);
        } else {
            // Otherwise, already have a result, so reuse it
            results = cachedResolverResults;
        }
        List<Object> buildDependencies = new ArrayList<Object>();
        results.getResolvedLocalComponents().collectArtifactBuildDependencies(buildDependencies);
        results.getFileDependencies().getFiles().collectBuildDependencies(buildDependencies);
        DefaultTaskDependency taskDependency = new DefaultTaskDependency();
        taskDependency.setValues(buildDependencies);
        return taskDependency;
    }

    private Set<File> doGetFiles(Spec<? super Dependency> dependencySpec) {
        synchronized (resolutionLock) {
            ResolvedConfiguration resolvedConfiguration = getResolvedConfiguration();
            resolvedConfiguration.rethrowFailure();
            return resolvedConfiguration.getFiles(dependencySpec);
        }
    }

    /**
     * {@inheritDoc}
     */
    public TaskDependency getTaskDependencyFromProjectDependency(final boolean useDependedOn, final String taskName) {
        if (useDependedOn) {
            return new TasksFromProjectDependencies(taskName, getAllDependencies(), projectAccessListener);
        } else {
            return new TasksFromDependentProjects(taskName, getName());
        }
    }

    public DependencySet getDependencies() {
        return dependencies;
    }

    public DependencySet getAllDependencies() {
        return allDependencies;
    }

    public PublishArtifactSet getArtifacts() {
        return artifacts;
    }

    public PublishArtifactSet getAllArtifacts() {
        return allArtifacts;
    }

    public Set<ExcludeRule> getExcludeRules() {
        return Collections.unmodifiableSet(excludeRules);
    }

    public void setExcludeRules(Set<ExcludeRule> excludeRules) {
        validateMutation(MutationType.DEPENDENCIES);
        this.excludeRules = excludeRules;
    }

    public DefaultConfiguration exclude(Map<String, String> excludeRuleArgs) {
        validateMutation(MutationType.DEPENDENCIES);
        excludeRules.add(ExcludeRuleNotationConverter.parser().parseNotation(excludeRuleArgs)); //TODO SF try using ExcludeRuleContainer
        return this;
    }

    public String getUploadTaskName() {
        return Configurations.uploadTaskName(getName());
    }

    public String getDisplayName() {
        StringBuilder builder = new StringBuilder();
        builder.append("configuration '");
        builder.append(path);
        builder.append("'");
        return builder.toString();
    }

    public ResolvableDependencies getIncoming() {
        return resolvableDependencies;
    }

    public ConfigurationInternal copy() {
        return createCopy(getDependencies(), false);
    }

    public Configuration copyRecursive() {
        return createCopy(getAllDependencies(), true);
    }

    public Configuration copy(Spec<? super Dependency> dependencySpec) {
        return createCopy(CollectionUtils.filter(getDependencies(), dependencySpec), false);
    }

    public Configuration copyRecursive(Spec<? super Dependency> dependencySpec) {
        return createCopy(CollectionUtils.filter(getAllDependencies(), dependencySpec), true);
    }

    private DefaultConfiguration createCopy(Set<Dependency> dependencies, boolean recursive) {
        DetachedConfigurationsProvider configurationsProvider = new DetachedConfigurationsProvider();
        DefaultConfiguration copiedConfiguration = new DefaultConfiguration(path + "Copy", name + "Copy",
            configurationsProvider, resolver, listenerManager, metaDataProvider, resolutionStrategy.copy(), projectAccessListener, projectFinder, configurationComponentMetaDataBuilder, fileCollectionFactory, componentIdentifierFactory);
        configurationsProvider.setTheOnlyConfiguration(copiedConfiguration);
        // state, cachedResolvedConfiguration, and extendsFrom intentionally not copied - must re-resolve copy
        // copying extendsFrom could mess up dependencies when copy was re-resolved

        copiedConfiguration.visible = visible;
        copiedConfiguration.transitive = transitive;
        copiedConfiguration.description = description;

        copiedConfiguration.defaultDependencyActions.addAll(defaultDependencyActions);

        copiedConfiguration.canBeConsumed = canBeConsumed;
        copiedConfiguration.canBeResolved = canBeResolved;

        copiedConfiguration.getArtifacts().addAll(getAllArtifacts());

        if (hasAttributes()) {
            copiedConfiguration.attributes(configurationAttributes.attributes);
        }

        // todo An ExcludeRule is a value object but we don't enforce immutability for DefaultExcludeRule as strong as we
        // should (we expose the Map). We should provide a better API for ExcludeRule (I don't want to use unmodifiable Map).
        // As soon as DefaultExcludeRule is truly immutable, we don't need to create a new instance of DefaultExcludeRule.
        Set<Configuration> excludeRuleSources = new LinkedHashSet<Configuration>();
        excludeRuleSources.add(this);
        if (recursive) {
            excludeRuleSources.addAll(getHierarchy());
        }

        for (Configuration excludeRuleSource : excludeRuleSources) {
            for (ExcludeRule excludeRule : excludeRuleSource.getExcludeRules()) {
                copiedConfiguration.excludeRules.add(new DefaultExcludeRule(excludeRule.getGroup(), excludeRule.getModule()));
            }
        }

        DomainObjectSet<Dependency> copiedDependencies = copiedConfiguration.getDependencies();
        for (Dependency dependency : dependencies) {
            copiedDependencies.add(dependency.copy());
        }
        return copiedConfiguration;
    }

    public Configuration copy(Closure dependencySpec) {
        return copy(Specs.<Dependency>convertClosureToSpec(dependencySpec));
    }

    public Configuration copyRecursive(Closure dependencySpec) {
        return copyRecursive(Specs.<Dependency>convertClosureToSpec(dependencySpec));
    }

    public ResolutionStrategyInternal getResolutionStrategy() {
        return resolutionStrategy;
    }

    public ComponentResolveMetadata toRootComponentMetaData() {
        Module module = getModule();
        Set<? extends Configuration> configurations = getAll();
        ComponentIdentifier componentIdentifier = componentIdentifierFactory.createComponentIdentifier(module);
        ModuleVersionIdentifier moduleVersionIdentifier = DefaultModuleVersionIdentifier.newId(module);
        ProjectInternal project = projectFinder.findProject(module.getProjectPath());
        AttributesSchema schema = project == null ? null : project.getAttributesSchema();
        DefaultLocalComponentMetadata metaData = new DefaultLocalComponentMetadata(moduleVersionIdentifier, componentIdentifier, module.getStatus(), schema);
        configurationComponentMetaDataBuilder.addConfigurations(metaData, configurations);
        return metaData;
    }

    public String getPath() {
        return path;
    }

    @Override
    public Configuration resolutionStrategy(Closure closure) {
        return resolutionStrategy(ClosureBackedAction.of(closure));
    }

    @Override
    public Configuration resolutionStrategy(Action<? super ResolutionStrategy> action) {
        action.execute(resolutionStrategy);
        return this;
    }

    @Override
    public void addMutationValidator(MutationValidator validator) {
        childMutationValidators.add(validator);
    }

    @Override
    public void removeMutationValidator(MutationValidator validator) {
        childMutationValidators.remove(validator);
    }

    private void validateParentMutation(MutationType type) {
        // Strategy changes in a parent configuration do not affect this configuration, or any of its children, in any way
        if (type == MutationType.STRATEGY) {
            return;
        }

        if (resolvedState == ARTIFACTS_RESOLVED) {
            throw new InvalidUserDataException(String.format("Cannot change %s of parent of %s after it has been resolved", type, getDisplayName()));
        } else if (resolvedState == GRAPH_RESOLVED) {
            if (type == MutationType.DEPENDENCIES) {
                throw new InvalidUserDataException(String.format("Cannot change %s of parent of %s after task dependencies have been resolved", type, getDisplayName()));
            }
        }

        markAsModifiedAndNotifyChildren(type);
    }

    public void validateMutation(MutationType type) {
        if (resolvedState == ARTIFACTS_RESOLVED) {
            // The public result for the configuration has been calculated.
            // It is an error to change anything that would change the dependencies or artifacts
            throw new InvalidUserDataException(String.format("Cannot change %s of %s after it has been resolved.", type, getDisplayName()));
        } else if (resolvedState == GRAPH_RESOLVED) {
            // The task dependencies for the configuration have been calculated using Configuration.getBuildDependencies().
            throw new InvalidUserDataException(String.format("Cannot change %s of %s after task dependencies have been resolved", type, getDisplayName()));
        } else if (observedState == GRAPH_RESOLVED || observedState == ARTIFACTS_RESOLVED) {
            // The configuration has been used in a resolution, and it is an error for build logic to change any dependencies,
            // exclude rules or parent configurations (values that will affect the resolved graph).
            if (type != MutationType.STRATEGY) {
                String extraMessage = insideBeforeResolve ? " Use 'defaultDependencies' instead of 'beforeResolve' to specify default dependencies for a configuration." : "";
                throw new InvalidUserDataException(String.format("Cannot change %s of %s after it has been included in dependency resolution.%s", type, getDisplayName(), extraMessage));
            }
        }

        markAsModifiedAndNotifyChildren(type);
    }

    private void markAsModifiedAndNotifyChildren(MutationType type) {
        // Notify child configurations
        for (MutationValidator validator : childMutationValidators) {
            validator.validateMutation(type);
        }
        if (type != MutationType.STRATEGY) {
            dependenciesModified = true;
        }
    }

    class ConfigurationFileCollection extends AbstractFileCollection {
        private Spec<? super Dependency> dependencySpec;

        private ConfigurationFileCollection(Spec<? super Dependency> dependencySpec) {
            assertResolvingAllowed();
            this.dependencySpec = dependencySpec;
        }

        public ConfigurationFileCollection(Closure dependencySpecClosure) {
            this.dependencySpec = Specs.convertClosureToSpec(dependencySpecClosure);
        }

        public ConfigurationFileCollection(final Set<Dependency> dependencies) {
            this.dependencySpec = new Spec<Dependency>() {
                public boolean isSatisfiedBy(Dependency element) {
                    return dependencies.contains(element);
                }
            };
        }

        @Override
        public TaskDependency getBuildDependencies() {
            return DefaultConfiguration.this.getBuildDependencies();
        }

        public Spec<? super Dependency> getDependencySpec() {
            return dependencySpec;
        }

        public String getDisplayName() {
            return DefaultConfiguration.this + " dependencies";
        }

        public Set<File> getFiles() {
            return doGetFiles(dependencySpec);
        }
    }

    private void assertResolvingAllowed() {
        if (!canBeResolved) {
            throw new IllegalStateException("Resolving configuration '" + name + "' directly is not allowed");
        }
    }

    @Override
    public void registerWatchPoints(FileSystemSubset.Builder builder) {
        for (Dependency dependency : allDependencies) {
            if (dependency instanceof FileCollectionDependency) {
                FileCollection files = ((FileCollectionDependency) dependency).getFiles();
                ((FileCollectionInternal) files).registerWatchPoints(builder);
            }
        }
        super.registerWatchPoints(builder);
    }

    @Override
    public Configuration attribute(String key, String value) {
        attribute(stringAttribute(key), value);
        return this;
    }

    private static void assertAttributeConstraints(Object value, Attribute<?> attribute) {
        if (value == null) {
            throw new IllegalArgumentException("Setting null as an attribute value is not allowed");
        }
        if (!attribute.getType().isAssignableFrom(value.getClass())) {
            throw new IllegalArgumentException("Unexpected type for attribute '" + attribute.getName() + "'. Expected " + attribute.getType().getName() + " but was:" + value.getClass().getName());
        }
    }


    @Override
    public <T> Configuration attribute(Attribute<T> key, T value) {
        configurationAttributes.attribute(key, value);
        return this;
    }

    @Override
    public AttributeContainer getAttributes() {
        return configurationAttributes;
    }

    @Override
    public <T> T getAttribute(Attribute<T> key) {
        return configurationAttributes.getAttribute(key);
    }

    @Override
    public Configuration attributes(Map<?, ?> attributes) {
        for (Map.Entry<?, ?> entry : attributes.entrySet()) {
            Object rawKey = entry.getKey();
            Attribute<Object> key = Cast.uncheckedCast(asAttribute(rawKey));
            Object value = entry.getValue();
            configurationAttributes.attribute(key, value);
        }
        return this;
    }

    private static Attribute<?> asAttribute(Object rawKey) {
        if (rawKey instanceof Attribute) {
            return (Attribute<?>) rawKey;
        }
        return stringAttribute(rawKey.toString());
    }

    @Override
    public boolean hasAttributes() {
        return !configurationAttributes.isEmpty();
    }

    @Override
    public boolean isCanBeConsumed() {
        return canBeConsumed;
    }

    @Override
    public void setCanBeConsumed(boolean allowed) {
        validateMutation(MutationType.ROLE);
        canBeConsumed = allowed;
    }

    @Override
    public boolean isCanBeResolved() {
        return canBeResolved;
    }

    @Override
    public void setCanBeResolved(boolean allowed) {
        validateMutation(MutationType.ROLE);
        canBeResolved = allowed;
    }

    /**
     * Print a formatted representation of a Configuration
     */
    public String dump() {
        StringBuilder reply = new StringBuilder();

        reply.append("\nConfiguration:");
        reply.append("  class='" + this.getClass() + "'");
        reply.append("  name='" + this.getName() + "'");
        reply.append("  hashcode='" + this.hashCode() + "'");

        reply.append("\nLocal Dependencies:");
        if (getDependencies().size() > 0) {
            for (Dependency d : getDependencies()) {
                reply.append("\n   " + d);
            }
        } else {
            reply.append("\n   none");
        }

        reply.append("\nLocal Artifacts:");
        if (getArtifacts().size() > 0) {
            for (PublishArtifact a : getArtifacts()) {
                reply.append("\n   " + a);
            }
        } else {
            reply.append("\n   none");
        }

        reply.append("\nAll Dependencies:");
        if (getAllDependencies().size() > 0) {
            for (Dependency d : getAllDependencies()) {
                reply.append("\n   " + d);
            }
        } else {
            reply.append("\n   none");
        }


        reply.append("\nAll Artifacts:");
        if (getAllArtifacts().size() > 0) {
            for (PublishArtifact a : getAllArtifacts()) {
                reply.append("\n   " + a);
            }
        } else {
            reply.append("\n   none");
        }

        return reply.toString();
    }

    private class ConfigurationResolvableDependencies implements ResolvableDependencies {
        public String getName() {
            return name;
        }

        public String getPath() {
            return path;
        }

        @Override
        public String toString() {
            return "dependencies '" + path + "'";
        }

        public FileCollection getFiles() {
            return DefaultConfiguration.this.fileCollection(Specs.<Dependency>satisfyAll());
        }

        public DependencySet getDependencies() {
            return getAllDependencies();
        }

        public void beforeResolve(Action<? super ResolvableDependencies> action) {
            dependencyResolutionListeners.add("beforeResolve", action);
        }

        public void beforeResolve(Closure action) {
            dependencyResolutionListeners.add(new ClosureBackedMethodInvocationDispatch("beforeResolve", action));
        }

        public void afterResolve(Action<? super ResolvableDependencies> action) {
            dependencyResolutionListeners.add("afterResolve", action);
        }

        public void afterResolve(Closure action) {
            dependencyResolutionListeners.add(new ClosureBackedMethodInvocationDispatch("afterResolve", action));
        }

        public ResolutionResult getResolutionResult() {
            DefaultConfiguration.this.resolveToStateOrLater(ARTIFACTS_RESOLVED);
            return DefaultConfiguration.this.cachedResolverResults.getResolutionResult();
        }

        @Override
        public Set<ResolvedArtifactResult> getArtifacts() {
            resolveToStateOrLater(ARTIFACTS_RESOLVED);
            cachedResolverResults.getResolvedConfiguration().rethrowFailure();
            Set<ResolvedArtifactResult> artifacts = new LinkedHashSet<ResolvedArtifactResult>();
            cachedResolverResults.getArtifactResults().collectArtifacts(artifacts);
            return artifacts;
        }
    }

    private static Attribute<String> stringAttribute(String name) {
        return Attribute.of(name, String.class);
    }

    private class DefaultAttributeContainer implements AttributeContainerInternal {

        private Map<Attribute<?>, Object> attributes;

        private void ensureAttributes() {
            if (this.attributes == null) {
                this.attributes = Maps.newHashMap();
            }
        }

        @Override
        public Set<Attribute<?>> keySet() {
            if (attributes == null) {
                return Collections.emptySet();
            }
            return attributes.keySet();
        }

        @Override
        public <T> AttributeContainer attribute(Attribute<T> key, T value) {
            validateMutation(MutationType.ATTRIBUTES);
            assertAttributeConstraints(value, key);
            ensureAttributes();
            checkInsertionAllowed(key);
            attributes.put(key, value);
            return this;
        }

        private <T> void checkInsertionAllowed(Attribute<T> key) {
            for (Attribute<?> attribute : attributes.keySet()) {
                String name = key.getName();
                if (attribute.getName().equals(name) && attribute.getType() != key.getType()) {
                    throw new IllegalArgumentException("Cannot have two attributes with the same name but different types. "
                        + "This container already has an attribute named '" + name + "' of type '" + attribute.getType().getName()
                        + "' and you are trying to store another one of type '" + key.getType().getName() + "'");
                }
            }
        }

        @Override
        public <T> T getAttribute(Attribute<T> key) {
            return Cast.uncheckedCast(attributes == null ? null : attributes.get(key));
        }

        @Override
        public boolean isEmpty() {
            return attributes == null;
        }

        @Override
        public boolean contains(Attribute<?> key) {
            return attributes != null && attributes.containsKey(key);
        }

        public AttributeContainer asImmutable() {
            if (attributes == null) {
                return EMPTY;
            }
            return new ImmutableAttributes(attributes);
        }
    }

    private static class ImmutableAttributes implements AttributeContainerInternal {

        private static final Comparator<Attribute<?>> ATTRIBUTE_NAME_COMPARATOR = new Comparator<Attribute<?>>() {
            @Override
            public int compare(Attribute<?> o1, Attribute<?> o2) {
                return o1.getName().compareTo(o2.getName());
            }
        };
        private final Map<Attribute<?>, Object> attributes;

        private ImmutableAttributes(Map<Attribute<?>, Object> attributes) {
            this.attributes = attributes;
        }

        @Override
        public Set<Attribute<?>> keySet() {
            return attributes.keySet();
        }

        @Override
        public <T> AttributeContainer attribute(Attribute<T> key, T value) {
            throw new UnsupportedOperationException("Mutation of attributes returned by Configuration#getAttributes() is not allowed");
        }

        @Override
        public <T> T getAttribute(Attribute<T> key) {
            return Cast.uncheckedCast(attributes.get(key));
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public boolean contains(Attribute<?> key) {
            return attributes.containsKey(key);
        }

        @Override
        public AttributeContainer asImmutable() {
            return this;
        }

        @Override
        public String toString() {
            if (attributes != null) {
                TreeMap<Attribute<?>, Object> sorted = new TreeMap<Attribute<?>, Object>(ATTRIBUTE_NAME_COMPARATOR);
                sorted.putAll(attributes);
                return sorted.toString();
            }
            return "{}";
        }
    }

}
