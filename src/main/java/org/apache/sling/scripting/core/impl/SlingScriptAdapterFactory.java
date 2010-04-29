/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.scripting.core.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;

import org.apache.sling.api.adapter.AdapterFactory;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.scripting.SlingScriptConstants;
import org.apache.sling.commons.mime.MimeTypeProvider;
import org.apache.sling.scripting.api.BindingsValuesProvider;
import org.apache.sling.scripting.api.ScriptEngineManagerFactory;
import org.apache.sling.scripting.core.impl.helper.SlingScriptEngineManager;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AdapterFactory that adapts Resources to the DefaultSlingScript servlet, which
 * executes the Resources as scripts.
 *
 * @scr.component metatype="no" immediate="true"
 * @scr.property name="service.vendor" value="The Apache Software Foundation"
 * @scr.property name="service.description" value="Default SlingScriptResolver"
 * @scr.property name="adaptables"
 *               value="org.apache.sling.api.resource.Resource";
 * @scr.property name="adapters"
 *               values.0="org.apache.sling.api.scripting.SlingScript"
 *               values.1="javax.servlet.Servlet"
 * @scr.service interface="org.apache.sling.api.adapter.AdapterFactory"
 * @scr.service interface="org.apache.sling.scripting.api.ScriptEngineManagerFactory"
 * @scr.service interface="org.apache.sling.commons.mime.MimeTypeProvider"
 * @scr.reference name="ScriptEngineFactory"
 *                interface="javax.script.ScriptEngineFactory"
 *                cardinality="0..n" policy="dynamic"
 */
public class SlingScriptAdapterFactory implements AdapterFactory, MimeTypeProvider, BundleListener, ScriptEngineManagerFactory {

    private final Logger log = LoggerFactory.getLogger(SlingScriptAdapterFactory.class);

    private static final String ENGINE_FACTORY_SERVICE = "META-INF/services/" + ScriptEngineFactory.class.getName();

    /** list of service property values which indicate 'any' script engine */
    private static final List<String> ANY_ENGINE = Arrays.asList("*", "ANY");

    private ScriptEngineManager scriptEngineManager;

    private List<Bundle> engineSpiBundles = new LinkedList<Bundle>();

    private List<ScriptEngineFactory> engineSpiServices = new LinkedList<ScriptEngineFactory>();

    private BundleContext bundleContext;

    /**
     * The service tracker for the event admin
     */
    private ServiceTracker eventAdminTracker;

    /**
     * The service tracker for BindingsValuesProvider impls
     */
    private ServiceTracker bindingsValuesProviderTracker;

    /**
     * The service tracker for Map impls with scripting bindings
     */
    private ServiceTracker mapBindingsValuesProviderTracker;

    /**
     * The BindingsValuesProvider impls which apply to all languages. Keys are serviceIds.
     */
    private Map<Object, BindingsValuesProvider> genericBindingsValuesProviders;

    /**
     * The BindingsValuesProvider impls which apply to a specific language.
     */
    private Map<String, Map<Object, BindingsValuesProvider>> langBindingsValuesProviders;

    /**
     * The service cache for script execution.
     */
    private ServiceCache serviceCache;

    // ---------- AdapterFactory -----------------------------------------------

    @SuppressWarnings("unchecked")
    public <AdapterType> AdapterType getAdapter(Object adaptable, Class<AdapterType> type) {

        Resource resource = (Resource) adaptable;
        String path = resource.getPath();
        String ext = path.substring(path.lastIndexOf('.') + 1);

        ScriptEngine engine = getScriptEngineManager().getEngineByExtension(ext);
        if (engine != null) {
            Collection<BindingsValuesProvider> bindingsValuesProviders = getBindingsValuesProviders(engine.getFactory());
            // unchecked cast
            return (AdapterType) new DefaultSlingScript(this.bundleContext,
                    resource, engine, bindingsValuesProviders, this.serviceCache);
        }

        return null;
    }

    public ScriptEngineManager getScriptEngineManager() {
        if (scriptEngineManager == null) {

            // create (empty) script engine manager
            ClassLoader loader = getClass().getClassLoader();
            SlingScriptEngineManager tmp = new SlingScriptEngineManager(loader);

            // register script engines from bundles
            final SortedSet<Object> extensions = new TreeSet<Object>();
            for (Bundle bundle : engineSpiBundles) {
                extensions.addAll(registerFactories(tmp, bundle));
            }

            // register script engines from registered services
            for (ScriptEngineFactory factory : engineSpiServices) {
                extensions.addAll(registerFactory(tmp, factory));
            }

            scriptEngineManager = tmp;

            // Log messages to verify which ScriptEngine is actually used
            // for our registered extensions
            if (log.isInfoEnabled()) {
                for (Object o : extensions) {
                    final String ext = o.toString();
                    final ScriptEngine e = scriptEngineManager.getEngineByExtension(ext);
                    if (e == null) {
                        log.warn("No ScriptEngine found for extension '{}' that was just registered", ext);
                    } else {
                        log.info("Script extension '{}' is now handled by ScriptEngine '{}', version='{}', class='{}'", new Object[] { ext,
                                e.getFactory().getEngineName(), e.getFactory().getEngineVersion(), e.getClass().getName() });
                    }
                }
            }
        }
        return scriptEngineManager;
    }

    @SuppressWarnings("unchecked")
    private Collection<?> registerFactories(SlingScriptEngineManager mgr, Bundle bundle) {
        URL url = bundle.getEntry(ENGINE_FACTORY_SERVICE);
        InputStream ins = null;
        final SortedSet<String> extensions = new TreeSet<String>();
        try {
            ins = url.openStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(ins));
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    Class<ScriptEngineFactory> clazz = bundle.loadClass(line);
                    ScriptEngineFactory spi = clazz.newInstance();
                    registerFactory(mgr, spi);
                    extensions.addAll(spi.getExtensions());
                } catch (Throwable t) {
                    log.error("Cannot register ScriptEngineFactory " + line, t);
                }
            }
        } catch (IOException ioe) {
        } finally {
            if (ins != null) {
                try {
                    ins.close();
                } catch (IOException ioe) {
                }
            }
        }

        return extensions;
    }

    private Collection<?> registerFactory(SlingScriptEngineManager mgr, ScriptEngineFactory factory) {
        log.info("Adding ScriptEngine {}, {} for language {}, {}", new Object[] { factory.getEngineName(), factory.getEngineVersion(),
                factory.getLanguageName(), factory.getLanguageVersion() });

        mgr.registerScriptEngineFactory(factory);

        return factory.getExtensions();
    }

    // ---------- BundleListener interface -------------------------------------

    public void bundleChanged(BundleEvent event) {
        if (event.getType() == BundleEvent.STARTED && event.getBundle().getEntry(ENGINE_FACTORY_SERVICE) != null) {

            engineSpiBundles.add(event.getBundle());
            scriptEngineManager = null;

        } else if (event.getType() == BundleEvent.STOPPED && engineSpiBundles.remove(event.getBundle())) {

            scriptEngineManager = null;

        }
    }

    // ---------- MimeTypeProvider

    /**
     * Returns the first MIME type entry of the supported MIME types of a
     * ScriptEngineFactory which is registered for the extension of the given
     * name. If no ScriptEngineFactory is registered for the given extension or
     * the registered ScriptEngineFactory is not registered for a MIME type,
     * this method returns <code>null</code>.
     *
     * @param name The name whose extension is to be mapped to a MIME type. The
     *            extension is the string after the last dot in the name. If the
     *            name contains no dot, the entire name is considered the
     *            extension.
     */
    public String getMimeType(String name) {
        name = name.substring(name.lastIndexOf('.') + 1);
        ScriptEngine se = getScriptEngineManager().getEngineByExtension(name);
        if (se != null) {
            List<?> mimeTypes = se.getFactory().getMimeTypes();
            if (mimeTypes != null && mimeTypes.size() > 0) {
                return String.valueOf(mimeTypes.get(0));
            }
        }

        return null;
    }

    /**
     * Returns the first extension entry of the supported extensions of a
     * ScriptEngineFactory which is registered for the given MIME type. If no
     * ScriptEngineFactory is registered for the given MIME type or the
     * registered ScriptEngineFactory is not registered for an extensions, this
     * method returns <code>null</code>.
     *
     * @param mimeType The MIME type to be mapped to an extension.
     */
    public String getExtension(String mimeType) {
        ScriptEngine se = getScriptEngineManager().getEngineByMimeType(mimeType);
        if (se != null) {
            List<?> extensions = se.getFactory().getExtensions();
            if (extensions != null && extensions.size() > 0) {
                return String.valueOf(extensions.get(0));
            }
        }

        return null;
    }

    // ---------- SCR integration ----------------------------------------------

    protected void activate(ComponentContext context) {
        this.bundleContext = context.getBundleContext();

        // setup tracker first as this is used in the bind/unbind methods
        this.eventAdminTracker = new ServiceTracker(this.bundleContext, EventAdmin.class.getName(), null);
        this.eventAdminTracker.open();

        this.genericBindingsValuesProviders = new HashMap<Object, BindingsValuesProvider>();
        this.langBindingsValuesProviders = new HashMap<String, Map<Object, BindingsValuesProvider>>();

        ServiceTrackerCustomizer customizer = new BindingsValuesProviderCustomizer();

        this.bindingsValuesProviderTracker = new ServiceTracker(this.bundleContext, BindingsValuesProvider.class.getName(), customizer);
        this.bindingsValuesProviderTracker.open();

        try {
            Filter filter = this.bundleContext.createFilter(String.format("(&(objectclass=%s)(javax.script.name=*))",
                    Map.class.getName()));

            this.mapBindingsValuesProviderTracker = new ServiceTracker(this.bundleContext, filter, customizer);
            this.mapBindingsValuesProviderTracker.open();
        } catch (InvalidSyntaxException e) {
            log.warn("Unable to create ServiceTracker for Map-based script bindiings", e);
        }

        this.bundleContext.addBundleListener(this);

        Bundle[] bundles = this.bundleContext.getBundles();
        for (Bundle bundle : bundles) {
            if (bundle.getState() == Bundle.ACTIVE && bundle.getEntry(ENGINE_FACTORY_SERVICE) != null) {
                engineSpiBundles.add(bundle);
            }
        }

        try {
            org.apache.sling.scripting.core.impl.ScriptEngineConsolePlugin.initPlugin(context.getBundleContext(), this);
        } catch (Throwable t) {
            // so what ?
        }
        this.serviceCache = new ServiceCache(this.bundleContext);
    }

    protected void deactivate(ComponentContext context) {
        this.serviceCache.dispose();
        this.serviceCache = null;

        try {
            org.apache.sling.scripting.core.impl.ScriptEngineConsolePlugin.destroyPlugin();
        } catch (Throwable t) {
            // so what ?
        }

        context.getBundleContext().removeBundleListener(this);

        engineSpiBundles.clear();
        engineSpiServices.clear();
        scriptEngineManager = null;
        if (this.eventAdminTracker != null) {
            this.eventAdminTracker.close();
            this.eventAdminTracker = null;
        }
        if (this.bindingsValuesProviderTracker != null) {
            this.bindingsValuesProviderTracker.close();
            this.bindingsValuesProviderTracker = null;
        }
        if (this.mapBindingsValuesProviderTracker != null) {
            this.mapBindingsValuesProviderTracker.close();
            this.mapBindingsValuesProviderTracker = null;
        }
        this.bundleContext = null;
    }

    /**
     * Get the event admin.
     *
     * @return The event admin or <code>null</code>
     */
    private EventAdmin getEventAdmin() {
        return (EventAdmin) (this.eventAdminTracker != null ? this.eventAdminTracker.getService() : null);
    }

    @SuppressWarnings("unchecked")
    private String[] toArray(List list) {
        return (String[]) list.toArray(new String[list.size()]);
    }

    private void postEvent(final String topic, final ScriptEngineFactory scriptEngineFactory) {
        final EventAdmin localEA = this.getEventAdmin();
        if (localEA != null) {
            final Dictionary<String, Object> props = new Hashtable<String, Object>();
            props.put(SlingScriptConstants.PROPERTY_SCRIPT_ENGINE_FACTORY_NAME, scriptEngineFactory.getEngineName());
            props.put(SlingScriptConstants.PROPERTY_SCRIPT_ENGINE_FACTORY_VERSION, scriptEngineFactory.getEngineVersion());
            props.put(SlingScriptConstants.PROPERTY_SCRIPT_ENGINE_FACTORY_EXTENSIONS, toArray(scriptEngineFactory.getExtensions()));
            props.put(SlingScriptConstants.PROPERTY_SCRIPT_ENGINE_FACTORY_LANGUAGE_NAME, scriptEngineFactory.getLanguageName());
            props.put(SlingScriptConstants.PROPERTY_SCRIPT_ENGINE_FACTORY_LANGUAGE_VERSION, scriptEngineFactory.getLanguageVersion());
            props.put(SlingScriptConstants.PROPERTY_SCRIPT_ENGINE_FACTORY_MIME_TYPES, toArray(scriptEngineFactory.getMimeTypes()));
            localEA.postEvent(new Event(topic, props));
        }
    }

    private Collection<BindingsValuesProvider> getBindingsValuesProviders(ScriptEngineFactory scriptEngineFactory) {
        List<BindingsValuesProvider> results = new ArrayList<BindingsValuesProvider>();
        results.addAll(genericBindingsValuesProviders.values());
        for (String name : scriptEngineFactory.getNames()) {
            Map<Object, BindingsValuesProvider> langProviders = langBindingsValuesProviders.get(name);
            if (langProviders != null) {
                results.addAll(langProviders.values());
            }
        }
        return results;
    }

    protected void bindScriptEngineFactory(ScriptEngineFactory scriptEngineFactory) {
        engineSpiServices.add(scriptEngineFactory);
        scriptEngineManager = null;
        // send event
        postEvent(SlingScriptConstants.TOPIC_SCRIPT_ENGINE_FACTORY_ADDED, scriptEngineFactory);
    }

    protected void unbindScriptEngineFactory(ScriptEngineFactory scriptEngineFactory) {
        engineSpiServices.remove(scriptEngineFactory);
        scriptEngineManager = null;
        // send event
        postEvent(SlingScriptConstants.TOPIC_SCRIPT_ENGINE_FACTORY_REMOVED, scriptEngineFactory);
    }

    private class BindingsValuesProviderCustomizer implements ServiceTrackerCustomizer {

        @SuppressWarnings("unchecked")
        public Object addingService(ServiceReference ref) {
            String engineName = (String) ref.getProperty(ScriptEngine.NAME);
            Object serviceId = ref.getProperty(Constants.SERVICE_ID);
            Object service = bundleContext.getService(ref);
            if (service instanceof Map) {
                service = new MapWrappingBindingsValuesProvider((Map) service);
            }
            if (engineName == null || ANY_ENGINE.contains(engineName.toUpperCase())) {
                genericBindingsValuesProviders.put(serviceId, (BindingsValuesProvider) service);
            } else {
                Map<Object, BindingsValuesProvider> langProviders = langBindingsValuesProviders.get(engineName);
                if (langProviders == null) {
                    langProviders = new HashMap<Object, BindingsValuesProvider>();
                    langBindingsValuesProviders.put(engineName, langProviders);
                }

                langProviders.put(serviceId, (BindingsValuesProvider) service);
            }
            return service;
        }

        public void modifiedService(ServiceReference ref, Object service) {
            removedService(ref, service);
            addingService(ref);
        }

        public void removedService(ServiceReference ref, Object service) {
            Object serviceId = ref.getProperty(Constants.SERVICE_ID);
            if (genericBindingsValuesProviders.remove(serviceId) == null) {
                for (Map<Object, BindingsValuesProvider> coll : langBindingsValuesProviders.values()) {
                    if (coll.remove(service) != null) {
                        return;
                    }
                }
            }
        }

    }

    private class MapWrappingBindingsValuesProvider implements BindingsValuesProvider {

        private Map<String,Object> map;

        MapWrappingBindingsValuesProvider(Map<String, Object> map) {
            this.map = map;
        }

        public void addBindings(Bindings bindings) {
            for (String key : map.keySet()) {
                bindings.put(key, map.get(key));
            }
        }

    }

}
