/*
 * Copyright 2012-2017 the original author or authors.
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

package org.springframework.boot.web.embedded.tomcat;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletContainerInitializer;

import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.Manager;
import org.apache.catalina.Valve;
import org.apache.catalina.WebResourceRoot.ResourceSetType;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.session.StandardManager;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.startup.Tomcat.FixContextListener;
import org.apache.catalina.webresources.TomcatURLStreamHandlerFactory;
import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.ProtocolHandler;
import org.apache.coyote.http11.AbstractHttp11JsseProtocol;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;

import org.springframework.boot.web.server.Compression;
import org.springframework.boot.web.server.ErrorPage;
import org.springframework.boot.web.server.MimeMappings;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.Ssl.ClientAuth;
import org.springframework.boot.web.server.SslStoreProvider;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.WebServerException;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.server.AbstractServletWebServerFactory;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;

/**
 * {@link AbstractServletWebServerFactory} that can be used to create
 * {@link TomcatWebServer}s. Can be initialized using Spring's
 * {@link ServletContextInitializer}s or Tomcat {@link LifecycleListener}s.
 * <p>
 * Unless explicitly configured otherwise this factory will created containers that
 * listens for HTTP requests on port 8080.
 *
 * @author Phillip Webb
 * @author Dave Syer
 * @author Brock Mills
 * @author Stephane Nicoll
 * @author Andy Wilkinson
 * @author Eddú Meléndez
 * @author Christoffer Sawicki
 * @since 2.0.0
 * @see #setPort(int)
 * @see #setContextLifecycleListeners(Collection)
 * @see TomcatWebServer
 */
public class TomcatServletWebServerFactory extends AbstractServletWebServerFactory
		implements ResourceLoaderAware {

	private static final Charset DEFAULT_CHARSET = Charset.forName("UTF-8");

	private static final Set<Class<?>> NO_CLASSES = Collections.emptySet();

	/**
	 * The class name of default protocol used.
	 */
	public static final String DEFAULT_PROTOCOL = "org.apache.coyote.http11.Http11NioProtocol";

	private File baseDirectory;

	private List<Valve> engineValves = new ArrayList<>();

	private List<Valve> contextValves = new ArrayList<>();

	private List<LifecycleListener> contextLifecycleListeners = new ArrayList<>();

	private List<TomcatContextCustomizer> tomcatContextCustomizers = new ArrayList<>();

	private List<TomcatConnectorCustomizer> tomcatConnectorCustomizers = new ArrayList<>();

	private List<Connector> additionalTomcatConnectors = new ArrayList<>();

	private ResourceLoader resourceLoader;

	private String protocol = DEFAULT_PROTOCOL;

	private Set<String> tldSkipPatterns = new LinkedHashSet<>(TldSkipPatterns.DEFAULT);

	private Charset uriEncoding = DEFAULT_CHARSET;

	private int backgroundProcessorDelay;

	/**
	 * Create a new {@link TomcatServletWebServerFactory} instance.
	 */
	public TomcatServletWebServerFactory() {
		super();
	}

	/**
	 * Create a new {@link TomcatServletWebServerFactory} that listens for requests using
	 * the specified port.
	 * @param port the port to listen on
	 */
	public TomcatServletWebServerFactory(int port) {
		super(port);
	}

	/**
	 * Create a new {@link TomcatServletWebServerFactory} with the specified context path
	 * and port.
	 * @param contextPath the root context path
	 * @param port the port to listen on
	 */
	public TomcatServletWebServerFactory(String contextPath, int port) {
		super(contextPath, port);
	}

	@Override
	public WebServer getWebServer(ServletContextInitializer... initializers) {
		Tomcat tomcat = new Tomcat();
		File baseDir = (this.baseDirectory != null ? this.baseDirectory
				: createTempDir("tomcat"));
		tomcat.setBaseDir(baseDir.getAbsolutePath());
		Connector connector = new Connector(this.protocol);
		tomcat.getService().addConnector(connector);
		customizeConnector(connector);
		tomcat.setConnector(connector);
		tomcat.getHost().setAutoDeploy(false);
		configureEngine(tomcat.getEngine());
		for (Connector additionalConnector : this.additionalTomcatConnectors) {
			tomcat.getService().addConnector(additionalConnector);
		}
		prepareContext(tomcat.getHost(), initializers);
		return getTomcatWebServer(tomcat);
	}

	private void configureEngine(Engine engine) {
		engine.setBackgroundProcessorDelay(this.backgroundProcessorDelay);
		for (Valve valve : this.engineValves) {
			engine.getPipeline().addValve(valve);
		}
	}

	protected void prepareContext(Host host, ServletContextInitializer[] initializers) {
		File docBase = getValidDocumentRoot();
		docBase = (docBase != null ? docBase : createTempDir("tomcat-docbase"));
		final TomcatEmbeddedContext context = new TomcatEmbeddedContext();
		context.setName(getContextPath());
		context.setDisplayName(getDisplayName());
		context.setPath(getContextPath());
		context.setDocBase(docBase.getAbsolutePath());
		context.addLifecycleListener(new FixContextListener());
		context.setParentClassLoader(
				this.resourceLoader != null ? this.resourceLoader.getClassLoader()
						: ClassUtils.getDefaultClassLoader());
		resetDefaultLocaleMapping(context);
		addLocaleMappings(context);
		try {
			context.setUseRelativeRedirects(false);
		}
		catch (NoSuchMethodError ex) {
			// Tomcat is < 8.0.30. Continue
		}
		SkipPatternJarScanner.apply(context, this.tldSkipPatterns);
		WebappLoader loader = new WebappLoader(context.getParentClassLoader());
		loader.setLoaderClass(TomcatEmbeddedWebappClassLoader.class.getName());
		loader.setDelegate(true);
		context.setLoader(loader);
		if (isRegisterDefaultServlet()) {
			addDefaultServlet(context);
		}
		if (shouldRegisterJspServlet()) {
			addJspServlet(context);
			addJasperInitializer(context);
		}
		context.addLifecycleListener(new StaticResourceConfigurer(context));
		ServletContextInitializer[] initializersToUse = mergeInitializers(initializers);
		configureContext(context, initializersToUse);
		host.addChild(context);
		postProcessContext(context);
	}

	/**
	 * Override Tomcat's default locale mappings to align with other servers. See
	 * {@code org.apache.catalina.util.CharsetMapperDefault.properties}.
	 * @param context the context to reset
	 */
	private void resetDefaultLocaleMapping(TomcatEmbeddedContext context) {
		context.addLocaleEncodingMappingParameter(Locale.ENGLISH.toString(),
				DEFAULT_CHARSET.displayName());
		context.addLocaleEncodingMappingParameter(Locale.FRENCH.toString(),
				DEFAULT_CHARSET.displayName());
	}

	private void addLocaleMappings(TomcatEmbeddedContext context) {
		for (Map.Entry<Locale, Charset> entry : getLocaleCharsetMappings().entrySet()) {
			Locale locale = entry.getKey();
			Charset charset = entry.getValue();
			context.addLocaleEncodingMappingParameter(locale.toString(),
					charset.toString());
		}
	}

	private void addDefaultServlet(Context context) {
		Wrapper defaultServlet = context.createWrapper();
		defaultServlet.setName("default");
		defaultServlet.setServletClass("org.apache.catalina.servlets.DefaultServlet");
		defaultServlet.addInitParameter("debug", "0");
		defaultServlet.addInitParameter("listings", "false");
		defaultServlet.setLoadOnStartup(1);
		// Otherwise the default location of a Spring DispatcherServlet cannot be set
		defaultServlet.setOverridable(true);
		context.addChild(defaultServlet);
		addServletMapping(context, "/", "default");
	}

	private void addJspServlet(Context context) {
		Wrapper jspServlet = context.createWrapper();
		jspServlet.setName("jsp");
		jspServlet.setServletClass(getJsp().getClassName());
		jspServlet.addInitParameter("fork", "false");
		for (Entry<String, String> initParameter : getJsp().getInitParameters()
				.entrySet()) {
			jspServlet.addInitParameter(initParameter.getKey(), initParameter.getValue());
		}
		jspServlet.setLoadOnStartup(3);
		context.addChild(jspServlet);
		addServletMapping(context, "*.jsp", "jsp");
		addServletMapping(context, "*.jspx", "jsp");
	}

	@SuppressWarnings("deprecation")
	private void addServletMapping(Context context, String pattern, String name) {
		context.addServletMapping(pattern, name);
	}

	private void addJasperInitializer(TomcatEmbeddedContext context) {
		try {
			ServletContainerInitializer initializer = (ServletContainerInitializer) ClassUtils
					.forName("org.apache.jasper.servlet.JasperInitializer", null)
					.newInstance();
			context.addServletContainerInitializer(initializer, null);
		}
		catch (Exception ex) {
			// Probably not Tomcat 8
		}
	}

	// Needs to be protected so it can be used by subclasses
	protected void customizeConnector(Connector connector) {
		int port = (getPort() >= 0 ? getPort() : 0);
		connector.setPort(port);
		if (StringUtils.hasText(this.getServerHeader())) {
			connector.setAttribute("server", this.getServerHeader());
		}
		if (connector.getProtocolHandler() instanceof AbstractProtocol) {
			customizeProtocol((AbstractProtocol<?>) connector.getProtocolHandler());
		}
		if (getUriEncoding() != null) {
			connector.setURIEncoding(getUriEncoding().name());
		}

		// If ApplicationContext is slow to start we want Tomcat not to bind to the socket
		// prematurely...
		connector.setProperty("bindOnInit", "false");

		if (getSsl() != null && getSsl().isEnabled()) {
			customizeSsl(connector);
		}
		if (getCompression() != null && getCompression().getEnabled()) {
			customizeCompression(connector);
		}
		for (TomcatConnectorCustomizer customizer : this.tomcatConnectorCustomizers) {
			customizer.customize(connector);
		}
	}

	private void customizeProtocol(AbstractProtocol<?> protocol) {
		if (getAddress() != null) {
			protocol.setAddress(getAddress());
		}
	}

	private void customizeSsl(Connector connector) {
		ProtocolHandler handler = connector.getProtocolHandler();
		Assert.state(handler instanceof AbstractHttp11JsseProtocol,
				"To use SSL, the connector's protocol handler must be an "
						+ "AbstractHttp11JsseProtocol subclass");
		configureSsl((AbstractHttp11JsseProtocol<?>) handler, getSsl());
		connector.setScheme("https");
		connector.setSecure(true);
	}

	private void customizeCompression(Connector connector) {
		ProtocolHandler handler = connector.getProtocolHandler();
		if (handler instanceof AbstractHttp11Protocol) {
			AbstractHttp11Protocol<?> protocol = (AbstractHttp11Protocol<?>) handler;
			Compression compression = getCompression();
			protocol.setCompression("on");
			protocol.setCompressionMinSize(compression.getMinResponseSize());
			protocol.setCompressibleMimeType(
					StringUtils.arrayToCommaDelimitedString(compression.getMimeTypes()));
			if (getCompression().getExcludedUserAgents() != null) {
				protocol.setNoCompressionUserAgents(
						StringUtils.arrayToCommaDelimitedString(
								getCompression().getExcludedUserAgents()));
			}
		}
	}

	/**
	 * Configure Tomcat's {@link AbstractHttp11JsseProtocol} for SSL.
	 * @param protocol the protocol
	 * @param ssl the ssl details
	 */
	protected void configureSsl(AbstractHttp11JsseProtocol<?> protocol, Ssl ssl) {
		protocol.setSSLEnabled(true);
		protocol.setSslProtocol(ssl.getProtocol());
		configureSslClientAuth(protocol, ssl);
		protocol.setKeystorePass(ssl.getKeyStorePassword());
		protocol.setKeyPass(ssl.getKeyPassword());
		protocol.setKeyAlias(ssl.getKeyAlias());
		String ciphers = StringUtils.arrayToCommaDelimitedString(ssl.getCiphers());
		protocol.setCiphers(StringUtils.hasText(ciphers) ? ciphers : null);
		if (ssl.getEnabledProtocols() != null) {
			try {
				for (SSLHostConfig sslHostConfig : protocol.findSslHostConfigs()) {
					sslHostConfig.setProtocols(StringUtils
							.arrayToCommaDelimitedString(ssl.getEnabledProtocols()));
				}
			}
			catch (NoSuchMethodError ex) {
				// Tomcat 8.0.x or earlier
				Assert.isTrue(
						protocol.setProperty("sslEnabledProtocols",
								StringUtils.arrayToCommaDelimitedString(
										ssl.getEnabledProtocols())),
						"Failed to set sslEnabledProtocols");
			}
		}
		if (getSslStoreProvider() != null) {
			TomcatURLStreamHandlerFactory instance = TomcatURLStreamHandlerFactory
					.getInstance();
			instance.addUserFactory(
					new SslStoreProviderUrlStreamHandlerFactory(getSslStoreProvider()));
			protocol.setKeystoreFile(
					SslStoreProviderUrlStreamHandlerFactory.KEY_STORE_URL);
			protocol.setTruststoreFile(
					SslStoreProviderUrlStreamHandlerFactory.TRUST_STORE_URL);
		}
		else {
			configureSslKeyStore(protocol, ssl);
			configureSslTrustStore(protocol, ssl);
		}
	}

	private void configureSslClientAuth(AbstractHttp11JsseProtocol<?> protocol, Ssl ssl) {
		if (ssl.getClientAuth() == ClientAuth.NEED) {
			protocol.setClientAuth(Boolean.TRUE.toString());
		}
		else if (ssl.getClientAuth() == ClientAuth.WANT) {
			protocol.setClientAuth("want");
		}
	}

	protected void configureSslStoreProvider(AbstractHttp11JsseProtocol<?> protocol,
			SslStoreProvider sslStoreProvider) {
		Assert.isInstanceOf(Http11NioProtocol.class, protocol,
				"SslStoreProvider can only be used with Http11NioProtocol");
	}

	private void configureSslKeyStore(AbstractHttp11JsseProtocol<?> protocol, Ssl ssl) {
		try {
			protocol.setKeystoreFile(ResourceUtils.getURL(ssl.getKeyStore()).toString());
		}
		catch (FileNotFoundException ex) {
			throw new WebServerException("Could not load key store: " + ex.getMessage(),
					ex);
		}
		if (ssl.getKeyStoreType() != null) {
			protocol.setKeystoreType(ssl.getKeyStoreType());
		}
		if (ssl.getKeyStoreProvider() != null) {
			protocol.setKeystoreProvider(ssl.getKeyStoreProvider());
		}
	}

	private void configureSslTrustStore(AbstractHttp11JsseProtocol<?> protocol, Ssl ssl) {

		if (ssl.getTrustStore() != null) {
			try {
				protocol.setTruststoreFile(
						ResourceUtils.getURL(ssl.getTrustStore()).toString());
			}
			catch (FileNotFoundException ex) {
				throw new WebServerException(
						"Could not load trust store: " + ex.getMessage(), ex);
			}
		}
		protocol.setTruststorePass(ssl.getTrustStorePassword());
		if (ssl.getTrustStoreType() != null) {
			protocol.setTruststoreType(ssl.getTrustStoreType());
		}
		if (ssl.getTrustStoreProvider() != null) {
			protocol.setTruststoreProvider(ssl.getTrustStoreProvider());
		}
	}

	/**
	 * Configure the Tomcat {@link Context}.
	 * @param context the Tomcat context
	 * @param initializers initializers to apply
	 */
	protected void configureContext(Context context,
			ServletContextInitializer[] initializers) {
		TomcatStarter starter = new TomcatStarter(initializers);
		if (context instanceof TomcatEmbeddedContext) {
			// Should be true
			((TomcatEmbeddedContext) context).setStarter(starter);
		}
		context.addServletContainerInitializer(starter, NO_CLASSES);
		for (LifecycleListener lifecycleListener : this.contextLifecycleListeners) {
			context.addLifecycleListener(lifecycleListener);
		}
		for (Valve valve : this.contextValves) {
			context.getPipeline().addValve(valve);
		}
		for (ErrorPage errorPage : getErrorPages()) {
			new TomcatErrorPage(errorPage).addToContext(context);
		}
		for (MimeMappings.Mapping mapping : getMimeMappings()) {
			context.addMimeMapping(mapping.getExtension(), mapping.getMimeType());
		}
		configureSession(context);
		for (TomcatContextCustomizer customizer : this.tomcatContextCustomizers) {
			customizer.customize(context);
		}
	}

	private void configureSession(Context context) {
		long sessionTimeout = getSessionTimeoutInMinutes();
		context.setSessionTimeout((int) sessionTimeout);
		if (isPersistSession()) {
			Manager manager = context.getManager();
			if (manager == null) {
				manager = new StandardManager();
				context.setManager(manager);
			}
			configurePersistSession(manager);
		}
		else {
			context.addLifecycleListener(new DisablePersistSessionListener());
		}
	}

	private void configurePersistSession(Manager manager) {
		Assert.state(manager instanceof StandardManager,
				"Unable to persist HTTP session state using manager type "
						+ manager.getClass().getName());
		File dir = getValidSessionStoreDir();
		File file = new File(dir, "SESSIONS.ser");
		((StandardManager) manager).setPathname(file.getAbsolutePath());
	}

	private long getSessionTimeoutInMinutes() {
		long sessionTimeout = getSessionTimeout();
		if (sessionTimeout > 0) {
			sessionTimeout = Math.max(TimeUnit.SECONDS.toMinutes(sessionTimeout), 1L);
		}
		return sessionTimeout;
	}

	/**
	 * Post process the Tomcat {@link Context} before it used with the Tomcat Server.
	 * Subclasses can override this method to apply additional processing to the
	 * {@link Context}.
	 * @param context the Tomcat {@link Context}
	 */
	protected void postProcessContext(Context context) {
	}

	/**
	 * Factory method called to create the {@link TomcatWebServer}. Subclasses can
	 * override this method to return a different {@link TomcatWebServer} or apply
	 * additional processing to the Tomcat server.
	 * @param tomcat the Tomcat server.
	 * @return a new {@link TomcatWebServer} instance
	 */
	protected TomcatWebServer getTomcatWebServer(Tomcat tomcat) {
		return new TomcatWebServer(tomcat, getPort() >= 0);
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	/**
	 * Set the Tomcat base directory. If not specified a temporary directory will be used.
	 * @param baseDirectory the tomcat base directory
	 */
	public void setBaseDirectory(File baseDirectory) {
		this.baseDirectory = baseDirectory;
	}

	/**
	 * A comma-separated list of jars to ignore for TLD scanning. See Tomcat's
	 * catalina.properties for typical values. Defaults to a list drawn from that source.
	 * @param tldSkip the jars to skip when scanning for TLDs etc
	 * @deprecated as of 1.5 in favor of {@link #setTldSkipPatterns(Collection)}
	 */
	@Deprecated
	public void setTldSkip(String tldSkip) {
		Assert.notNull(tldSkip, "TldSkip must not be null");
		setTldSkipPatterns(StringUtils.commaDelimitedListToSet(tldSkip));
	}

	/**
	 * Returns a mutable set of the patterns that match jars to ignore for TLD scanning.
	 * @return the list of jars to ignore for TLD scanning
	 */
	public Set<String> getTldSkipPatterns() {
		return this.tldSkipPatterns;
	}

	/**
	 * Set the patterns that match jars to ignore for TLD scanning. See Tomcat's
	 * catalina.properties for typical values. Defaults to a list drawn from that source.
	 * @param patterns the jar patterns to skip when scanning for TLDs etc
	 */
	public void setTldSkipPatterns(Collection<String> patterns) {
		Assert.notNull(patterns, "Patterns must not be null");
		this.tldSkipPatterns = new LinkedHashSet<>(patterns);
	}

	/**
	 * Add patterns that match jars to ignore for TLD scanning. See Tomcat's
	 * catalina.properties for typical values.
	 * @param patterns the additional jar patterns to skip when scanning for TLDs etc
	 */
	public void addTldSkipPatterns(String... patterns) {
		Assert.notNull(patterns, "Patterns must not be null");
		this.tldSkipPatterns.addAll(Arrays.asList(patterns));
	}

	/**
	 * The Tomcat protocol to use when create the {@link Connector}.
	 * @param protocol the protocol
	 * @see Connector#Connector(String)
	 */
	public void setProtocol(String protocol) {
		Assert.hasLength(protocol, "Protocol must not be empty");
		this.protocol = protocol;
	}

	/**
	 * Set {@link Valve}s that should be applied to the Tomcat {@link Engine}. Calling
	 * this method will replace any existing valves.
	 * @param engineValves the valves to set
	 */
	public void setEngineValves(Collection<? extends Valve> engineValves) {
		Assert.notNull(engineValves, "Valves must not be null");
		this.engineValves = new ArrayList<>(engineValves);
	}

	/**
	 * Returns a mutable collection of the {@link Valve}s that will be applied to the
	 * Tomcat {@link Engine}.
	 * @return the engineValves the valves that will be applied
	 */
	public Collection<Valve> getEngineValves() {
		return this.engineValves;
	}

	/**
	 * Add {@link Valve}s that should be applied to the Tomcat {@link Engine}.
	 * @param engineValves the valves to add
	 */
	public void addEngineValves(Valve... engineValves) {
		Assert.notNull(engineValves, "Valves must not be null");
		this.engineValves.addAll(Arrays.asList(engineValves));
	}

	/**
	 * Set {@link Valve}s that should be applied to the Tomcat {@link Context}. Calling
	 * this method will replace any existing valves.
	 * @param contextValves the valves to set
	 */
	public void setContextValves(Collection<? extends Valve> contextValves) {
		Assert.notNull(contextValves, "Valves must not be null");
		this.contextValves = new ArrayList<>(contextValves);
	}

	/**
	 * Returns a mutable collection of the {@link Valve}s that will be applied to the
	 * Tomcat {@link Context}.
	 * @return the contextValves the valves that will be applied
	 * @see #getEngineValves()
	 */
	public Collection<Valve> getContextValves() {
		return this.contextValves;
	}

	/**
	 * Add {@link Valve}s that should be applied to the Tomcat {@link Context}.
	 * @param contextValves the valves to add
	 */
	public void addContextValves(Valve... contextValves) {
		Assert.notNull(contextValves, "Valves must not be null");
		this.contextValves.addAll(Arrays.asList(contextValves));
	}

	/**
	 * Set {@link LifecycleListener}s that should be applied to the Tomcat {@link Context}
	 * . Calling this method will replace any existing listeners.
	 * @param contextLifecycleListeners the listeners to set
	 */
	public void setContextLifecycleListeners(
			Collection<? extends LifecycleListener> contextLifecycleListeners) {
		Assert.notNull(contextLifecycleListeners,
				"ContextLifecycleListeners must not be null");
		this.contextLifecycleListeners = new ArrayList<>(contextLifecycleListeners);
	}

	/**
	 * Returns a mutable collection of the {@link LifecycleListener}s that will be applied
	 * to the Tomcat {@link Context} .
	 * @return the contextLifecycleListeners the listeners that will be applied
	 */
	public Collection<LifecycleListener> getContextLifecycleListeners() {
		return this.contextLifecycleListeners;
	}

	/**
	 * Add {@link LifecycleListener}s that should be added to the Tomcat {@link Context}.
	 * @param contextLifecycleListeners the listeners to add
	 */
	public void addContextLifecycleListeners(
			LifecycleListener... contextLifecycleListeners) {
		Assert.notNull(contextLifecycleListeners,
				"ContextLifecycleListeners must not be null");
		this.contextLifecycleListeners.addAll(Arrays.asList(contextLifecycleListeners));
	}

	/**
	 * Set {@link TomcatContextCustomizer}s that should be applied to the Tomcat
	 * {@link Context} . Calling this method will replace any existing customizers.
	 * @param tomcatContextCustomizers the customizers to set
	 */
	public void setTomcatContextCustomizers(
			Collection<? extends TomcatContextCustomizer> tomcatContextCustomizers) {
		Assert.notNull(tomcatContextCustomizers,
				"TomcatContextCustomizers must not be null");
		this.tomcatContextCustomizers = new ArrayList<>(tomcatContextCustomizers);
	}

	/**
	 * Returns a mutable collection of the {@link TomcatContextCustomizer}s that will be
	 * applied to the Tomcat {@link Context} .
	 * @return the listeners that will be applied
	 */
	public Collection<TomcatContextCustomizer> getTomcatContextCustomizers() {
		return this.tomcatContextCustomizers;
	}

	/**
	 * Add {@link TomcatContextCustomizer}s that should be added to the Tomcat
	 * {@link Context}.
	 * @param tomcatContextCustomizers the customizers to add
	 */
	public void addContextCustomizers(
			TomcatContextCustomizer... tomcatContextCustomizers) {
		Assert.notNull(tomcatContextCustomizers,
				"TomcatContextCustomizers must not be null");
		this.tomcatContextCustomizers.addAll(Arrays.asList(tomcatContextCustomizers));
	}

	/**
	 * Set {@link TomcatConnectorCustomizer}s that should be applied to the Tomcat
	 * {@link Connector} . Calling this method will replace any existing customizers.
	 * @param tomcatConnectorCustomizers the customizers to set
	 */
	public void setTomcatConnectorCustomizers(
			Collection<? extends TomcatConnectorCustomizer> tomcatConnectorCustomizers) {
		Assert.notNull(tomcatConnectorCustomizers,
				"TomcatConnectorCustomizers must not be null");
		this.tomcatConnectorCustomizers = new ArrayList<>(tomcatConnectorCustomizers);
	}

	/**
	 * Add {@link TomcatContextCustomizer}s that should be added to the Tomcat
	 * {@link Connector}.
	 * @param tomcatConnectorCustomizers the customizers to add
	 */
	public void addConnectorCustomizers(
			TomcatConnectorCustomizer... tomcatConnectorCustomizers) {
		Assert.notNull(tomcatConnectorCustomizers,
				"TomcatConnectorCustomizers must not be null");
		this.tomcatConnectorCustomizers.addAll(Arrays.asList(tomcatConnectorCustomizers));
	}

	/**
	 * Returns a mutable collection of the {@link TomcatConnectorCustomizer}s that will be
	 * applied to the Tomcat {@link Context} .
	 * @return the listeners that will be applied
	 */
	public Collection<TomcatConnectorCustomizer> getTomcatConnectorCustomizers() {
		return this.tomcatConnectorCustomizers;
	}

	/**
	 * Add {@link Connector}s in addition to the default connector, e.g. for SSL or AJP
	 * @param connectors the connectors to add
	 */
	public void addAdditionalTomcatConnectors(Connector... connectors) {
		Assert.notNull(connectors, "Connectors must not be null");
		this.additionalTomcatConnectors.addAll(Arrays.asList(connectors));
	}

	/**
	 * Returns a mutable collection of the {@link Connector}s that will be added to the
	 * Tomcat.
	 * @return the additionalTomcatConnectors
	 */
	public List<Connector> getAdditionalTomcatConnectors() {
		return this.additionalTomcatConnectors;
	}

	/**
	 * Set the character encoding to use for URL decoding. If not specified 'UTF-8' will
	 * be used.
	 * @param uriEncoding the uri encoding to set
	 */
	public void setUriEncoding(Charset uriEncoding) {
		this.uriEncoding = uriEncoding;
	}

	/**
	 * Returns the character encoding to use for URL decoding.
	 * @return the URI encoding
	 */
	public Charset getUriEncoding() {
		return this.uriEncoding;
	}

	/**
	 * Sets the background processor delay in seconds.
	 * @param delay the delay in seconds
	 * @since 1.4.1
	 */
	public void setBackgroundProcessorDelay(int delay) {
		this.backgroundProcessorDelay = delay;
	}

	/**
	 * {@link LifecycleListener} to disable persistence in the {@link StandardManager}. A
	 * {@link LifecycleListener} is used so not to interfere with Tomcat's default manager
	 * creation logic.
	 */
	private static class DisablePersistSessionListener implements LifecycleListener {

		@Override
		public void lifecycleEvent(LifecycleEvent event) {
			if (event.getType().equals(Lifecycle.START_EVENT)) {
				Context context = (Context) event.getLifecycle();
				Manager manager = context.getManager();
				if (manager != null && manager instanceof StandardManager) {
					((StandardManager) manager).setPathname(null);
				}
			}
		}

	}

	private final class StaticResourceConfigurer implements LifecycleListener {

		private final Context context;

		private StaticResourceConfigurer(Context context) {
			this.context = context;
		}

		@Override
		public void lifecycleEvent(LifecycleEvent event) {
			if (event.getType().equals(Lifecycle.CONFIGURE_START_EVENT)) {
				addResourceJars(getUrlsOfJarsWithMetaInfResources());
			}
		}

		private void addResourceJars(List<URL> resourceJarUrls) {
			for (URL url : resourceJarUrls) {
				String file = url.getFile();
				if (file.endsWith(".jar") || file.endsWith(".jar!/")) {
					String jar = url.toString();
					if (!jar.startsWith("jar:")) {
						// A jar file in the file system. Convert to Jar URL.
						jar = "jar:" + jar + "!/";
					}
					addResourceSet(jar);
				}
				else {
					addResourceSet(url.toString());
				}
			}
		}

		private void addResourceSet(String resource) {
			try {
				if (isInsideNestedJar(resource)) {
					// It's a nested jar but we now don't want the suffix because Tomcat
					// is going to try and locate it as a root URL (not the resource
					// inside it)
					resource = resource.substring(0, resource.length() - 2);
				}
				URL url = new URL(resource);
				String path = "/META-INF/resources";
				this.context.getResources().createWebResourceSet(
						ResourceSetType.RESOURCE_JAR, "/", url, path);
			}
			catch (Exception ex) {
				// Ignore (probably not a directory)
			}
		}

		private boolean isInsideNestedJar(String dir) {
			return dir.indexOf("!/") < dir.lastIndexOf("!/");
		}

	}

}
