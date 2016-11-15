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
package org.gradle.api.internal.artifacts.ivyservice;

import com.google.common.collect.Sets;
import org.gradle.api.Nullable;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.FileCollectionDependency;
import org.gradle.api.artifacts.LenientConfiguration;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ResolveException;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.artifacts.UnresolvedDependency;
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.component.Artifact;
import org.gradle.api.internal.artifacts.DependencyGraphNodeResult;
import org.gradle.api.internal.artifacts.configurations.ConfigurationInternal;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactResults;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactVisitor;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.FileDependencyResults;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedArtifactResults;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedArtifactSet;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.oldresult.TransientConfigurationResults;
import org.gradle.api.internal.artifacts.result.DefaultResolvedArtifactResult;
import org.gradle.api.specs.Spec;
import org.gradle.api.specs.Specs;
import org.gradle.internal.Factory;
import org.gradle.internal.component.local.model.ComponentFileArtifactIdentifier;
import org.gradle.internal.component.local.model.OpaqueComponentArtifactIdentifier;
import org.gradle.internal.graph.CachingDirectedGraphWalker;
import org.gradle.internal.graph.DirectedGraphWithEdgeValues;
import org.gradle.util.CollectionUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DefaultLenientConfiguration implements LenientConfiguration, ArtifactResults {
    private final CacheLockingManager cacheLockingManager;
    private final ConfigurationInternal configuration;
    private final Set<UnresolvedDependency> unresolvedDependencies;
    private final ResolvedArtifactResults artifactResults;
    private final FileDependencyResults fileDependencyResults;
    private final Factory<TransientConfigurationResults> transientConfigurationResultsFactory;
    private final ArtifactTransformer artifactTransformer;

    public DefaultLenientConfiguration(ConfigurationInternal configuration, CacheLockingManager cacheLockingManager, Set<UnresolvedDependency> unresolvedDependencies,
                                       ResolvedArtifactResults artifactResults, FileDependencyResults fileDependencyResults, Factory<TransientConfigurationResults> transientConfigurationResultsLoader, ArtifactTransformer artifactTransformer) {
        this.configuration = configuration;
        this.cacheLockingManager = cacheLockingManager;
        this.unresolvedDependencies = unresolvedDependencies;
        this.artifactResults = artifactResults;
        this.fileDependencyResults = fileDependencyResults;
        this.transientConfigurationResultsFactory = transientConfigurationResultsLoader;
        this.artifactTransformer = artifactTransformer;
    }

    public boolean hasError() {
        return unresolvedDependencies.size() > 0;
    }

    public Set<UnresolvedDependency> getUnresolvedModuleDependencies() {
        return unresolvedDependencies;
    }

    public void rethrowFailure() throws ResolveException {
        if (hasError()) {
            List<Throwable> failures = new ArrayList<Throwable>();
            for (UnresolvedDependency unresolvedDependency : unresolvedDependencies) {
                failures.add(unresolvedDependency.getProblem());
            }
            throw new ResolveException(configuration.toString(), failures);
        }
    }

    public Set<ResolvedArtifact> getResolvedArtifacts() throws ResolveException {
        return artifactResults.getArtifacts().getArtifacts();
    }

    private TransientConfigurationResults loadTransientGraphResults() {
        return transientConfigurationResultsFactory.create();
    }

    public Set<ResolvedDependency> getFirstLevelModuleDependencies(Spec<? super Dependency> dependencySpec) {
        Set<ResolvedDependency> matches = new LinkedHashSet<ResolvedDependency>();
        for (DependencyGraphNodeResult node : getFirstLevelNodes(dependencySpec)) {
            matches.add(node.getPublicView());
        }
        return matches;
    }

    private Set<DependencyGraphNodeResult> getFirstLevelNodes(Spec<? super Dependency> dependencySpec) {
        Set<DependencyGraphNodeResult> matches = new LinkedHashSet<DependencyGraphNodeResult>();
        for (Map.Entry<ModuleDependency, DependencyGraphNodeResult> entry : loadTransientGraphResults().getFirstLevelDependencies().entrySet()) {
            if (dependencySpec.isSatisfiedBy(entry.getKey())) {
                matches.add(entry.getValue());
            }
        }
        return matches;
    }

    public Set<ResolvedDependency> getAllModuleDependencies() {
        Set<ResolvedDependency> resolvedElements = new LinkedHashSet<ResolvedDependency>();
        Deque<ResolvedDependency> workQueue = new LinkedList<ResolvedDependency>();
        workQueue.addAll(loadTransientGraphResults().getRootNode().getPublicView().getChildren());
        while (!workQueue.isEmpty()) {
            ResolvedDependency item = workQueue.removeFirst();
            if (resolvedElements.add(item)) {
                final Set<ResolvedDependency> children = item.getChildren();
                if (children != null) {
                    workQueue.addAll(children);
                }
            }
        }
        return resolvedElements;
    }

    @Override
    public Set<File> getFiles() {
        return getFiles(Specs.<Dependency>satisfyAll());
    }

    /**
     * Recursive but excludes unsuccessfully resolved artifacts.
     */
    public Set<File> getFiles(Spec<? super Dependency> dependencySpec) {
        Set<File> files = Sets.newLinkedHashSet();
        FilesAndArtifactCollectingVisitor visitor = new FilesAndArtifactCollectingVisitor(files);
        visitArtifacts(dependencySpec, visitor);
        files.addAll(getFiles(filterUnresolved(visitor.artifacts)));
        return files;
    }

    /**
     * Collects files reachable from first level dependencies that satisfy the given spec. Fails when any file cannot be resolved
     */
    public void collectFiles(Spec<? super Dependency> dependencySpec, Collection<File> dest) {
        ResolvedFilesCollectingVisitor visitor = new ResolvedFilesCollectingVisitor(dest);
        try {
            visitArtifacts(dependencySpec, visitor);
            // The visitor adds file dependencies directly to the destination collection however defers adding the artifacts. This is to ensure a fixed order regardless of whether the first level dependencies are filtered or not
            // File dependencies and artifacts are currently treated separately as a migration step
            visitor.addArtifacts();
        } catch (Throwable t) {
            visitor.failures.add(t);
        }
        if (!visitor.failures.isEmpty()) {
            throw new ArtifactResolveException("files", configuration.getPath(), configuration.getDisplayName(), visitor.failures);
        }
    }

    /**
     * Collects all resolved artifacts. Fails when any artifact cannot be resolved.
     */
    public void collectArtifacts(Collection<? super ResolvedArtifactResult> dest) {
        ResolvedArtifactCollectingVisitor visitor = new ResolvedArtifactCollectingVisitor(dest);
        try {
            visitArtifacts(Specs.<Dependency>satisfyAll(), visitor);
        } catch (Throwable t) {
            visitor.failures.add(t);
        }
        if (!visitor.failures.isEmpty()) {
            throw new ArtifactResolveException("artifacts", configuration.getPath(), configuration.getDisplayName(), visitor.failures);
        }
    }

    @Override
    public Set<ResolvedArtifact> getArtifacts() {
        return getArtifacts(Specs.<Dependency>satisfyAll());
    }

    /**
     * Recursive but excludes unsuccessfully resolved artifacts.
     */
    public Set<ResolvedArtifact> getArtifacts(Spec<? super Dependency> dependencySpec) {
        ArtifactCollectingVisitor visitor = new ArtifactCollectingVisitor();
        visitArtifacts(dependencySpec, visitor);
        return filterUnresolved(visitor.artifacts);
    }

    private Set<ResolvedArtifact> filterUnresolved(final Set<ResolvedArtifact> artifacts) {
        return cacheLockingManager.useCache("retrieve artifacts from " + configuration, new Factory<Set<ResolvedArtifact>>() {
            public Set<ResolvedArtifact> create() {
                return CollectionUtils.filter(artifacts, new IgnoreMissingExternalArtifacts());
            }
        });
    }

    private Set<File> getFiles(final Set<ResolvedArtifact> artifacts) {
        final Set<File> files = new LinkedHashSet<File>();
        cacheLockingManager.useCache("resolve files from " + configuration, new Runnable() {
            public void run() {
                for (ResolvedArtifact artifact : artifacts) {
                    File depFile = artifact.getFile();
                    if (depFile != null) {
                        files.add(depFile);
                    }
                }
            }
        });
        return files;
    }

    /**
     * Recursive, includes unsuccessfully resolved artifacts
     *
     * @param dependencySpec dependency spec
     */
    private void visitArtifacts(Spec<? super Dependency> dependencySpec, ArtifactVisitor visitor) {
        visitor = artifactTransformer.visitor(visitor);

        //this is not very nice might be good enough until we get rid of ResolvedConfiguration and friends
        //avoid traversing the graph causing the full ResolvedDependency graph to be loaded for the most typical scenario
        if (dependencySpec == Specs.SATISFIES_ALL) {
            if (visitor.includeFiles()) {
                fileDependencyResults.getFiles().visit(visitor);
            }
            artifactResults.getArtifacts().visit(visitor);
            return;
        }

        if (visitor.includeFiles()) {
            for (Map.Entry<FileCollectionDependency, ResolvedArtifactSet> entry: fileDependencyResults.getFirstLevelFiles().entrySet()) {
                if (dependencySpec.isSatisfiedBy(entry.getKey())) {
                    entry.getValue().visit(visitor);
                }
            }
        }

        CachingDirectedGraphWalker<DependencyGraphNodeResult, ResolvedArtifact> walker = new CachingDirectedGraphWalker<DependencyGraphNodeResult, ResolvedArtifact>(new ResolvedDependencyArtifactsGraph(visitor));

        for (DependencyGraphNodeResult node : getFirstLevelNodes(dependencySpec)) {
            node.getArtifactsForIncomingEdge(loadTransientGraphResults().getRootNode()).visit(visitor);
            walker.add(node);
        }
        walker.findValues();
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    public Set<ResolvedDependency> getFirstLevelModuleDependencies() {
        return loadTransientGraphResults().getRootNode().getPublicView().getChildren();
    }

    private static class Visitor implements ArtifactVisitor {
        @Override
        public void visitArtifact(ResolvedArtifact artifact) {
        }

        @Override
        public boolean includeFiles() {
            return false;
        }

        @Override
        public void visitFiles(@Nullable ComponentIdentifier componentIdentifier, Iterable<File> files) {
            throw new UnsupportedOperationException();
        }
    }

    private static class ResolvedFilesCollectingVisitor extends Visitor {
        private final Collection<File> files;
        private final List<Throwable> failures = new ArrayList<Throwable>();
        private final Set<ResolvedArtifact> artifacts = new LinkedHashSet<ResolvedArtifact>();

        ResolvedFilesCollectingVisitor(Collection<File> files) {
            this.files = files;
        }

        @Override
        public void visitArtifact(ResolvedArtifact artifact) {
            // Defer adding the artifacts until after all the file dependencies have been visited
            this.artifacts.add(artifact);
        }

        @Override
        public boolean includeFiles() {
            return true;
        }

        @Override
        public void visitFiles(@Nullable ComponentIdentifier componentIdentifier, Iterable<File> files) {
            try {
                for (File file : files) {
                    this.files.add(file);
                }
            } catch (Throwable t) {
                failures.add(t);
            }
        }

        public void addArtifacts() {
            for (ResolvedArtifact artifact : artifacts) {
                try {
                    this.files.add(artifact.getFile());
                } catch (Throwable t) {
                    failures.add(t);
                }
            }
        }
    }

    private static class ResolvedArtifactCollectingVisitor extends Visitor {
        private final Collection<? super ResolvedArtifactResult> artifacts;
        private final Set<ComponentArtifactIdentifier> seenArtifacts = new HashSet<ComponentArtifactIdentifier>();
        private final Set<File> seenFiles = new HashSet<File>();
        private final List<Throwable> failures = new ArrayList<Throwable>();

        ResolvedArtifactCollectingVisitor(Collection<? super ResolvedArtifactResult> artifacts) {
            this.artifacts = artifacts;
        }

        @Override
        public void visitArtifact(ResolvedArtifact artifact) {
            try {
                if (seenArtifacts.add(artifact.getId())) {
                    // Trigger download of file, if required
                    File file = artifact.getFile();
                    this.artifacts.add(new DefaultResolvedArtifactResult(artifact.getId(), Artifact.class, file));
                }
            } catch (Throwable t) {
                failures.add(t);
            }
        }

        @Override
        public boolean includeFiles() {
            return true;
        }

        @Override
        public void visitFiles(@Nullable ComponentIdentifier componentIdentifier, Iterable<File> files) {
            try {
                for (File file : files) {
                    if (seenFiles.add(file)) {
                        ComponentArtifactIdentifier artifactIdentifier;
                        if (componentIdentifier == null) {
                            artifactIdentifier = new OpaqueComponentArtifactIdentifier(file);
                        } else {
                            artifactIdentifier = new ComponentFileArtifactIdentifier(componentIdentifier, file.getName());
                        }
                        artifacts.add(new DefaultResolvedArtifactResult(artifactIdentifier, Artifact.class, file));
                    }
                }
            } catch (Throwable t) {
                failures.add(t);
            }
        }
    }

    private static class ArtifactCollectingVisitor extends Visitor {
        final Set<ResolvedArtifact> artifacts = Sets.newLinkedHashSet();

        @Override
        public void visitArtifact(ResolvedArtifact artifact) {
            this.artifacts.add(artifact);
        }
    }

    private static class FilesAndArtifactCollectingVisitor extends ArtifactCollectingVisitor {
        final Collection<File> files;

        FilesAndArtifactCollectingVisitor(Collection<File> files) {
            this.files = files;
        }

        @Override
        public boolean includeFiles() {
            return true;
        }

        @Override
        public void visitFiles(@Nullable ComponentIdentifier componentIdentifier, Iterable<File> files) {
            CollectionUtils.addAll(this.files, files);
        }
    }

    private static class ArtifactResolveException extends ResolveException {
        private final String type;
        private final String displayName;

        public ArtifactResolveException(String type, String path, String displayName, List<Throwable> failures) {
            super(path, failures);
            this.type = type;
            this.displayName = displayName;
        }

        // Need to override as error message is hardcoded in constructor of public type ResolveException
        @Override
        public String getMessage() {
            return String.format("Could not resolve all %s for %s.", type, displayName);
        }
    }

    private class ResolvedDependencyArtifactsGraph implements DirectedGraphWithEdgeValues<DependencyGraphNodeResult, ResolvedArtifact> {
        private final ArtifactVisitor artifactsVisitor;

        ResolvedDependencyArtifactsGraph(ArtifactVisitor artifactsVisitor) {
            this.artifactsVisitor = artifactsVisitor;
        }

        @Override
        public void getNodeValues(DependencyGraphNodeResult node, Collection<? super ResolvedArtifact> values, Collection<? super DependencyGraphNodeResult> connectedNodes) {
            connectedNodes.addAll(node.getOutgoingEdges());
            if (artifactsVisitor.includeFiles()) {
                fileDependencyResults.getFiles(node.getNodeId()).visit(artifactsVisitor);
            }
        }

        @Override
        public void getEdgeValues(DependencyGraphNodeResult from, DependencyGraphNodeResult to,
                                  Collection<ResolvedArtifact> values) {
            to.getArtifactsForIncomingEdge(from).visit(artifactsVisitor);
        }
    }

    private static class IgnoreMissingExternalArtifacts implements Spec<ResolvedArtifact> {
        public boolean isSatisfiedBy(ResolvedArtifact element) {
            if (isExternalModuleArtifact(element)) {
                try {
                    element.getFile();
                } catch (org.gradle.internal.resolve.ArtifactResolveException e) {
                    return false;
                }
            }
            return true;
        }

        boolean isExternalModuleArtifact(ResolvedArtifact element) {
            return element.getId().getComponentIdentifier() instanceof ModuleComponentIdentifier;
        }
    }
}
