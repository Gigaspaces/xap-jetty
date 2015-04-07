/*
 * Copyright 2006-2007 the original author or authors.
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

package org.openspaces.pu.container.jee.jetty;

import com.j_spaces.kernel.ClassLoaderHelper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.util.log.JavaUtilLog;
import org.eclipse.jetty.util.resource.FileResource;
import org.eclipse.jetty.webapp.WebAppContext;
import org.jini.rio.boot.CommonClassLoader;
import org.jini.rio.boot.ServiceClassLoader;
import org.openspaces.core.cluster.ClusterInfo;
import org.openspaces.core.properties.BeanLevelProperties;
import org.openspaces.pu.container.CannotCreateContainerException;
import org.openspaces.pu.container.ProcessingUnitContainer;
import org.openspaces.pu.container.jee.JeeProcessingUnitContainerProvider;
import org.openspaces.pu.container.jee.jetty.holder.JettyHolder;
import org.openspaces.pu.container.jee.jetty.support.*;
import org.openspaces.pu.container.support.BeanLevelPropertiesUtils;
import org.openspaces.pu.container.support.ClusterInfoParser;
import org.openspaces.pu.container.support.ResourceApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import javax.management.MBeanServer;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.BindException;
import java.net.URL;
import java.util.*;

/**
 * An implementation of {@link org.openspaces.pu.container.jee.JeeProcessingUnitContainerProvider} that
 * can run web applications (war files) using Jetty.
 *
 * <p>The jetty xml configuration is loaded from {@link #DEFAULT_JETTY_PU} location if it exists. If
 * it does not exists, two defaults exists, one is the <code>jetty.plain.pu.xml</code> and the other
 * is <code>jetty.shared.xml</code>. By default, if nothing is passed, the <code>jetty.plain.xml<code>
 * is loaded.
 *
 * <p>The difference between plain and shared mode is only indicated by the built in jerry spring
 * application context. The plain mode starts a jetty instance per web application instance. The
 * shared mode uses the same jetty instance for all web applications. The default is plain mode
 * as it is usually the simpler and preferred way to use it.
 *
 * <p>The web application will be enabled automatically for OpenSpaces bootstrapping using
 * {@link org.openspaces.pu.container.jee.context.BootstrapWebApplicationContextListener}.
 *
 * <p>Post processing of the <code>web.xml</code> and <code>jetty-web.xml</code> is performed allowing
 * to use <code>${...}</code> notation within them (for example, using system properties, deployed
 * properties, or <code>${clusterInfo...}</code>).
 *
 * <p>JMX in jetty can be enabled by passing a deployment property {@link #JETTY_JMX_PROP}. If set
 * to <code>true</code> jetty will be configured with JMX. In plain mode, where there can be more
 * than one instance of jetty within the same JVM, the domain each instance will be registered under
 * will be <code>gigaspaces.jetty.${clusterInfo.name}.${clusterInfo.runningNumberOffset1}</code>.
 * In shared mode, where there is only one instance of jetty in a single JVM, jetty JMX will be registered
 * with a domain called <code>gigaspaces.jetty</code>.
 *
 * @author kimchy
 */
public class JettyJeeProcessingUnitContainerProvider extends JeeProcessingUnitContainerProvider {

    static {
        System.setProperty("org.eclipse.jetty.util.log.class", JavaUtilLog.class.getName());
        // disable jetty server shutdown hook
        System.setProperty("JETTY_NO_SHUTDOWN_HOOK", "true");
    }

    private static final Log logger = LogFactory.getLog(JettyJeeProcessingUnitContainerProvider.class);


    /**
     * The optional location where a jetty spring application context (responsible for configuring
     * jetty) will be loaded (within the processing unit). If does not exists, will load either the
     * plain or shared built in jetty configurations (controlled by {@link #JETTY_INSTANCE_PROP})
     * defaulting to plain.
     */
    public final static String DEFAULT_JETTY_PU = "/META-INF/spring/jetty.pu.xml";

    public final static String INTERNAL_JETTY_PU_PREFIX = "/org/openspaces/pu/container/jee/jetty/jetty.";

    public final static String INSTANCE_PLAIN = "plain";

    public final static String INSTANCE_SHARD = "shared";

    public static final String JETTY_LOCATION_PREFIX_SYSPROP = "com.gs.pu.jee.jetty.pu.locationPrefix";

    public static final String JETTY_INSTANCE_PROP = "jetty.instance";

    /**
     * The deployment property controlling if JMX is enabled or not. Defaults to <code>false</code>
     * (JMX is disabled).
     */
    public static final String JETTY_JMX_PROP = "jetty.jmx";

    private static final ThreadLocal<ApplicationContext> currentApplicationContext = new ThreadLocal<ApplicationContext>();

    private static final ThreadLocal<ClusterInfo> currentClusterInfo = new ThreadLocal<ClusterInfo>();

    private static final ThreadLocal<BeanLevelProperties> currentBeanLevelProperties = new ThreadLocal<BeanLevelProperties>();

    /**
     * Allows to get the current application context (loaded from <code>pu.xml</code>) during web application
     * startup. Can be used to access beans defined within it (like a Space) by components loaded (such as
     * session storage). Note, this is only applicable during web application startup. It is cleared right
     * afterwards.
     */
    public static ApplicationContext getCurrentApplicationContext() {
        return currentApplicationContext.get();
    }

    /**
     * Internal used to set the application context loaded from the <code>pu.xml</code> on a thread local
     * so components within the web container (such as session storage) will be able to access it during
     * startup time of the web application using {@link #getCurrentApplicationContext()}.
     */
    private static void setCurrentApplicationContext(ApplicationContext applicationContext) {
        currentApplicationContext.set(applicationContext);
    }

    public static ClusterInfo getCurrentClusterInfo() {
        return currentClusterInfo.get();
    }

    private static void setCurrentClusterInfo(ClusterInfo clusterInfo) {
        currentClusterInfo.set(clusterInfo);
    }

    public static BeanLevelProperties getCurrentBeanLevelProperties() {
        return currentBeanLevelProperties.get();
    }

    private static void setCurrentBeanLevelProperties(BeanLevelProperties beanLevelProperties) {
        currentBeanLevelProperties.set(beanLevelProperties);
    }

    @Override
    public String getJeeContainerType() {
        return "jetty";
    }

    /**
     * See the header javadoc.
     */
    public ProcessingUnitContainer createContainer() throws CannotCreateContainerException {
        addConfigLocation(getJettyPuResource());

        if (getClusterInfo() != null) {
            ClusterInfoParser.guessSchema(getClusterInfo());
        }

        final ResourceApplicationContext applicationContext = initApplicationContext();

        ClassLoader origClassLoader = Thread.currentThread().getContextClassLoader();
        JettyHolder jettyHolder = null;
        List<FreePortGenerator.PortHandle> portHandles = new ArrayList<FreePortGenerator.PortHandle>();
        try {
            try {
                ClassLoaderHelper.setContextClassLoader(getJeeClassLoader(), true);
            } catch (Exception e) {
                // ignore ...
            }

            // "start" the application context
            applicationContext.refresh();

            jettyHolder = initJettyHolder(applicationContext, portHandles);

            initJettyJmx(jettyHolder);

            String[] filesToResolve = new String[] {
                    "WEB-INF/web.xml",
                    "WEB-INF/jetty-web.xml",
                    "WEB-INF/jetty6-web.xml",
                    "WEB-INF/web-jetty.xml"};
            for (String fileToResolve : filesToResolve) {
                try {
                    BeanLevelPropertiesUtils.resolvePlaceholders(getBeanLevelProperties(), new File(getDeployPath(), fileToResolve));
                } catch (IOException e) {
                    throw new CannotCreateContainerException("Failed to resolve properties on " + fileToResolve, e);
                }
            }
        } finally {
            ClassLoaderHelper.setContextClassLoader(origClassLoader, true);
        }

        try {
            setCurrentApplicationContext(applicationContext);
            setCurrentBeanLevelProperties(getBeanLevelProperties());
            setCurrentClusterInfo(getClusterInfo());

            // we disable the smart getUrl in the common class loader so the JSP classpath will be built correctly
            CommonClassLoader.getInstance().setDisableSmartGetUrl(true);

            WebAppContext webAppContext = initWebAppContext(applicationContext);

            HandlerContainer container = jettyHolder.getServer();

            ContextHandlerCollection contextHandlerContainer = null;
            Handler[] contexts = jettyHolder.getServer().getChildHandlersByClass(ContextHandlerCollection.class);
            if (contexts != null && contexts.length > 0) {
                contextHandlerContainer = (ContextHandlerCollection) contexts[0];
            } else {
                while (container != null) {
                    if (container instanceof HandlerWrapper) {
                        HandlerWrapper wrapper = (HandlerWrapper) container;
                        Handler handler = wrapper.getHandler();
                        if (handler == null)
                            break;
                        if (handler instanceof HandlerContainer)
                            contextHandlerContainer = (ContextHandlerCollection) handler;
                        else
                            throw new IllegalStateException("No container");
                    }
                    throw new IllegalStateException("No container");
                }
            }
            contextHandlerContainer.addHandler(webAppContext);

            if (container.isStarted() || container.isStarting()) {
                origClassLoader = Thread.currentThread().getContextClassLoader();
                try {
                    // we set the parent class loader of the web application to be the jee container class loader
                    // this is to basically to hide the service class loader from it (and openspaces jars and so on)
                    ClassLoaderHelper.setContextClassLoader(getJeeClassLoader(), true);
                    webAppContext.start();
                } catch (Exception e) {
                    throw new CannotCreateContainerException("Failed to start web app context", e);
                } finally {
                    ClassLoaderHelper.setContextClassLoader(origClassLoader, true);
                }
            }
            if (webAppContext.getUnavailableException() != null) {
                throw new CannotCreateContainerException("Failed to start web app context", webAppContext.getUnavailableException());
            }
            if (webAppContext.isFailed()) {
                throw new CannotCreateContainerException("Failed to start web app context (exception should be logged)");
            }

            JettyProcessingUnitContainer processingUnitContainer = new JettyProcessingUnitContainer(applicationContext, webAppContext, contextHandlerContainer, jettyHolder, portHandles);
            logger.info("Deployed web application [" + processingUnitContainer.getJeeDetails().getDescription() + "]");
            return processingUnitContainer;
        } catch (Exception e) {
            try {
                jettyHolder.stop();
            } catch (Exception e1) {
                logger.debug("Failed to stop jetty after an error occured, ignoring", e);
            }
            if (e instanceof CannotCreateContainerException) {
                throw ((CannotCreateContainerException) e);
            }
            throw new CannotCreateContainerException("Failed to start web application", e);
        } finally {
            setCurrentApplicationContext(null);
            setCurrentBeanLevelProperties(null);
            setCurrentClusterInfo(null);

            CommonClassLoader.getInstance().setDisableSmartGetUrl(false);
        }
    }

    private Resource getJettyPuResource() {
        Resource jettyPuResource = new ClassPathResource(DEFAULT_JETTY_PU);
        if (!jettyPuResource.exists()) {
            String instanceProp = getBeanLevelProperties().getContextProperties().getProperty(JETTY_INSTANCE_PROP, INSTANCE_PLAIN);
            String defaultLocation = System.getProperty(JETTY_LOCATION_PREFIX_SYSPROP, INTERNAL_JETTY_PU_PREFIX) + instanceProp + ".pu.xml";
            jettyPuResource = new ClassPathResource(defaultLocation);
            if (!jettyPuResource.exists()) {
                throw new CannotCreateContainerException("Failed to read internal pu file [" + defaultLocation + "] as well as user defined [" + DEFAULT_JETTY_PU + "]");
            }
            if (logger.isDebugEnabled()) {
                logger.debug("Using internal built in jetty pu.xml from [" + defaultLocation + "]");
            }
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("Using user specific jetty pu.xml from [" + DEFAULT_JETTY_PU + "]");
            }
        }

        return jettyPuResource;
    }

    private JettyHolder initJettyHolder(ResourceApplicationContext applicationContext, List<FreePortGenerator.PortHandle> portHandles) {
        JettyHolder jettyHolder = (JettyHolder) applicationContext.getBean("jettyHolder");

        int retryPortCount = 20;
        try {
            retryPortCount = (Integer) applicationContext.getBean("retryPortCount");
        } catch (Exception e) {
            // do nothing
        }

        FreePortGenerator freePortGenerator = new NoOpFreePortGenerator();
        String freePortGeneratorSetting = getBeanLevelProperties().getContextProperties().getProperty("jetty.freePortGenerator", "file");
        if ("file".equalsIgnoreCase(freePortGeneratorSetting)) {
            freePortGenerator = new FileLockFreePortGenerator();
        }

        // only check ports if the server is not running already
        if (!jettyHolder.getServer().isStarted()) {
            boolean success = false;
            for (int i = 0; i < retryPortCount; i++) {
                for (Connector connector : jettyHolder.getServer().getConnectors()) {
                    if (connector.getPort() != 0) {
                        FreePortGenerator.PortHandle portHandle = freePortGenerator.nextAvailablePort(connector.getPort(), retryPortCount);
                        for (Connector connector1 : jettyHolder.getServer().getConnectors()) {
                            if (connector1 instanceof AbstractConnector) {
                                if (connector.getPort() == connector1.getConfidentialPort()) {
                                    // if the confidential port of one connectors points to this connector that we are changing its port
                                    // then update it as well
                                    ((AbstractConnector) connector1).setConfidentialPort(portHandle.getPort());
                                }
                            }
                        }
                        connector.setPort(portHandle.getPort());
                        portHandles.add(portHandle);
                    }
                }

                try {
                    jettyHolder.openConnectors();
                    success = true;
                    break;
                } catch (BindException e) {
                    for (FreePortGenerator.PortHandle portHandle : portHandles) {
                        portHandle.release();
                    }
                    portHandles.clear();
                    try {
                        jettyHolder.closeConnectors();
                    } catch (Exception e1) {
                        logger.debug(e1);
                        // ignore
                    }
                    for (Connector connector : jettyHolder.getServer().getConnectors()) {
                        for (Connector connector1 : jettyHolder.getServer().getConnectors()) {
                            if (connector1 instanceof AbstractConnector) {
                                if (connector.getPort() == connector1.getConfidentialPort()) {
                                    // if the confidential port of one connectors points to this connector that we are changing its port
                                    // then update it as well
                                    ((AbstractConnector) connector1).setConfidentialPort(connector.getPort() + 1);
                                }
                            }
                        }
                        connector.setPort(connector.getPort() + 1);
                    }
                } catch (Exception e) {
                    for (FreePortGenerator.PortHandle portHandle : portHandles) {
                        portHandle.release();
                    }
                    portHandles.clear();
                    try {
                        jettyHolder.closeConnectors();
                    } catch (Exception e1) {
                        logger.debug(e1);
                        // ignore
                    }
                    if (e instanceof CannotCreateContainerException)
                        throw (CannotCreateContainerException) e;
                    throw new CannotCreateContainerException("Failed to start jetty server", e);
                }
            }
            if (!success) {
                throw new CannotCreateContainerException("Failed to bind jetty to port with retries [" + retryPortCount + "]");
            }
        }
        for (Connector connector : jettyHolder.getServer().getConnectors()) {
            logger.info("Using Jetty server connector [" + connector.getClass().getName() + "], Host [" + connector.getHost() + "], Port [" + connector.getPort() + "], Confidential Port [" + connector.getConfidentialPort() + "]");
        }

        try {
            jettyHolder.start();
        } catch (Exception e) {
            try {
                jettyHolder.stop();
            } catch (Exception e1) {
                logger.debug(e1);
                // ignore
            }
            if (e instanceof CannotCreateContainerException)
                throw (CannotCreateContainerException) e;
            throw new CannotCreateContainerException("Failed to start jetty server", e);
        }

        return jettyHolder;
    }

    private void initJettyJmx(JettyHolder jettyHolder) {
        String jmxEnabled = getBeanLevelProperties().getContextProperties().getProperty(JETTY_JMX_PROP, "false");
        if ("true".equals(jmxEnabled)) {
            MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
            MBeanContainer mBeanContainer = new MBeanContainer(mBeanServer);
            String domain = "gigaspaces.jetty";
            if (!jettyHolder.isSingleInstance()) {
                domain += "." + getClusterInfo().getName() + "." + getClusterInfo().getRunningNumberOffset1();
            }
            mBeanContainer.setDomain(domain);
            jettyHolder.getServer().getContainer().addEventListener(mBeanContainer);
            try {
                mBeanContainer.start();
            } catch (Exception e) {
                logger.warn("Failed to start jetty mbean container", e);
            }
        }
    }

    private WebAppContext initWebAppContext(ResourceApplicationContext applicationContext) throws Exception {
        WebAppContext webAppContext = (WebAppContext) applicationContext.getBean("webAppContext");
        webAppContext.setExtractWAR(true);

        // allow aliases so load balancing will work on static content
        if (!webAppContext.getInitParams().containsKey("org.eclipse.jetty.servlet.Default.aliases")) {
            webAppContext.getInitParams().put("org.eclipse.jetty.servlet.Default.aliases", "true");
        }
        // when using file mapped buffers, jetty does not release the files when closing the web application
        // resulting in not being able to deploy again the application (failure to write the file again)
        if (!webAppContext.getInitParams().containsKey("org.eclipse.jetty.servlet.Default.useFileMappedBuffer")) {
            webAppContext.getInitParams().put("org.eclipse.jetty.servlet.Default.useFileMappedBuffer", "false");
        }

        // by default, the web app context will delegate log4j and commons logging to the parent class loader
        // allow to disable that
        if (getBeanLevelProperties().getContextProperties().getProperty("com.gs.pu.jee.jetty.modifySystemClasses", "false").equalsIgnoreCase("true")) {
            Set<String> systemClasses = new HashSet<String>(Arrays.asList(webAppContext.getSystemClasses()));
            systemClasses.remove("org.apache.commons.logging.");
            systemClasses.remove("org.apache.log4j.");
            webAppContext.setSystemClasses(systemClasses.toArray(new String[systemClasses.size()]));
        }
        // don't hide server (jetty) classes from context, since we use it in the JettyWebApplicationContextListener
        webAppContext.setServerClasses(new String[0]);

        webAppContext.setDisplayName("web." + getClusterInfo().getName() + "." + getClusterInfo().getSuffix());

        webAppContext.setClassLoader(initWebAppClassLoader(webAppContext));

        final String SESSION_MANAGER_BEAN = "sessionManager";
        if( applicationContext.containsBean( SESSION_MANAGER_BEAN ) ){
            SessionManager sessionManager =
                    ( SessionManager )applicationContext.getBean( SESSION_MANAGER_BEAN );
            if( sessionManager != null ){
                SessionHandler sessionHandler = webAppContext.getSessionHandler();
                if( sessionHandler != null ){
                    //fix for GS-10830 , CLOUDIFY-1797
                    sessionHandler.setSessionManager( sessionManager );
                }
            }
        }

        return webAppContext;
    }

    private JettyWebAppClassLoader initWebAppClassLoader(WebAppContext webAppContext) throws Exception {
        // Provide our own extension to jetty class loader, so we can get the name for it in our logging
        ServiceClassLoader serviceClassLoader = (ServiceClassLoader) Thread.currentThread().getContextClassLoader();
        JettyWebAppClassLoader webAppClassLoader = new JettyWebAppClassLoader(getJeeClassLoader(), webAppContext, serviceClassLoader.getLogName());

        // add pu-common & web-pu-common jar files
        for (String jar : super.getWebAppClassLoaderJars()) {
            webAppClassLoader.addJars(new FileResource(new File(jar).toURL()));
        }
        for (String classpath : super.getWebAppClassLoaderClassPath()) {
            webAppClassLoader.addClassPath(classpath);
        }
        if (getManifestURLs() != null) {
            for (URL url : getManifestURLs()) {
                webAppClassLoader.addClassPath(new FileResource(url));
            }
        }

        return webAppClassLoader;
    }
}
