/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact;

import org.gradle.api.Buildable;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.internal.component.local.model.LocalFileDependencyMetadata;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

public class LocalFileDependencyBackedArtifactSet implements ResolvedArtifactSet {
    private final LocalFileDependencyMetadata dependencyMetadata;

    public LocalFileDependencyBackedArtifactSet(LocalFileDependencyMetadata dependencyMetadata) {
        this.dependencyMetadata = dependencyMetadata;
    }

    @Override
    public Set<ResolvedArtifact> getArtifacts() {
        return Collections.emptySet();
    }

    @Override
    public void collectBuildDependencies(Collection<? super Buildable> dest) {
        dest.add(dependencyMetadata.getFiles());
    }

    @Override
    public void visit(ArtifactVisitor visitor) {
        if (visitor.includeFiles()) {
            visitor.visitFiles(dependencyMetadata.getComponentId(), dependencyMetadata.getFiles());
        }
    }
}
