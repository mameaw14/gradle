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

package org.gradle.api.internal.artifacts.ivyservice;

import com.google.common.base.Objects;
import com.google.common.io.Files;
import org.gradle.api.Attribute;
import org.gradle.api.AttributeContainer;
import org.gradle.api.Nullable;
import org.gradle.api.Transformer;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.attributes.ArtifactExtension;
import org.gradle.api.artifacts.attributes.ArtifactName;
import org.gradle.api.artifacts.attributes.ArtifactType;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.internal.DefaultAttributeContainer;
import org.gradle.api.internal.artifacts.DefaultResolvedArtifact;
import org.gradle.api.internal.artifacts.configurations.ResolutionStrategyInternal;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactVisitor;
import org.gradle.internal.Cast;
import org.gradle.internal.Factory;
import org.gradle.internal.component.model.DefaultIvyArtifactName;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ArtifactTransformer {
    private final AttributeContainer requiredAttributes;
    private final ResolutionStrategyInternal resolutionStrategy;

    public ArtifactTransformer(@Nullable AttributeContainer requiredAttributes, ResolutionStrategyInternal resolutionStrategy) {
        this.requiredAttributes = requiredAttributes;
        this.resolutionStrategy = resolutionStrategy;
    }

    public ArtifactVisitor visitor(final ArtifactVisitor visitor) {
        if (requiredAttributes == null || requiredAttributes.isEmpty()) {
            return visitor;
        }
        return new ArtifactVisitor() {
            @Override
            public void visitArtifact(final ResolvedArtifact artifact) {
                if (matchAttributes(artifact.getAttributes())) {
                    visitor.visitArtifact(artifact);
                    return;
                }
                final Transformer<File, File> transform = resolutionStrategy.getTransform(artifact.getAttributes(), requiredAttributes);
                if (transform == null) {
                    return;
                }
                AttributeContainer transformedAttributes = transformAttributes(artifact.getAttributes());
                visitor.visitArtifact(new DefaultResolvedArtifact(artifact.getModuleVersion(), new DefaultIvyArtifactName(artifact.getName(), artifact.getType(), artifact.getExtension(), null, transformedAttributes), artifact.getId(), new Factory<File>() {
                    @Override
                    public File create() {
                        return transform.transform(artifact.getFile());
                    }
                }));
            }

            @Override
            public boolean includeFiles() {
                return visitor.includeFiles();
            }

            @Override
            public void visitFiles(@Nullable ComponentIdentifier componentIdentifier, Iterable<File> files) {
                List<File> transformed = new ArrayList<File>();
                for (File file : files) {
                    AttributeContainer attributeContainer = defaultFileAttributes(file);
                    Transformer<File, File> transform = resolutionStrategy.getTransform(attributeContainer, requiredAttributes);
                    if (transform == null) {
                        continue;
                    }
                    transformed.add(transform.transform(file));
                }
                visitor.visitFiles(componentIdentifier, transformed);
            }

            private AttributeContainer defaultFileAttributes(File file) {
                DefaultAttributeContainer attributes = new DefaultAttributeContainer();
                attributes.attribute(Attribute.of(ArtifactName.class), new ArtifactName(file.getName()));
                String fileExtension = Files.getFileExtension(file.getName());
                if (!"".equals(fileExtension)) {
                    attributes.attribute(Attribute.of(ArtifactType.class), new ArtifactType(fileExtension));
                    attributes.attribute(Attribute.of(ArtifactExtension.class), new ArtifactExtension(fileExtension));
                }
                return attributes;
            }

            private boolean matchAttributes(AttributeContainer artifactAttributes) {
                for (Attribute<?> artifactAttribute : requiredAttributes.keySet()) {
                    Object valueInArtifact = artifactAttributes.getAttribute(artifactAttribute);
                    Object valueInConfiguration = requiredAttributes.getAttribute(artifactAttribute);
                    if (!Objects.equal(valueInArtifact, valueInConfiguration)) {
                        return false;
                    }
                }
                return true;
            }

            private AttributeContainer transformAttributes(AttributeContainer artifactAttributes) { //TODO currently we discard the attributes of input completely
                AttributeContainer transformed = new DefaultAttributeContainer();
                for (Attribute<?> attribute : requiredAttributes.keySet()) {
                    Attribute<Object> castAttribute = Cast.uncheckedCast(attribute);
                    transformed.attribute(castAttribute, requiredAttributes.getAttribute(castAttribute));
                }
                return transformed;
            }
        };
    }
}
