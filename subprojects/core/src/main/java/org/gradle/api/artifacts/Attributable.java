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

package org.gradle.api.artifacts;

import org.gradle.api.Attribute;
import org.gradle.api.AttributeContainer;
import org.gradle.api.Incubating;

import java.util.Map;

/**
 * Domain objects that can be enriched with attributes for typing them
 * in multiple dimensions implement this interface.
 *
 * @param <ATTRIBUTED> the type that is enriched with attributes (used to provide fluent API)
 */
public interface Attributable<ATTRIBUTED> {

    /**
     * Sets a configuration attribute.
     * @param key the name of the attribute
     * @param value the value of the attribute
     * @return this configuration
     */
    @Incubating
    ATTRIBUTED attribute(String key, String value);

    @Incubating
    <T> ATTRIBUTED attribute(Attribute<T> key, T value);

    /**
     * Sets multiple configuration attributes at once. The attributes are copied from the source map.
     * This method can be used with both a {@link Attribute proper attribute key},
     * or with a {@link String} in which case the type of the attribute is expected to be a {@link String}.
     * Type safety is guaranteed at runtime.
     * @param attributes the attributes to be copied to this configuration
     * @return this configuration
     */
    @Incubating
    ATTRIBUTED attributes(Map<?, ?> attributes);

    /**
     * Returns this configuration attributes.
     * @return the attribute set of this configuration
     */
    @Incubating
    AttributeContainer getAttributes();

    /**
     * Returns the value of a configuration attribute, or <code>null</code> if not found
     * @param key the key of the attribute
     * @param <T> the type of the attribute
     * @return the attribute value or <code>null</code> if not found
     */
    @Incubating
    <T> T getAttribute(Attribute<T> key);

    /**
     * Tells if this configuration defines attributes.
     * @return true if this configuration has attributes.
     */
    @Incubating
    boolean hasAttributes();
}
