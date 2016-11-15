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

package org.gradle.api.artifacts.transform.internal;

import com.google.common.base.Objects;
import com.google.common.collect.Lists;
import org.gradle.api.Action;
import org.gradle.api.Attribute;
import org.gradle.api.AttributeContainer;
import org.gradle.api.Transformer;
import org.gradle.api.artifacts.transform.ArtifactTransform;
import org.gradle.api.artifacts.transform.ArtifactTransformAttributes;
import org.gradle.api.artifacts.transform.ArtifactTransformException;
import org.gradle.internal.reflect.DirectInstantiator;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

public class ArtifactTransforms {
    private final List<DependencyTransformRegistration> transforms = Lists.newArrayList();

    public Transformer<File, File> getTransform(AttributeContainer from, AttributeContainer to) {
        for (DependencyTransformRegistration transformReg : transforms) {
            if (matchAttributes(transformReg.from, from) && matchAttributes(transformReg.to, to)) {
                return transformReg.getTransformer();
            }
        }
        return null;
    }

    //See also ArtifactTransformer.visitor().matchAttributes()
    private boolean matchAttributes(AttributeContainer requiredAttributes, AttributeContainer artifactAttributes) {
        for (Attribute<?> artifactAttribute : requiredAttributes.keySet()) {
            Object valueInArtifact = requiredAttributes.getAttribute(artifactAttribute);
            Object valueInConfiguration = artifactAttributes.getAttribute(artifactAttribute);
            if (!Objects.equal(valueInArtifact, valueInConfiguration)) {
                return false;
            }
        }
        return true;
    }

    public void registerTransform(Class<? extends ArtifactTransform> type, Action<? super ArtifactTransform> config) {
        ArtifactTransform artifactTransform = DirectInstantiator.INSTANCE.newInstance(type);

        AttributeContainer from = artifactTransform.getInAttributes().getAttributes();
        for (ArtifactTransformAttributes to : artifactTransform.getOutAttributesSet()) {
            DependencyTransformRegistration registration = new DependencyTransformRegistration(from, to.getAttributes(), type, config);
            transforms.add(registration);
        }
    }

    private final class DependencyTransformRegistration {
        final AttributeContainer from;
        final AttributeContainer to;
        final Class<? extends ArtifactTransform> type;
        final Action<? super ArtifactTransform> config;

        public DependencyTransformRegistration(AttributeContainer from, AttributeContainer to, Class<? extends ArtifactTransform> type, Action<? super ArtifactTransform> config) {
            this.from = from;
            this.to = to;
            this.type = type;
            this.config = config;
        }

        public Transformer<File, File> getTransformer() {
            ArtifactTransform artifactTransform = DirectInstantiator.INSTANCE.newInstance(type);
            config.execute(artifactTransform);
            return new DependencyTransformTransformer(artifactTransform, to);
        }
    }

    private static class DependencyTransformTransformer implements Transformer<File, File> {
        private final ArtifactTransform artifactTransform;
        private final AttributeContainer outputAttributes;

        private DependencyTransformTransformer(ArtifactTransform artifactTransform, AttributeContainer outputAttributes) {
            this.artifactTransform = artifactTransform;
            this.outputAttributes = outputAttributes;
        }

        @Override
        public File transform(File input) {
            if (artifactTransform.getOutputDirectory() != null) {
                artifactTransform.getOutputDirectory().mkdirs();
            }
            File output;
            try {
                artifactTransform.transform(input);
                output = artifactTransform.getResult(outputAttributes);
            } catch (Exception e) {
                throw new ArtifactTransformException(input, "", artifactTransform, e);
            }
            if (output == null || output == null) {
                throw new ArtifactTransformException(input, "", artifactTransform, new FileNotFoundException("No output file created"));
            } else if (!output.exists()) {
                throw new ArtifactTransformException(input, "", artifactTransform, new FileNotFoundException("Expected output file '" + output.getPath() + "' was not created"));
            }
            return output;
        }
    }

}
