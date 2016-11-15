/*
 * Copyright 2007-2009 the original author or authors.
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
package org.gradle.api.internal.artifacts.publish;

import org.gradle.api.Attribute;
import org.gradle.api.AttributeContainer;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.internal.DefaultAttributeContainer;
import org.gradle.api.internal.tasks.DefaultTaskDependency;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.internal.Cast;

import java.util.Map;

public abstract class AbstractPublishArtifact implements PublishArtifact {
    private final DefaultTaskDependency taskDependency;
    private final AttributeContainer attributes;

    public AbstractPublishArtifact(Object... tasks) {
        taskDependency = new DefaultTaskDependency();
        taskDependency.add(tasks);
        this.attributes = new DefaultAttributeContainer();
    }

    public TaskDependency getBuildDependencies() {
        return taskDependency;
    }

    public AbstractPublishArtifact builtBy(Object... tasks) {
        taskDependency.add(tasks);
        return this;
    }

    @Override
    public PublishArtifact attribute(String key, String value) {
        attribute(stringAttribute(key), value);
        return this;
    }

    @Override
    public <T> PublishArtifact attribute(Attribute<T> key, T value) {
        attributes.attribute(key, value);
        return this;
    }

    @Override
    public AttributeContainer getAttributes() {
        return attributes;
    }

    @Override
    public <T> T getAttribute(Attribute<T> key) {
        return attributes.getAttribute(key);
    }

    @Override
    public PublishArtifact attributes(Map<?, ?> attributes) {
        for (Map.Entry<?, ?> entry : attributes.entrySet()) {
            Object rawKey = entry.getKey();
            Attribute<Object> key = Cast.uncheckedCast(asAttribute(rawKey));
            Object value = entry.getValue();
            this.attributes.attribute(key, value);
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
        return !attributes.isEmpty();
    }

    private static Attribute<String> stringAttribute(String name) {
        return Attribute.of(name, String.class);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " " + getName() + ":" + getType() + ":" +getExtension()  + ":" + getClassifier();
    }
}
