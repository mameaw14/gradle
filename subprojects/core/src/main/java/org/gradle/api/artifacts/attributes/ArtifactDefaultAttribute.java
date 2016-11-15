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

package org.gradle.api.artifacts.attributes;

import com.google.common.base.Objects;
import org.gradle.api.Incubating;

@Incubating
abstract public class ArtifactDefaultAttribute {

    private final String value;

    public ArtifactDefaultAttribute(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (other == null) {
            return false;
        }
        if (!getClass().equals(other.getClass())) {
            return false;
        }
        return Objects.equal(value, ((ArtifactDefaultAttribute) other).getValue());
    }

    @Override
    public int hashCode() {
        return value == null ? super.hashCode() : value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
