/*
 * Copyright 2013 the original author or authors.
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

package org.gradle.plugin.resolve.internal;

import org.gradle.api.Plugin;
import org.gradle.api.internal.plugins.PluginRegistry;
import org.gradle.api.plugins.UnknownPluginException;
import org.gradle.plugin.resolve.NoClasspathAdditionPluginResolution;
import org.gradle.plugin.resolve.PluginRequest;
import org.gradle.plugin.resolve.PluginResolution;
import org.gradle.plugin.resolve.PluginResolver;

public class PluginRegistryPluginResolver implements PluginResolver {

    private final PluginRegistry pluginRegistry;

    public PluginRegistryPluginResolver(PluginRegistry pluginRegistry) {
        this.pluginRegistry = pluginRegistry;
    }

    public PluginResolution resolve(PluginRequest pluginRequest) {
        try {
            Class<? extends Plugin> typeForId = pluginRegistry.getTypeForId(pluginRequest.getId());
            return new NoClasspathAdditionPluginResolution(typeForId.getName());
        } catch (UnknownPluginException e) {
            return null;
        }
    }

    public String getName() {
        return "core plugins";
    }
}
